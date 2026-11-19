package com.jihun.signalradar

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.identify.DeviceName
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import kotlin.math.roundToInt

/**
 * One row per signal.
 *
 * Two row styles share the strength bar, the type icon and the badges; they differ only in
 * how much text the row carries:
 *
 *  - [RowStyle.CLASSIC] — the v1 card: title, maker, a two-line description and a
 *    `블루투스 · F8:1E:…` address line, with the raw estimate (`약 1.7m`) on the right.
 *  - [RowStyle.SUMMARY] — the v2 card: the title on one line, then **one** summary line
 *    (radio · maker) and nothing else besides the badges. 지훈's complaint was that the maker,
 *    the kind and the address were all shown on top of each other ("중복 설명"), and that the
 *    MAC address does not belong in a list. The distance is a step ("매우 가까움") rather than
 *    a decimal metre value.
 *
 * The layout is a parameter so the v2 screen can hand in `item_signal_v2.xml`; a view the
 * chosen layout does not have is simply skipped instead of crashing.
 */
class SignalAdapter(
    private val itemLayout: Int = R.layout.item_signal,
    private val rowStyle: RowStyle = RowStyle.CLASSIC,
    /** Last, so `SignalAdapter { open(it) }` keeps working exactly as before. */
    private val onClick: (Signal) -> Unit
) : RecyclerView.Adapter<SignalAdapter.Holder>() {

    enum class RowStyle { CLASSIC, SUMMARY }

    private val items = ArrayList<Signal>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Signal>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(itemLayout, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val icon: ImageView = view.findViewById(R.id.itemIcon)
        private val title: TextView = view.findViewById(R.id.itemTitle)
        private val vendor: TextView? = view.findViewById(R.id.itemVendor)
        private val detail: TextView? = view.findViewById(R.id.itemDetail)
        private val address: TextView? = view.findViewById(R.id.itemAddress)
        private val rssi: TextView = view.findViewById(R.id.itemRssi)
        private val distance: TextView = view.findViewById(R.id.itemDistance)
        private val bar: View = view.findViewById(R.id.itemBarFill)
        private val badgeNew: TextView? = view.findViewById(R.id.itemBadgeNew)
        private val badgeBoost: TextView? = view.findViewById(R.id.itemBadgeBoost)
        private val badgeMine: TextView? = view.findViewById(R.id.itemBadgeMine)
        private val broadcast: TextView? = view.findViewById(R.id.itemBroadcast)

        init {
            view.setOnClickListener { onPositionClicked() }
        }

        private fun onPositionClicked() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            onClick(items[position])
        }

        fun bind(signal: Signal) {
            title.text = signal.title
            bindTypeAndBadges(signal)
            bindStrength(signal)
            if (rowStyle == RowStyle.SUMMARY) bindSummary(signal) else bindClassic(signal)
        }

        // ---------------------------------------------------------------- classic

        private fun bindClassic(signal: Signal) {
            vendor?.text = signal.vendor ?: unknownVendor(signal)
            detail?.text = signal.detail ?: signal.source.label
            address?.text = signal.source.label + " · " + signal.address
            broadcast?.let { line ->
                // A device that names itself is the strongest ownership hint there is
                // ("지훈의 AirPods"), so the row says where the name came from instead of
                // repeating it under the title.
                val nameLine: String? = if (signal.source != SignalSource.BLE) {
                    null
                } else {
                    val broadcastName = signal.broadcastName
                    val systemName = signal.systemName
                    when {
                        broadcastName != null && broadcastName != signal.title ->
                            DeviceName.broadcastLabel(broadcastName)
                        broadcastName != null -> "📢 기기가 스스로 알려 준 이름"
                        systemName != null && systemName != signal.title ->
                            "📌 폰에 저장된 이름: $systemName"
                        systemName != null -> "📌 폰이 기억하고 있는 이름"
                        else -> null
                    }
                }
                if (nameLine != null) {
                    line.text = nameLine
                    line.visibility = View.VISIBLE
                } else {
                    line.visibility = View.GONE
                }
            }
        }

        // ---------------------------------------------------------------- summary

        /**
         * One line that says what the row is: `블루투스 · 애플`, `와이파이 · ipTIME(EFM)`.
         *
         * The maker is left out when it repeats the title or is only "제조사 미확인", and the
         * address never appears; both were the duplicated text 지훈 asked to remove.
         */
        private fun bindSummary(signal: Signal) {
            vendor?.visibility = View.GONE
            address?.visibility = View.GONE
            broadcast?.visibility = View.GONE
            detail?.text = summaryLine(signal)
        }

        private fun summaryLine(signal: Signal): String {
            val maker = signal.vendor
                ?.takeIf { it.isNotBlank() && it != signal.title && !it.contains("미확인") }
                ?: signal.kind?.takeIf { it.isNotBlank() && it != signal.title }
            return if (maker == null) signal.source.label else signal.source.label + " · " + maker
        }

        // ----------------------------------------------------------------- shared

        private fun bindTypeAndBadges(signal: Signal) {
            val context = itemView.context

            when (signal.source) {
                SignalSource.BLE -> {
                    icon.setImageResource(R.drawable.ic_ble)
                    icon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.accent)
                    )
                }
                SignalSource.WIFI -> {
                    icon.setImageResource(R.drawable.ic_wifi)
                    icon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.green)
                    )
                }
                SignalSource.CELL -> {
                    icon.setImageResource(R.drawable.ic_cell)
                    icon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.purple)
                    )
                }
            }

            badgeNew?.visibility = if (signal.isNew) View.VISIBLE else View.GONE
            badgeBoost?.visibility = if (signal.isBoosted) View.VISIBLE else View.GONE

            // "Is this one of mine?" — the pairing/connection list answers it, so it is
            // worth a badge of its own rather than a line buried in the detail screen.
            val mine = DeviceName.badgeText(signal.isConnected, signal.isBonded)
            if (badgeMine != null && signal.source == SignalSource.BLE && mine != null) {
                badgeMine.text = mine
                badgeMine.visibility = View.VISIBLE
            } else {
                badgeMine?.visibility = View.GONE
            }
        }

        private fun bindStrength(signal: Signal) {
            val context = itemView.context

            // Everything visible uses the smoothed level: a distance that jumps 3 m because
            // one packet was weak is worse than no distance at all.
            val level = signal.smoothedRssi?.roundToInt() ?: signal.rssi
            val percent = Classifier.strengthPercent(level, signal.source)
            val strengthColor = when {
                percent >= 65 -> R.color.green
                percent >= 35 -> R.color.amber
                else -> R.color.red
            }
            rssi.text = level.toString()
            rssi.setTextColor(ContextCompat.getColor(context, strengthColor))

            val trackWidth = context.resources.displayMetrics.density * 46f
            val fillWidth = (trackWidth * percent / 100f).toInt().coerceAtLeast(
                (context.resources.displayMetrics.density * 3f).toInt()
            )
            val params = bar.layoutParams
            params.width = fillWidth
            bar.layoutParams = params
            bar.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, strengthColor)
            )

            val label = if (rowStyle == RowStyle.SUMMARY) {
                Classifier.distanceStepLabel(
                    signal.smoothedRssi ?: signal.rssi.toFloat(),
                    signal.source,
                    signal.txPower,
                    signal.frequencyMhz
                )
            } else {
                Classifier.distanceLabel(
                    signal.smoothedRssi ?: signal.rssi.toFloat(),
                    signal.source,
                    signal.txPower,
                    signal.frequencyMhz
                )
            }
            if (label != null) {
                distance.text = label
                distance.setTextColor(ContextCompat.getColor(context, strengthColor))
            } else {
                // A cell's signal level says nothing about its distance.
                distance.text = "기지국"
                distance.setTextColor(ContextCompat.getColor(context, R.color.purple))
            }
        }

        private fun unknownVendor(signal: Signal): String {
            return when (signal.source) {
                SignalSource.BLE -> "제조사 미확인"
                SignalSource.WIFI -> "제조사 미확인"
                SignalSource.CELL -> "통신사 미확인"
            }
        }
    }
}
