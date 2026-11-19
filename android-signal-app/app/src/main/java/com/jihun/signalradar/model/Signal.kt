package com.jihun.signalradar.model

/** Which radio a signal came from. */
enum class SignalSource(val label: String) {
    BLE("블루투스"),
    WIFI("와이파이"),
    CELL("기지국")
}

/**
 * One radio signal observed by the phone.
 *
 * [id] is stable for the same physical source (MAC address / cell id), so the same
 * device keeps one row while the app is running.
 */
data class Signal(
    val id: String,
    val source: SignalSource,
    val title: String,
    val vendor: String?,
    val kind: String?,
    val detail: String?,
    val address: String,
    val rssi: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val isNew: Boolean = false,
    val isBoosted: Boolean = false,
    val baselineRssi: Int? = null
)
