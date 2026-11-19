package com.jihun.signalradar.v2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.2 — the saved 기준 (baselines) of 전파 감지기 v2, more than one and with names.
 *
 * 지훈 asked for two things on top of v2.1: a name on every saved 기준, a `기준 초기화` button,
 * and then more than one 기준 kept at the same time ("우리 집", "2학년 1반", "복도") with one
 * of them chosen as the one in use.
 *
 * So a 기준 is now a named snapshot: a name, when it was saved, and the level heard from every
 * bluetooth / Wi-Fi device at that moment. One of them is *active* ([activeId]); the active one
 * is the only thing the "🚨 새 신호" check compares against.
 *
 * The scanning code in `common/` still keeps exactly one baseline in [com.jihun.signalradar.SignalRepository];
 * this store is the list on top of it and pushes the active one down (`applyTo`). That keeps
 * the shared scanner untouched, which is what the other two apps still run.
 *
 * Everything lives in SharedPreferences as JSON, so the list survives the app being closed.
 * The file is the same `signal_radar` one the repository uses (a separate app package means a
 * separate file), which is also what lets an existing v2.1 baseline be adopted as a normal
 * named 기준 on the first launch of v2.2 ([adoptLegacy]).
 */
class V2BaselineStore(context: Context) {

    /** One saved 기준: its name, when it was saved, and the devices it holds. */
    data class Baseline(
        val id: String,
        val name: String,
        val savedAt: Long,
        val entries: Map<String, Int>
    ) {
        val size: Int get() = entries.size
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Newest first, which is the order the 기준 tab draws them in. */
    private val items = ArrayList<Baseline>()

    private var activeId: String? = null

    init {
        load()
    }

    // ------------------------------------------------------------------ reading

    /** Every saved 기준, newest first. */
    fun all(): List<Baseline> = items.toList()

    fun count(): Int = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    /** The 기준 the 🚨 새 신호 check currently compares against, if any. */
    fun active(): Baseline? = items.firstOrNull { it.id == activeId }

    fun activeId(): String? = activeId

    fun find(id: String): Baseline? = items.firstOrNull { it.id == id }

    /** What an empty name becomes: `기준 1`, `기준 2`, … (the lowest number not in use). */
    fun nextAutoName(): String {
        val used = items.mapNotNull { AUTO_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()
        var number = 1
        while (number in used) number++
        return "$AUTO_PREFIX $number"
    }

    // ------------------------------------------------------------------ writing

    /**
     * Save one more 기준 and make it the active one. A blank [name] gets an automatic
     * `기준 N`; a name already in the list gets ` (2)`, ` (3)` behind it so two cards never
     * read the same.
     */
    fun add(
        name: String,
        entries: Map<String, Int>,
        savedAt: Long = System.currentTimeMillis()
    ): Baseline {
        val clean = tidy(name)
        val label = if (clean.isEmpty()) nextAutoName() else uniqueName(clean)
        val item = Baseline(
            id = newId(savedAt),
            name = label,
            savedAt = savedAt,
            entries = LinkedHashMap(entries)
        )
        items.add(0, item)
        activeId = item.id
        persist()
        return item
    }

    /**
     * Rename one 기준. A blank name keeps the old one, so clearing the box by accident cannot
     * leave a card without a name.
     */
    fun rename(id: String, name: String): Baseline? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val clean = tidy(name)
        if (clean.isEmpty()) return items[index]
        val renamed = items[index].copy(name = uniqueName(clean, ignoreId = id))
        items[index] = renamed
        persist()
        return renamed
    }

    /** Choose which 기준 the 🚨 새 신호 check uses. */
    fun setActive(id: String): Baseline? {
        val item = find(id) ?: return null
        if (activeId != id) {
            activeId = id
            persist()
        }
        return item
    }

    /**
     * Delete one 기준. If it was the active one, the newest remaining 기준 takes over, so the
     * app is never left comparing against something that is no longer in the list.
     */
    fun delete(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (!removed) return false
        if (activeId == id) activeId = items.firstOrNull()?.id
        persist()
        return true
    }

    /** `기준 초기화`: drop every 기준 and clear the one in use. */
    fun clearAll() {
        items.clear()
        activeId = null
        persist()
        // The v2.1 baseline this list may have been seeded from goes with it, otherwise the
        // next launch would quietly adopt it again.
        prefs.edit()
            .remove(LEGACY_BASELINE)
            .remove(LEGACY_SAVED_AT)
            .putBoolean(KEY_ADOPTED, true)
            .apply()
    }

    // ------------------------------------------------------------------- pushing

    /**
     * Make [baseline] the one the shared repository compares against.
     *
     * The repository only has one baseline and no "replace it with this map" call, so the map
     * is written in by clearing it and adding every device. Its own saved-at stamp is not used
     * any more — the v2 screens show the 기준's own time from this store.
     */
    fun applyTo(baseline: Baseline, repo: com.jihun.signalradar.SignalRepository) {
        repo.clearBaseline()
        for ((id, rssi) in baseline.entries) repo.addToBaseline(id, rssi)
    }

    /** Apply whichever 기준 is active; with none, the repository is left without one. */
    fun applyActiveTo(repo: com.jihun.signalradar.SignalRepository): Baseline? {
        val active = active()
        if (active == null) {
            repo.clearBaseline()
            return null
        }
        applyTo(active, repo)
        return active
    }

    // --------------------------------------------------------------- v2.1 upgrade

    /**
     * v2.1 saved one baseline without a name, through the shared repository. On the first run
     * of v2.2 that becomes a normal 기준 called [LEGACY_NAME], so an update does not look like
     * the 기준 tab lost what was already saved. Runs at most once.
     */
    fun adoptLegacy(): Baseline? {
        if (items.isNotEmpty()) return null
        if (prefs.getBoolean(KEY_ADOPTED, false)) return null
        val raw = prefs.getString(LEGACY_BASELINE, null)
        if (raw == null) {
            prefs.edit().putBoolean(KEY_ADOPTED, true).apply()
            return null
        }
        val entries = LinkedHashMap<String, Int>()
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                entries[key] = json.optInt(key, -100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "the v2.1 baseline is unreadable, starting without one", e)
            prefs.edit().putBoolean(KEY_ADOPTED, true).apply()
            return null
        }
        // Read the stamp before it is removed, or the adopted 기준 would be dated "now".
        val savedAt = prefs.getLong(LEGACY_SAVED_AT, 0L)
        prefs.edit()
            .putBoolean(KEY_ADOPTED, true)
            .remove(LEGACY_BASELINE)
            .remove(LEGACY_SAVED_AT)
            .apply()
        if (entries.isEmpty()) return null
        return add(LEGACY_NAME, entries, if (savedAt > 0L) savedAt else System.currentTimeMillis())
    }

