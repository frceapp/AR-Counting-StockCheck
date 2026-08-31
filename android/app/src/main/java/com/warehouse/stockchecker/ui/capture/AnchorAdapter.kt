package com.warehouse.stockchecker.ui.capture

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockchecker.databinding.ItemAnchorBinding
import com.warehouse.stockchecker.tracking.WorldTrackedDetection
import com.warehouse.stockchecker.ui.ClassColors

/**
 * Renders the anchored-parts list as a compact, dense feed of (label, id, score) rows.
 *
 * `ListAdapter` so a state change that only reorders or inserts one row does not rebind the
 * whole list.
 */
class AnchorAdapter :
    ListAdapter<WorldTrackedDetection, AnchorAdapter.VH>(DIFF) {

    class VH(val binding: ItemAnchorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorldTrackedDetection) {
            binding.colorDot.setBackgroundColor(ClassColors.forClass(item.detection.classId))
            binding.label.text = item.detection.displayText
            binding.subtitle.text = String.format(
                java.util.Locale.US,
                "%d%% confidence · %d hits",
                (item.detection.score * 100).toInt(),
                item.hits
            )
            binding.idChip.text = String.format(java.util.Locale.US, "#%02d", item.trackId)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemAnchorBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WorldTrackedDetection>() {
            override fun areItemsTheSame(a: WorldTrackedDetection, b: WorldTrackedDetection) =
                a.trackId == b.trackId

            override fun areContentsTheSame(a: WorldTrackedDetection, b: WorldTrackedDetection) =
                a == b
        }

        @Suppress("unused")
        private val FOREGROUND = Color.WHITE
    }
}
