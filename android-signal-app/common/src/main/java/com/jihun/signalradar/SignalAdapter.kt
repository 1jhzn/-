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

class SignalAdapter(
    private val onClick: (Signal) -> Unit
) : RecyclerView.Adapter<SignalAdapter.Holder>() {

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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_signal, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val icon: ImageView = view.findViewById(R.id.itemIcon)
        private val title: TextView = view.findViewById(R.id.itemTitle)
        private val vendor: TextView = view.findViewById(R.id.itemVendor)
        private val detail: TextView = view.findViewById(R.id.itemDetail)
        private val address: TextView = view.findViewById(R.id.itemAddress)
        private val rssi: TextView = view.findViewById(R.id.itemRssi)
        private val distance: TextView = view.findViewById(R.id.itemDistance)
        private val bar: View = view.findViewById(R.id.itemBarFill)
        private val badgeNew: TextView = view.findViewById(R.id.itemBadgeNew)
        private val badgeBoost: TextView = view.findViewById(R.id.itemBadgeBoost)
        private val badgeMine: TextView = view.findViewById(R.id.itemBadgeMine)
        private val broadcast: TextView = view.findViewById(R.id.itemBroadcast)

        init {
            view.setOnClickListener { onPositionClicked() }
        }

        private fun onPositionClicked() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            onClick(items[position])
        }

        fun bind(signal: Signal) {
            val context = itemView.context

            title.text = signal.title
            vendor.text = signal.vendor ?: unknownVendor(signal)
            detail.text = signal.detail ?: signal.source.label
            address.text = signal.source.label + " · " + signal.address

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

            badgeNew.visibility = if (signal.isNew) View.VISIBLE else View.GONE
            badgeBoost.visibility = if (signal.isBoosted) View.VISIBLE else View.GONE

            // "Is this one of mine?" — the pairing/connection list answers it, so it is
            // worth a badge of its own rather than a line buried in the detail screen.
            val mine = DeviceName.badgeText(signal.isConnected, signal.isBonded)
            if (signal.source == SignalSource.BLE && mine != null) {
                badgeMine.text = mine
                badgeMine.visibility = View.VISIBLE
            } else {
                badgeMine.visibility = View.GONE
            }

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
                broadcast.text = nameLine
                broadcast.visibility = View.VISIBLE
            } else {
                broadcast.visibility = View.GONE
            }

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

            val distanceLabel = Classifier.distanceLabel(
                signal.smoothedRssi ?: signal.rssi.toFloat(),
                signal.source,
                signal.txPower,
                signal.frequencyMhz
            )
            if (distanceLabel != null) {
                distance.text = distanceLabel
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
