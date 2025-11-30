package com.example.dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.CarRepository
import com.example.dashboard.data.MaintenanceItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.dashboard.data.MaintenanceUiState

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CarRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CarRepository(db.carDao())
    }

    val maintenanceListState = combine(
        repository.maintenanceItems,
        repository.carProfile
    ) { items, profile ->
        val currentKm = profile?.totalMileage ?: 0.0

        items.map { item ->
            val distanceDriven = currentKm - item.lastServiceKm
            val remainingKm = item.intervalKm - distanceDriven

            val progressPercent = if (item.intervalKm > 0) {
                (distanceDriven / item.intervalKm * 100).toInt().coerceIn(0, 100)
            } else { 0 }

            MaintenanceUiState(
                item = item,
                currentCarKm = currentKm,
                remainingKm = remainingKm,
                progressPercent = progressPercent,
                statusColor = when {
                    remainingKm < 0 -> 0xFFFF5252.toInt()
                    remainingKm < item.warningThreshold -> 0xFFFFAB00.toInt()
                    else -> 0xFF4CAF50.toInt()
                }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Mise à jour pour inclure les Mois
    fun saveItem(id: Int, name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double) {
        viewModelScope.launch {
            val item = MaintenanceItem(
                id = id,
                name = name,
                intervalKm = intervalKm,
                intervalMonths = intervalMonths,
                lastServiceKm = lastKm,
                lastServiceDate = System.currentTimeMillis()
            )
            repository.saveMaintenanceItem(item)
        }
    }

    fun deleteItem(item: MaintenanceItem) {
        viewModelScope.launch { repository.deleteMaintenanceItem(item) }
    }

    // Tes fonctions d'export/import (inchangées sur le principe, mais vérifie les propriétés)
    fun exportData(context: android.content.Context) { /* ... Ton code d'export ... */ }
}