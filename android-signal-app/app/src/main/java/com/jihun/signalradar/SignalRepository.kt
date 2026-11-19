package com.jihun.signalradar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.json.JSONObject

/**
 * Keeps the live picture of everything the phone hears right now, and the saved
 * baseline ("what my room normally looks like") used to spot new arrivals.
 */
class SignalRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val live = HashMap<String, Signal>()
    private val baseline = HashMap<String, Int>()
    private val lock = Any()

    private var baselineSavedAt = 0L

    init {
        loadBaseline()
    }

    fun hasBaseline(): Boolean = synchronized(lock) { baseline.isNotEmpty() }

    fun baselineSize(): Int = synchronized(lock) { baseline.size }

    fun baselineSavedAt(): Long = baselineSavedAt

    /** Merge freshly observed signals, keeping the first time we saw each one. */
    fun upsert(signals: List<Signal>) {
        if (signals.isEmpty()) return
        synchronized(lock) {
            for (signal in signals) {
                val previous = live[signal.id]
                live[signal.id] = if (previous == null) {
                    signal.copy(firstSeen = signal.lastSeen)
                } else {
                    signal.copy(firstSeen = previous.firstSeen)
                }
            }
        }
    }

    /** Drop signals that stopped being heard. */
    fun prune() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val iterator = live.entries.iterator()
            while (iterator.hasNext()) {
                val signal = iterator.next().value
                val limit = when (signal.source) {
                    SignalSource.BLE -> 25_000L
                    SignalSource.WIFI -> 120_000L
                    SignalSource.CELL -> 120_000L
                }
                if (now - signal.lastSeen > limit) iterator.remove()
            }
        }
    }

    /**
     * Snapshot with the baseline comparison applied.
     * Base stations are excluded from the "new" flag on purpose: they change every
     * time you move, so alerting on them would never stop ringing.
     */
    fun snapshot(): List<Signal> = synchronized(lock) {
        val baselineReady = baseline.isNotEmpty()
        live.values.map { signal ->
            val base = baseline[signal.id]
            signal.copy(
                isNew = baselineReady && base == null && signal.source != SignalSource.CELL,
                isBoosted = base != null && signal.rssi - base >= BOOST_DB,
                baselineRssi = base
            )
        }
    }

    /** Store every bluetooth / Wi-Fi signal currently heard as the new baseline. */
    fun saveBaseline(): Int = synchronized(lock) {
        val json = JSONObject()
        var count = 0
        for (signal in live.values) {
            if (signal.source == SignalSource.CELL) continue
            json.put(signal.id, signal.rssi)
            count++
        }
        baseline.clear()
        for (signal in live.values) {
            if (signal.source == SignalSource.CELL) continue
            baseline[signal.id] = signal.rssi
        }
        baselineSavedAt = System.currentTimeMillis()
        try {
            prefs.edit()
                .putString(KEY_BASELINE, json.toString())
                .putLong(KEY_SAVED_AT, baselineSavedAt)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "could not persist baseline", e)
        }
        count
    }

    /** Forget one signal so it stops showing up as new ("이 신호는 무시"). */
    fun addToBaseline(id: String, rssi: Int) {
        synchronized(lock) {
            baseline[id] = rssi
            persistLocked()
        }
    }

    fun clearBaseline() {
        synchronized(lock) {
            baseline.clear()
            baselineSavedAt = 0L
            prefs.edit().remove(KEY_BASELINE).remove(KEY_SAVED_AT).apply()
        }
    }

    private fun loadBaseline() {
        baselineSavedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val raw = prefs.getString(KEY_BASELINE, null) ?: return
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                baseline[key] = json.optInt(key, -100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "baseline unreadable, starting fresh", e)
            baseline.clear()
        }
    }

    private fun persistLocked() {
        val json = JSONObject()
        for ((key, value) in baseline) json.put(key, value)
        prefs.edit().putString(KEY_BASELINE, json.toString()).apply()
    }

    companion object {
        private const val TAG = "SignalRepository"
        private const val PREFS = "signal_radar"
        private const val KEY_BASELINE = "baseline_v1"
        private const val KEY_SAVED_AT = "baseline_saved_at"

        /** A signal that got this much stronger than in the baseline is worth a flag. */
        private const val BOOST_DB = 12
    }
}
