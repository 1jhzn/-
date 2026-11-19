package com.jihun.signalradar.v2

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.R
import com.jihun.signalradar.identify.DeviceName
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource

/**
 * One Find-My style row per signal: a coloured circular badge, the name on one line, one
 * summary line (`블루투스 · 애플`), and the distance step as a pale pill on the right.
 *
 * The shared [SignalAdapter] of the v1 apps paints a dark card and a strength meter, which
 * is the wrong language for a white sheet, so the v2 list has its own tiny adapter. The MAC
 * address is never printed here — it lives behind `세부 주소 보기 ▾` in the detail card.
 */
class V2SignalAdapter(
    private val onClick: (Signal) -> Unit
) : RecyclerView.Adapter<V2SignalAdapter.Holder>() {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_signal_v2, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val iconBox: View? = view.findViewById(R.id.itemIconBox)
        private val icon: ImageView? = view.findViewById(R.id.itemIcon)
        private val title: TextView? = view.findViewById(R.id.itemTitle)
        private val detail: TextView? = view.findViewById(R.id.itemDetail)
        private val distance: TextView? = view.findViewById(R.id.itemDistance)
        private val badgeMine: TextView? = view.findViewById(R.id.itemBadgeMine)
        private val badgeNew: TextView? = view.findViewById(R.id.itemBadgeNew)
        private val badgeBoost: TextView? = view.findViewById(R.id.itemBadgeBoost)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }

        fun bind(signal: Signal) {
            val context = itemView.context
            iconBox?.setBackgroundResource(V2Look.iconBadgeFor(signal.source))
            icon?.setImageResource(V2Look.iconFor(signal.source))
            icon?.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.v2_sheet)
            )

            title?.text = signal.title
            detail?.text = V2Look.summaryLine(signal)

            val step = V2Look.stepOf(signal)
            val style = V2Look.stepStyle(step)
            distance?.text = step ?: signal.source.label
            distance?.setBackgroundResource(style.background)
            distance?.setTextColor(ContextCompat.getColor(context, style.textColor))

            badgeNew?.visibility = if (signal.isNew) View.VISIBLE else View.GONE
            badgeBoost?.visibility = if (signal.isBoosted) View.VISIBLE else View.GONE

            val mine = if (signal.source == SignalSource.BLE) {
                DeviceName.badgeText(signal.isConnected, signal.isBonded)
            } else {
                null
            }
            if (mine != null) {
                badgeMine?.text = mine
                badgeMine?.visibility = View.VISIBLE
            } else {
                badgeMine?.visibility = View.GONE
            }
        }
    }
}
