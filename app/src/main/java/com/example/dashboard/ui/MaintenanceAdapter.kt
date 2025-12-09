package com.example.dashboard.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.dashboard.databinding.ItemMaintenanceBinding
import android.content.res.ColorStateList
import com.example.dashboard.data.MaintenanceUiState

class MaintenanceAdapter(
    private val onItemClicked: (MaintenanceUiState) -> Unit
) : ListAdapter<MaintenanceUiState, MaintenanceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMaintenanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onItemClicked(item) }
    }

    class ViewHolder(private val binding: ItemMaintenanceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(state: MaintenanceUiState) {
            binding.tvName.text = state.item.name

            if (state.remainingKm < 0) {
                binding.tvRemaining.text = "DÉPASSÉ de ${-state.remainingKm.toInt()} km !"
                binding.tvRemaining.setTextColor(0xFFFF5252.toInt())
            } else {
                binding.tvRemaining.text = "Reste ${state.remainingKm.toInt()} km"
                binding.tvRemaining.setTextColor(0xFFCCCCCC.toInt())
            }
            binding.tvDetails.text = "Fait à ${state.item.lastServiceKm.toInt()} km"
            binding.progressBar.progress = state.progressPercent
            binding.progressBar.progressTintList = ColorStateList.valueOf(state.statusColor)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MaintenanceUiState>() {
        override fun areItemsTheSame(oldItem: MaintenanceUiState, newItem: MaintenanceUiState) = oldItem.item.id == newItem.item.id
        override fun areContentsTheSame(oldItem: MaintenanceUiState, newItem: MaintenanceUiState) = oldItem == newItem
    }
}