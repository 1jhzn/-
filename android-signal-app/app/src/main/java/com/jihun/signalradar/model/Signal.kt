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
    val baselineRssi: Int? = null,
    /** Level after the smoothing filter; what distance and the strength bar read from. */
    val smoothedRssi: Float? = null,
    /** Bluetooth transmitters advertise "how strong I am at 1 m" — far better than a guess. */
    val txPower: Int? = null,
    /** Wi-Fi channel frequency, so 5/6 GHz is not compared against 2.4 GHz levels. */
    val frequencyMhz: Int? = null,
    /** The name the device itself gives out in its advertisement. */
    val broadcastName: String? = null,
    /** The name the phone has cached from pairing, when nothing was advertised. */
    val systemName: String? = null,
    /** This device is connected to the phone right now (GATT / A2DP / headset). */
    val isConnected: Boolean = false,
    /** This device is paired with the phone. */
    val isBonded: Boolean = false,
    /** Name read from the device's own GATT table, when the user asked for it. */
    val gattName: String? = null,
    /** Manufacturer name from the device's Device Information service, when read. */
    val gattManufacturer: String? = null
)
