package com.atombits.pocopaw

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.atombits.pocopaw.databinding.ItemExecutionEventBinding

internal class ExecutionLogAdapter : ListAdapter<ExecutionTimelineItem, ExecutionLogAdapter.ExecutionLogViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExecutionLogViewHolder {
        val binding = ItemExecutionEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ExecutionLogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExecutionLogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ExecutionLogViewHolder(
        private val binding: ItemExecutionEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExecutionTimelineItem) {
            binding.logTimeText.text = item.leadingText
            binding.logStateText.text = item.stateText
            binding.logSummaryText.text = item.summary
            binding.logDetailText.isVisible = !item.detail.isNullOrBlank()
            binding.logDetailText.text = item.detail.orEmpty()
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ExecutionTimelineItem>() {
        override fun areItemsTheSame(oldItem: ExecutionTimelineItem, newItem: ExecutionTimelineItem): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ExecutionTimelineItem, newItem: ExecutionTimelineItem): Boolean = oldItem == newItem
    }
}