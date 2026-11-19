package com.jihun.signalradar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Keeps the live picture of everything the phone hears right now, and the saved
 * baseline ("what my room normally looks like") used to spot new arrivals.
 */
class SignalRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val live = HashMap<String, Signal>()
    private val baseline = HashMap<String, Int>()

    /** Smoothed level per signal id, so a distance never jumps on one noisy packet. */
    private val smoothed = HashMap<String, Float>()

    /**
     * Compass bearing measured for a signal by "🧭 방향 찾기", in degrees from north.
     *
     * Only a hand-held sweep can tell a radio's direction, so this stays empty until the
     * user actually walks around the signal. The 10 m radar draws a device with a bearing
     * at its real direction and everything else at a placeholder angle, and the difference
     * is visible on purpose.
     */
    private val bearings = HashMap<String, Float>()
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
                smoothed[signal.id] = smoothLevel(signal.id, signal.rssi)
                live[signal.id] = if (previous == null) {
                    signal.copy(firstSeen = signal.lastSeen)
                } else {
                    // A name the user asked the device for stays put; a fresh packet
                    // carries no GATT information and must not wipe it.
                    signal.copy(
                        firstSeen = previous.firstSeen,
                        gattName = signal.gattName ?: previous.gattName,
                        gattManufacturer = signal.gattManufacturer ?: previous.gattManufacturer
                    )
                }
            }
        }
    }

    /**
     * Exponential moving average over the levels heard from one device.
     * A single wild packet is capped before it is mixed in, otherwise one bad reading
     * would still drag the average (and the distance) with it.
     */
    private fun smoothLevel(id: String, rssi: Int): Float {
        val previous = smoothed[id] ?: return rssi.toFloat()
        val delta = (rssi - previous).coerceIn(-OUTLIER_DB, OUTLIER_DB)
        return previous + EMA_ALPHA * delta
    }

    /** One signal by id, with the level it was last heard at. */
    fun find(id: String): Signal? = synchronized(lock) {
        live[id]?.let { withSmoothed(it) }
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
                if (now - signal.lastSeen > limit) {
                    iterator.remove()
                    smoothed.remove(signal.id)
                    bearings.remove(signal.id)
                }
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
            withSmoothed(signal).copy(
                isNew = baselineReady && base == null && signal.source != SignalSource.CELL,
                isBoosted = base != null && signal.rssi - base >= BOOST_DB,
                baselineRssi = base
            )
        }
    }

    private fun withSmoothed(signal: Signal): Signal =
        signal.copy(smoothedRssi = smoothed[signal.id] ?: signal.rssi.toFloat())

    /** Store every bluetooth / Wi-Fi signal currently heard as the new baseline. */
    fun saveBaseline(): Int = synchronized(lock) {
        val json = JSONObject()
        var count = 0
        for (signal in live.values) {
            if (signal.source == SignalSource.CELL) continue
            val level = smoothed[signal.id]?.roundToInt() ?: signal.rssi
            json.put(signal.id, level)
            count++
        }
        baseline.clear()
        for (signal in live.values) {
            if (signal.source == SignalSource.CELL) continue
            baseline[signal.id] = smoothed[signal.id]?.roundToInt() ?: signal.rssi
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

    /** Store a name read from the device's own GATT table ("기기 이름 더 읽어오기"). */
    fun updateGattNames(id: String, deviceName: String?, manufacturer: String?) {
        synchronized(lock) {
            val current = live[id] ?: return
            live[id] = current.copy(
                gattName = deviceName ?: current.gattName,
                gattManufacturer = manufacturer ?: current.gattManufacturer
            )
        }
    }

    /**
     * Remember (or forget, with null) the direction measured for one signal.
     * A reset inside the direction finder reports null, so the radar stops claiming a
     * direction the user just cleared.
     */
    fun setBearing(id: String, azimuth: Float?) {
        synchronized(lock) {
            if (azimuth == null) bearings.remove(id) else bearings[id] = azimuth
        }
    }

    /** Measured compass bearing for a signal, or null while it has not been measured. */
    fun bearing(id: String): Float? = synchronized(lock) { bearings[id] }

    /** Whether a new signal may buzz the phone (the 🔔/🔕 switch at the top). */
    fun vibrateOnNewSignal(): Boolean = prefs.getBoolean(KEY_VIBRATE, true)

    fun setVibrateOnNewSignal(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATE, enabled).apply()
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
        private const val KEY_VIBRATE = "vibrate_new_signal"

        /** A signal that got this much stronger than in the baseline is worth a flag. */
        private const val BOOST_DB = 12

        /** Weight of each new reading in the smoothed level. */
        private const val EMA_ALPHA = 0.22f

        /** A jump bigger than this in one step is most likely a bad packet. */
        private const val OUTLIER_DB = 12f
    }
}
