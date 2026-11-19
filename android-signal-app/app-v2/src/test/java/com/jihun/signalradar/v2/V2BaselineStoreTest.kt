package com.jihun.signalradar.v2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.SignalRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 전파 감지기 v2.2 — the saved 기준 list on its own, without the screens:
 *
 *  - a name is kept (and a blank one becomes 기준 1, 기준 2, …)
 *  - more than one 기준 is stored, one of them is in use
 *  - switching, renaming, deleting one, and 초기화
 *  - the list survives the app being closed (a second store reads it back)
 *  - a v2.1 baseline is adopted as a named 기준, once
 *  - whatever is in use is what the shared repository compares against
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class V2BaselineStoreTest {

    private lateinit var context: Application

    /** The file the store and [SignalRepository] both use (one per app package). */
    private fun prefs() = context.getSharedPreferences("signal_radar", Context.MODE_PRIVATE)

    @Before
    fun clearSavedState() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun store() = V2BaselineStore(context)

    private fun devices(vararg ids: String): Map<String, Int> =
        ids.associateWith { -55 }.toMap()

    // ------------------------------------------------------------------- names

    @Test
    fun a_saved_baseline_keeps_the_name_it_was_given() {
        val store = store()
        val item = store.add("우리 집", devices("ble:a", "wifi:b"), savedAt = 1_000L)

        assertEquals("우리 집", item.name)
        assertEquals(2, item.size)
        assertEquals(1_000L, item.savedAt)
        assertEquals("우리 집", store.active()?.name)
        assertEquals(1, store.count())
    }

    @Test
    fun a_blank_name_becomes_the_next_automatic_number() {
        val store = store()
        assertEquals("기준 1", store.add("", devices("ble:a")).name)
        assertEquals("기준 2", store.add("   ", devices("ble:b")).name)
        // Deleting the second one frees its number again.
        store.delete(store.all().first().id)
        assertEquals("기준 2", store.add("", devices("ble:c")).name)
    }

    @Test
    fun a_name_already_in_the_list_gets_a_number_behind_it() {
        val store = store()
        store.add("우리 집", devices("ble:a"))
        assertEquals("우리 집 (2)", store.add("우리 집", devices("ble:b")).name)
        assertEquals("우리 집 (3)", store.add("우리 집", devices("ble:c")).name)
    }

    @Test
    fun a_name_is_trimmed_and_cut_to_a_card_width() {
        val store = store()
        val item = store.add("   우리   집   ", devices("ble:a"))
        assertEquals("우리 집", item.name)

        val long = store.add("가".repeat(40), devices("ble:b"))
        assertEquals(V2BaselineStore.MAX_NAME, long.name.length)
    }

    // ---------------------------------------------------------------- switching

    @Test
    fun saving_the_next_baseline_makes_it_the_one_in_use() {
        val store = store()
        val first = store.add("우리 집", devices("ble:a"))
        val second = store.add("복도", devices("ble:b"))

        assertEquals(second.id, store.activeId())
        assertNotEquals(first.id, store.activeId())
        // Newest first, which is the order the cards are drawn in.
        assertEquals(listOf("복도", "우리 집"), store.all().map { it.name })
    }

    @Test
    fun another_baseline_can_be_put_back_in_use() {
        val store = store()
        val home = store.add("우리 집", devices("ble:a"))
        store.add("복도", devices("ble:b"))

        store.setActive(home.id)
        assertEquals("우리 집", store.active()?.name)
        assertEquals(home.id, store.activeId())
    }

    @Test
    fun a_baseline_that_is_not_there_cannot_be_put_in_use() {
        val store = store()
        store.add("우리 집", devices("ble:a"))
        assertNull(store.setActive("no-such-id"))
        assertEquals("우리 집", store.active()?.name)
    }

    // ----------------------------------------------------------------- renaming

    @Test
    fun a_baseline_can_be_renamed_and_a_blank_name_keeps_the_old_one() {
        val store = store()
        val item = store.add("우리 집", devices("ble:a"))

        assertEquals("집", store.rename(item.id, "집")?.name)
        assertEquals("집", store.find(item.id)?.name)
        assertEquals("집", store.rename(item.id, "  ")?.name)
        assertNull(store.rename("no-such-id", "아무거나"))
    }

    @Test
    fun renaming_to_a_name_another_baseline_has_gets_a_number() {
        val store = store()
        store.add("우리 집", devices("ble:a"))
        val corridor = store.add("복도", devices("ble:b"))

        assertEquals("우리 집 (2)", store.rename(corridor.id, "우리 집")?.name)
        // Renaming a baseline to its own name does not number it.
        val home = store.all().first { it.name == "우리 집" }
        assertEquals("우리 집", store.rename(home.id, "우리 집")?.name)
    }

    // ------------------------------------------------------------------ deleting

    @Test
    fun deleting_one_baseline_leaves_the_rest_and_their_names() {
        val store = store()
        val home = store.add("우리 집", devices("ble:a"))
        store.add("복도", devices("ble:b", "ble:c"))

        assertTrue(store.delete(home.id))
        assertEquals(listOf("복도"), store.all().map { it.name })
        assertEquals("복도", store.active()?.name)
        assertFalse(store.delete(home.id))
    }

    @Test
    fun deleting_the_one_in_use_hands_it_to_the_newest_remaining() {
        val store = store()
        store.add("우리 집", devices("ble:a"))
        val corridor = store.add("복도", devices("ble:b"))
        val classroom = store.add("2학년 1반", devices("ble:c"))

        store.delete(classroom.id)
        assertEquals(corridor.id, store.activeId())
        store.delete(corridor.id)
        assertEquals(listOf("우리 집"), store.all().map { it.name })
        assertEquals("우리 집", store.active()?.name)
    }

    @Test
    fun clearing_everything_leaves_no_baseline_and_nothing_in_use() {
        val store = store()
        store.add("우리 집", devices("ble:a"))
        store.add("복도", devices("ble:b"))

        store.clearAll()
        assertTrue(store.isEmpty())
        assertEquals(0, store.count())
        assertNull(store.active())
        assertNull(store.activeId())
        assertEquals("기준 1", store.nextAutoName())
    }

    // ------------------------------------------------------------- persistence

    @Test
    fun the_list_and_the_one_in_use_survive_the_app_being_closed() {
        val first = store()
        val home = first.add("우리 집", devices("ble:a", "wifi:b"))
        first.add("복도", devices("ble:c"))
        first.setActive(home.id)

        val reopened = store()
        assertEquals(listOf("복도", "우리 집"), reopened.all().map { it.name })
        assertEquals("우리 집", reopened.active()?.name)
        assertEquals(mapOf("ble:a" to -55, "wifi:b" to -55), reopened.find(home.id)?.entries)
    }

    // ------------------------------------------------------- the v2.1 upgrade

    @Test
    fun a_baseline_saved_by_v21_is_adopted_once_as_a_named_one() {
        prefs().edit()
            .putString("baseline_v1", """{"ble:a":-40,"ble:b":-70}""")
            .putLong("baseline_saved_at", 1_700_000_000_000L)
            .commit()

        val store = store()
        val adopted = store.adoptLegacy()
        assertNotNull(adopted)
        assertEquals(V2BaselineStore.LEGACY_NAME, adopted?.name)
        assertEquals(1_700_000_000_000L, adopted?.savedAt)
        assertEquals(2, adopted?.size)
        assertEquals(adopted?.id, store.activeId())

        // Runs once: the old keys are gone and the flag is set.
        assertNull(store().adoptLegacy())
        assertEquals(1, store().count())
    }

    @Test
    fun a_cleared_list_does_not_adopt_the_v21_baseline_again() {
        prefs().edit()
            .putString("baseline_v1", """{"ble:a":-40}""")
            .putLong("baseline_saved_at", 1_700_000_000_000L)
            .commit()

        val store = store()
        store.adoptLegacy()
        store.clearAll()

        val reopened = store()
        assertNull(reopened.adoptLegacy())
        assertTrue(reopened.isEmpty())
    }

    // --------------------------------------------- pushing it to the repository

    @Test
    fun the_one_in_use_is_what_the_shared_repository_compares_against() {
        val repo = SignalRepository(context)
        val store = store()
        val home = store.add("우리 집", mapOf("ble:a" to -40, "wifi:b" to -60))
        store.applyActiveTo(repo)

        assertTrue(repo.hasBaseline())
        assertEquals(2, repo.baselineSize())

        // Switching to another 기준 replaces the comparison at once.
        store.add("복도", devices("ble:c"))
        store.applyActiveTo(repo)
        assertEquals(1, repo.baselineSize())

        // And 초기화 leaves the repository without one, so nothing is flagged as new.
        store.clearAll()
        store.applyActiveTo(repo)
        assertFalse(repo.hasBaseline())
        assertEquals(0, repo.baselineSize())
    }
}