    // ------------------------------------------------------------- persistence

    private fun persist() {
        val array = JSONArray()
        for (item in items) {
            val entries = JSONObject()
            for ((id, rssi) in item.entries) entries.put(id, rssi)
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("savedAt", item.savedAt)
                    .put("entries", entries)
            )
        }
        prefs.edit()
            .putString(KEY_ITEMS, array.toString())
            .putString(KEY_ACTIVE, activeId)
            .apply()
    }

    private fun load() {
        activeId = prefs.getString(KEY_ACTIVE, null)
        val raw = prefs.getString(KEY_ITEMS, null) ?: return
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val entriesJson = json.optJSONObject("entries") ?: JSONObject()
                val entries = LinkedHashMap<String, Int>()
                val keys = entriesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    entries[key] = entriesJson.optInt(key, -100)
                }
                items.add(
                    Baseline(
                        id = json.optString("id", "b$index"),
                        name = json.optString("name").ifBlank { "$AUTO_PREFIX ${index + 1}" },
                        savedAt = json.optLong("savedAt", 0L),
                        entries = entries
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "saved 기준 unreadable, starting fresh", e)
            items.clear()
            activeId = null
        }
        if (items.isNotEmpty() && items.none { it.id == activeId }) activeId = items.first().id
    }

    // -------------------------------------------------------------------- utils

    private fun tidy(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

    private fun uniqueName(name: String, ignoreId: String? = null): String {
        val taken = items.filter { it.id != ignoreId }.map { it.name }.toSet()
        if (name !in taken) return name
        var suffix = 2
        while ("$name ($suffix)" in taken) suffix++
        return "$name ($suffix)"
    }

    private fun newId(savedAt: Long): String {
        var candidate = "b$savedAt"
        var suffix = 2
        while (items.any { it.id == candidate }) {
            candidate = "b$savedAt-$suffix"
            suffix++
        }
        return candidate
    }

    companion object {
        private const val TAG = "V2BaselineStore"

        /** The same file [com.jihun.signalradar.SignalRepository] writes (one per app package). */
        private const val PREFS = "signal_radar"

        private const val KEY_ITEMS = "v2_baselines"
        private const val KEY_ACTIVE = "v2_baselines_active"
        private const val KEY_ADOPTED = "v2_baselines_adopted"

        /** The v2.1 keys, read once so an update keeps the 기준 the user already saved. */
        private const val LEGACY_BASELINE = "baseline_v1"
        private const val LEGACY_SAVED_AT = "baseline_saved_at"

        internal const val AUTO_PREFIX = "기준"
        internal const val LEGACY_NAME = "저장된 기준"

        /** Long enough for "2학년 1반 화학실", short enough to stay on one card line. */
        internal const val MAX_NAME = 20

        private val AUTO_NAME = Regex("^$AUTO_PREFIX (\\d+)$")
    }
}
