package com.warehouse.stockchecker.ui.sessions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockchecker.databinding.ItemSessionBinding
import com.warehouse.stockchecker.session.SessionIndexEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionsAdapter(
    private val onOpen: (SessionIndexEntry) -> Unit,
    private val onDelete: (SessionIndexEntry) -> Unit
) : ListAdapter<SessionIndexEntry, SessionsAdapter.VH>(DIFF) {

    class VH(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SessionIndexEntry, onOpen: (SessionIndexEntry) -> Unit, onDelete: (SessionIndexEntry) -> Unit) {
            binding.name.text = item.displayName
            binding.subtitle.text = String.format(
                Locale.US,
                "%d anchors · %d ms · %s · %s",
                item.anchorCount,
                item.inferenceTimeMs,
                DATE_FORMAT.format(Date(item.createdAtMillis)),
                item.relativePath
            )
            binding.root.setOnClickListener { onOpen(item) }
            binding.menuButton.setOnClickListener { onDelete(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemSessionBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onOpen, onDelete)
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        private val DIFF = object : DiffUtil.ItemCallback<SessionIndexEntry>() {
            override fun areItemsTheSame(a: SessionIndexEntry, b: SessionIndexEntry) = a.id == b.id
            override fun areContentsTheSame(a: SessionIndexEntry, b: SessionIndexEntry) = a == b
        }
    }
}
