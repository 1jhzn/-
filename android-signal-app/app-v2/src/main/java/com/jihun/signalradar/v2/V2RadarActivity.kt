package com.jihun.signalradar.v2

import android.app.Dialog
import android.view.View
import android.widget.TextView
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalAdapter
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.nearby.NearbyRadarActivity

/**
 * 전파 감지기 v2 — the same 두 탭 app (📏 거리 + 📡 내 주변 10m 레이더) in its own package,
 * `com.jihun.signalradar.v2`, so it installs next to the existing apps and leaves them alone.
 *
 * Everything that reads radios, keeps the signal list, draws the radar and finds directions is
 * inherited; this class only picks the v2 screens and wording:
 *
 *  - `activity_v2.xml` / `content_v2.xml` / `page_v2.xml` — the empty-state text sits at the
 *    top of the list area instead of the bottom, and the filter chips are laid out on two
 *    rows so no chip is cut off at 360dp.
 *  - `item_signal_v2.xml` + `RowStyle.SUMMARY` — one-line title, one summary line
 *    (`블루투스 · 애플`), no MAC address in the list.
 *  - `dialog_detail_v2.xml` — the MAC address is folded behind `세부 주소 보기 ▾`.
 *  - `distanceStepLabel` — `매우 가까움` / `가까움` / `보통` / `멀어요` / `아주 멀어요` instead
 *    of `약 1.7m`, in the list, the detail card, the direction dial and the new-signal banner.
 *
 * The scan button already reads `■ 스캔 중지` while scanning and `▶ 스캔 시작` while stopped;
 * the v2 test suite pins that down for both buttons (거리 탭 and 내 주변 탭).
 */
class V2RadarActivity : NearbyRadarActivity() {

    override val contentLayout: Int = R.layout.activity_v2

    override val itemRowStyle: SignalAdapter.RowStyle = SignalAdapter.RowStyle.SUMMARY

    override val itemRowLayout: Int = R.layout.item_signal_v2

    override val detailLayoutId: Int = R.layout.dialog_detail_v2

    /** The address is not part of the body any more: it lives behind the toggle below. */
    override val showAddressInDetail: Boolean = false

    override fun distanceTextFor(signal: Signal): String? = Classifier.distanceStepLabel(
        signal.smoothedRssi ?: signal.rssi.toFloat(),
        signal.source,
        signal.txPower,
        signal.frequencyMhz
    )

    override fun directionDistanceText(signal: Signal, rssi: Float): String? =
        Classifier.distanceStepLabel(rssi, signal.source, signal.txPower, signal.frequencyMhz)

    override fun detailSubtitle(signal: Signal): String = listOfNotNull(
        signal.source.label,
        signal.vendor ?: signal.kind
    ).joinToString(" · ")

    /**
     * The detail card starts with the address folded away: a MAC address is the least
     * readable thing on the card, so it waits behind a small toggle until it is asked for.
     */
    override fun onDetailDialogReady(dialog: Dialog, signal: Signal) {
        val toggle = dialog.findViewById<TextView>(R.id.detailAddressToggle) ?: return
        val value = dialog.findViewById<TextView>(R.id.detailAddress) ?: return
        value.text = macText(signal.address)
        value.visibility = View.GONE
        toggle.text = ADDRESS_HIDDEN
        toggle.setOnClickListener {
            val expand = value.visibility != View.VISIBLE
            value.visibility = if (expand) View.VISIBLE else View.GONE
            toggle.text = if (expand) ADDRESS_SHOWN else ADDRESS_HIDDEN
        }
    }

    /**
     * While the scan is running the list is not waiting for a button press, so it says what
     * is actually happening; the old wording told the user to press a button that now reads
     * `■ 스캔 중지`.
     */
    override fun emptyMessage(): String =
        if (activeFilter == Filter.ALL && isScanning) {
            "스캔 중이에요 · 조금 기다리면 주변 신호가 여기에 나타나요"
        } else {
            super.emptyMessage()
        }

    /** `F8:1E:DF:12:34:56` with thin gaps, so six pairs of characters can be read at all. */
    private fun macText(address: String): String =
        if (MAC_PATTERN.matches(address.uppercase())) address.uppercase() else address

    companion object {
        internal const val ADDRESS_HIDDEN = "세부 주소 보기 ▾"
        internal const val ADDRESS_SHOWN = "세부 주소 접기 ▴"

        private val MAC_PATTERN = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
