package com.jihun.signalradar.v2

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.SignalRepository
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 전파 감지기 v2.2 — the 기준 tab as 지훈 asked for it:
 *
 *  - save the signals heard now under a name, more than once (우리 집, 2학년 1반, 복도 …)
 *  - a blank name becomes 기준 1, 기준 2, …
 *  - the newest saved one is the one in use, and tapping an older card switches to it
 *  - 이름 바꾸기 and 삭제 on each card, and `🧹 기준 초기화` for all of them
 *  - the top card says which one is in use, or `저장된 기준 없음`
 *  - switching a 기준 switches what 🚨 새 신호 means, immediately
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w360dp-h780dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class V2BaselineTabTest {

    private fun grantScanPermissions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.NEARBY_WIFI_DEVICES"
        )
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("signal_radar", Context.MODE_PRIVATE)

    @Before
    fun clearSavedBaselines() {
        prefs().edit().clear().commit()
    }

    private fun bleSignal(
        address: String = "F8:1E:DF:12:34:56",
        title: String = "지훈의 AirPods",
        rssi: Int = -50
    ) = Signal(
        id = "ble:$address",
        source = SignalSource.BLE,
        title = title,
        vendor = "애플",
        kind = "에어팟",
        detail = "에어팟 · 서비스 배터리",
        address = address,
        rssi = rssi,
        firstSeen = System.currentTimeMillis() - 30_000,
        lastSeen = System.currentTimeMillis(),
        smoothedRssi = rssi.toFloat(),
        txPower = -52,
        broadcastName = title,
        isConnected = true,
        isBonded = true
    )

    private fun wifiSignal(ssid: String = "iptime_5G", rssi: Int = -62) = Signal(
        id = "wifi:$ssid",
        source = SignalSource.WIFI,
        title = ssid,
        vendor = "ipTIME(EFM)",
        kind = "ipTIME 공유기",
        detail = "5GHz · 44채널",
        address = "00:08:9F:00:00:01",
        rssi = rssi,
        firstSeen = System.currentTimeMillis() - 30_000,
        lastSeen = System.currentTimeMillis(),
        smoothedRssi = rssi.toFloat(),
        frequencyMhz = 5745
    )

    private fun repoOf(activity: V2RadarActivity): SignalRepository {
        val field = SignalRadarActivity::class.java.getDeclaredField("repo")
        field.isAccessible = true
        return field.get(activity) as SignalRepository
    }

    private fun refresh(seconds: Long = 1L) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
    }

    private fun open(activity: V2RadarActivity, tab: Int) {
        activity.findViewById<View>(tab).performClick()
        refresh()
    }

    /** `📌 지금 신호를 새 기준으로 저장` → the name card → 저장. */
    private fun save(activity: V2RadarActivity, name: String) {
        activity.findViewById<View>(R.id.btnBaseline).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("the name card must open", dialog.isShowing)
        dialog.findViewById<EditText>(R.id.nameDialogInput).setText(name)
        dialog.findViewById<View>(R.id.nameDialogOk).performClick()
        refresh()
    }

    private fun cards(activity: V2RadarActivity): List<View> {
        val list = activity.findViewById<ViewGroup>(R.id.v2RecordList)
        return (0 until list.childCount).map { list.getChildAt(it) }
    }

    private fun nameOf(card: View) =
        card.findViewById<TextView>(R.id.baselineName).text.toString()

    private fun isApplied(card: View) =
        card.findViewById<View>(R.id.baselineActive).visibility == View.VISIBLE

    // --------------------------------------------------------------------- save

    @Test
    fun two_named_baselines_are_kept_and_the_newest_one_is_in_use() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)

        repo.upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        repo.upsert(listOf(wifiSignal()))
        save(activity, "복도")
        open(activity, R.id.tabRecord)

        assertEquals("복도", activity.findViewById<TextView>(R.id.v2RecordName).text.toString())
        assertEquals("기기 2개", activity.findViewById<TextView>(R.id.v2RecordCount).text.toString())
        assertTrue(
            activity.findViewById<TextView>(R.id.recordSummary).text.contains("저장된 기준 2개")
        )
        assertTrue(
            activity.findViewById<TextView>(R.id.recordSummary).text.contains("적용 중 ‘복도’")
        )

        val cards = cards(activity)
        assertEquals(listOf("복도", "우리 집"), cards.map { nameOf(it) })
        assertTrue("the newest 기준 is the one in use", isApplied(cards[0]))
        assertFalse(isApplied(cards[1]))
        val meta = cards[0].findViewById<TextView>(R.id.baselineMeta).text.toString()
        assertTrue("the card says how much it holds: $meta", meta.startsWith("기기 2개 · "))
        assertTrue("and when it was saved: $meta", meta.endsWith(" 저장"))
        assertEquals(2, repo.baselineSize())
        controller.pause().stop().destroy()
    }

    @Test
    fun a_blank_name_is_saved_as_the_next_automatic_number() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))

        save(activity, "")
        save(activity, "   ")
        open(activity, R.id.tabRecord)

        assertEquals(listOf("기준 2", "기준 1"), cards(activity).map { nameOf(it) })
        assertEquals("기준 2", activity.findViewById<TextView>(R.id.v2RecordName).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun cancelling_the_name_card_saves_nothing() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))

        activity.findViewById<View>(R.id.btnBaseline).performClick()
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.nameDialogCancel).performClick()
        refresh()

        open(activity, R.id.tabRecord)
        assertTrue(cards(activity).isEmpty())
        assertEquals(
            "저장된 기준 없음",
            activity.findViewById<TextView>(R.id.v2RecordName).text.toString()
        )
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------------ switch

    @Test
    fun tapping_an_older_baseline_card_changes_what_counts_as_a_new_signal() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)

        repo.upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        repo.upsert(listOf(wifiSignal()))
        save(activity, "복도")

        // 복도 holds both devices, so nothing is new while it is in use.
        open(activity, R.id.tabSignal)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.alertBanner).visibility)

        // 우리 집 only holds the AirPods, so the Wi-Fi is a new signal there.
        open(activity, R.id.tabRecord)
        cards(activity)[1].performClick()
        refresh()
        open(activity, R.id.tabSignal)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.alertBanner).visibility)
        assertTrue(
            activity.findViewById<TextView>(R.id.alertTitle).text.contains("새 신호")
        )

        // Back to 복도: the new-signal banner goes away again.
        open(activity, R.id.tabRecord)
        cards(activity)[0].performClick()
        refresh()
        open(activity, R.id.tabSignal)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.alertBanner).visibility)

        // The repository compares against whatever card says 적용 중.
        assertEquals(2, repo.baselineSize())
        controller.pause().stop().destroy()
    }

    @Test
    fun a_switched_baseline_is_remembered_when_the_screen_opens_again() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        repoOf(activity).upsert(listOf(wifiSignal()))
        save(activity, "복도")
        open(activity, R.id.tabRecord)
        cards(activity)[1].performClick()
        refresh()
        controller.pause().stop().destroy()

        val again = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val reopened = again.get()
        open(reopened, R.id.tabRecord)
        assertEquals("우리 집", reopened.findViewById<TextView>(R.id.v2RecordName).text.toString())
        assertEquals(listOf("복도", "우리 집"), cards(reopened).map { nameOf(it) })
        assertTrue(isApplied(cards(reopened)[1]))
        assertEquals(1, repoOf(reopened).baselineSize())
        again.pause().stop().destroy()
    }

    // ------------------------------------------------------------ rename/delete

    @Test
    fun a_baseline_is_renamed_from_its_card() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        open(activity, R.id.tabRecord)

        cards(activity)[0].findViewById<View>(R.id.baselineRename).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        val input = dialog.findViewById<EditText>(R.id.nameDialogInput)
        assertEquals("우리 집", input.text.toString())
        input.setText("2학년 1반")
        dialog.findViewById<View>(R.id.nameDialogOk).performClick()
        refresh()

        assertEquals("2학년 1반", nameOf(cards(activity)[0]))
        assertEquals(
            "2학년 1반",
            activity.findViewById<TextView>(R.id.v2RecordName).text.toString()
        )
        assertFalse(
            activity.findViewById<TextView>(R.id.recordSummary).text.contains("우리 집")
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun deleting_one_baseline_keeps_the_others() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        save(activity, "복도")
        open(activity, R.id.tabRecord)
        assertEquals(2, cards(activity).size)

        // Delete 복도, the one in use: 우리 집 takes over.
        cards(activity)[0].findViewById<View>(R.id.baselineDelete).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(
            dialog.findViewById<TextView>(R.id.confirmTitle).text.contains("복도")
        )
        dialog.findViewById<View>(R.id.confirmOk).performClick()
        refresh()

        assertEquals(listOf("우리 집"), cards(activity).map { nameOf(it) })
        assertTrue(isApplied(cards(activity)[0]))
        assertEquals("우리 집", activity.findViewById<TextView>(R.id.v2RecordName).text.toString())
        assertEquals(1, repoOf(activity).baselineSize())
        controller.pause().stop().destroy()
    }

    @Test
    fun cancelling_a_delete_keeps_the_baseline() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        open(activity, R.id.tabRecord)

        cards(activity)[0].findViewById<View>(R.id.baselineDelete).performClick()
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.confirmCancel).performClick()
        refresh()

        assertEquals(1, cards(activity).size)
        assertEquals("우리 집", nameOf(cards(activity)[0]))
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------------- reset

    @Test
    fun the_reset_button_is_hidden_until_something_is_saved() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        open(activity, R.id.tabRecord)

        assertEquals(View.GONE, activity.findViewById<View>(R.id.v2RecordReset).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.v2RecordEmpty).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnBaseline).visibility)
        assertEquals(
            "저장된 기준 없음",
            activity.findViewById<TextView>(R.id.v2RecordName).text.toString()
        )

        repoOf(activity).upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        open(activity, R.id.tabRecord)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.v2RecordReset).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.v2RecordEmpty).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun 기준_초기화_clears_every_baseline_and_the_new_signal_state() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        repo.upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        repo.upsert(listOf(wifiSignal()))
        save(activity, "복도")
        open(activity, R.id.tabRecord)

        activity.findViewById<View>(R.id.v2RecordReset).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.findViewById<TextView>(R.id.confirmMessage).text.contains("2개"))
        dialog.findViewById<View>(R.id.confirmOk).performClick()
        refresh()

        assertTrue("every card is gone", cards(activity).isEmpty())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.v2RecordReset).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.v2RecordEmpty).visibility)
        assertEquals(
            "저장된 기준 없음",
            activity.findViewById<TextView>(R.id.v2RecordName).text.toString()
        )
        assertEquals("저장된 기준", activity.findViewById<TextView>(R.id.recordSummary).text.toString())
        assertFalse(repo.hasBaseline())
        assertEquals(0, repo.baselineSize())

        // Nothing is "new" any more, and nothing is left in the shared file either.
        open(activity, R.id.tabSignal)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.alertBanner).visibility)
        assertEquals("🚨 새 신호", activity.findViewById<TextView>(R.id.chipNew).text.toString())
        assertNull(prefs().getString("v2_baselines_active", null))
        controller.pause().stop().destroy()
    }

    @Test
    fun cancelling_the_reset_keeps_every_baseline() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        save(activity, "우리 집")
        open(activity, R.id.tabRecord)

        activity.findViewById<View>(R.id.v2RecordReset).performClick()
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.confirmCancel).performClick()
        refresh()

        assertEquals(1, cards(activity).size)
        assertTrue(repoOf(activity).hasBaseline())
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------- the v2.1 upgrade

    @Test
    fun a_baseline_saved_by_v21_shows_up_as_a_named_one_after_the_update() {
        prefs().edit()
            .putString("baseline_v1", """{"ble:F8:1E:DF:12:34:56":-50}""")
            .putLong("baseline_saved_at", 1_700_000_000_000L)
            .commit()

        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        open(activity, R.id.tabRecord)

        assertEquals(
            V2BaselineStore.LEGACY_NAME,
            activity.findViewById<TextView>(R.id.v2RecordName).text.toString()
        )
        assertEquals(listOf(V2BaselineStore.LEGACY_NAME), cards(activity).map { nameOf(it) })
        assertEquals(1, repoOf(activity).baselineSize())
        controller.pause().stop().destroy()
    }
}
