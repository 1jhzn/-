package com.jihun.signalradar.scan

import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource

/**
 * Reads the base stations the phone can hear: LTE / 5G NR / 3G.
 * A base station moves as you walk, so these rows are shown but kept out of the
 * "new signal" alarm.
 */
class CellScanner(
    context: Context,
    private val onSignals: (List<Signal>) -> Unit,
    private val onUnavailable: () -> Unit = {}
) {

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun poll() {
        val manager = telephony
        if (manager == null) {
            onUnavailable()
            return
        }
        val cells: List<CellInfo>? = try {
            manager.allCellInfo
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "allCellInfo failed", e)
            null
        }
        if (cells.isNullOrEmpty()) {
            onSignals(emptyList())
            return
        }
        val now = System.currentTimeMillis()
        val operator = operatorName(manager)
        onSignals(cells.mapNotNull { toSignal(it, operator, now) })
    }

    private fun operatorName(manager: TelephonyManager): String? {
        val fromNetwork = try {
            manager.networkOperatorName
        } catch (e: Exception) {
            null
        }
        if (!fromNetwork.isNullOrBlank()) return fromNetwork
        val code = try {
            manager.networkOperator
        } catch (e: Exception) {
            null
        }
        if (code != null && code.length >= 5) {
            Classifier.carrierFromMccMnc(code.substring(0, 3), code.substring(3))?.let { return it }
        }
        return null
    }

    private fun toSignal(info: CellInfo, operator: String?, now: Long): Signal? {
        return when {
            info is CellInfoLte -> lte(info, operator, now)
            info is CellInfoGsm -> gsm(info, operator, now)
            info is CellInfoWcdma -> wcdma(info, operator, now)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isNr(info) -> nr(info, operator, now)
            else -> null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isNr(info: CellInfo): Boolean = info is CellInfoNr

    private fun lte(info: CellInfoLte, operator: String?, now: Long): Signal {
        val identity = info.cellIdentity
        val strength = info.cellSignalStrength
        val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Classifier.lteBandLabel(identity.bands)
        } else {
            null
        }
        val parts = ArrayList<String>(3)
        parts.add("PCI ${identity.pci}")
        parts.add("기지국 ID ${identity.ci}")
        if (band != null) parts.add(band)
        parts.add("신호 ${strength.level}/4")
        return Signal(
            id = "cell:lte:${identity.ci}:${identity.pci}",
            source = SignalSource.CELL,
            title = if (info.isRegistered) "LTE 기지국 (지금 연결됨)" else "LTE 기지국",
            vendor = operator ?: "통신사 미확인",
            kind = "LTE 기지국",
            detail = parts.joinToString(" · "),
            address = "셀 ID ${identity.ci}",
            rssi = safeDbm(strength.dbm),
            firstSeen = now,
            lastSeen = now
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun nr(info: CellInfo, operator: String?, now: Long): Signal? {
        val nrInfo = info as? CellInfoNr ?: return null
        val identity = nrInfo.cellIdentity as? CellIdentityNr ?: return null
        val strength = nrInfo.cellSignalStrength as? CellSignalStrengthNr ?: return null
        val band = Classifier.lteBandLabel(identity.bands)
        val parts = ArrayList<String>(3)
        parts.add("PCI ${identity.pci}")
        parts.add("기지국 ID ${identity.nci}")
        if (band != null) parts.add("n$band")
        parts.add("RSRP ${strength.ssRsrp}dBm")
        return Signal(
            id = "cell:nr:${identity.nci}:${identity.pci}",
            source = SignalSource.CELL,
            title = if (nrInfo.isRegistered) "5G 기지국 (지금 연결됨)" else "5G 기지국",
            vendor = operator ?: "통신사 미확인",
            kind = "5G NR 기지국",
            detail = parts.joinToString(" · "),
            address = "셀 ID ${identity.nci}",
            rssi = safeDbm(strength.ssRsrp),
            firstSeen = now,
            lastSeen = now
        )
    }

    private fun wcdma(info: CellInfoWcdma, operator: String?, now: Long): Signal {
        val identity = info.cellIdentity
        val strength = info.cellSignalStrength
        return Signal(
            id = "cell:wcdma:${identity.cid}:${identity.psc}",
            source = SignalSource.CELL,
            title = if (info.isRegistered) "3G 기지국 (지금 연결됨)" else "3G 기지국",
            vendor = operator ?: "통신사 미확인",
            kind = "3G(WCDMA) 기지국",
            detail = "PSC ${identity.psc} · 셀 ID ${identity.cid} · 신호 ${strength.level}/4",
            address = "셀 ID ${identity.cid}",
            rssi = safeDbm(strength.dbm),
            firstSeen = now,
            lastSeen = now
        )
    }

    private fun gsm(info: CellInfoGsm, operator: String?, now: Long): Signal {
        val identity = info.cellIdentity
        val strength = info.cellSignalStrength
        return Signal(
            id = "cell:gsm:${identity.cid}:${identity.lac}",
            source = SignalSource.CELL,
            title = if (info.isRegistered) "2G 기지국 (지금 연결됨)" else "2G 기지국",
            vendor = operator ?: "통신사 미확인",
            kind = "2G(GSM) 기지국",
            detail = "LAC ${identity.lac} · 셀 ID ${identity.cid} · 신호 ${strength.level}/4",
            address = "셀 ID ${identity.cid}",
            rssi = safeDbm(strength.dbm),
            firstSeen = now,
            lastSeen = now
        )
    }

    /** CellInfo returns Integer.MAX_VALUE when a value is unavailable. */
    private fun safeDbm(value: Int): Int = if (value == Int.MAX_VALUE || value == 0) -120 else value

    companion object {
        private const val TAG = "CellScanner"
    }
}
