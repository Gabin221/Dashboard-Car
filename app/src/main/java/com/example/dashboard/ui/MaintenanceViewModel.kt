package com.example.dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.CarRepository
import com.example.dashboard.data.MaintenanceItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CarRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CarRepository(db.carDao())

        // Initialise un profil par défaut au premier lancement
        viewModelScope.launch {
            // On pourrait vérifier si ça existe avant, mais insertOrUpdate gère ça
            // Pour l'instant on laisse l'utilisateur le définir via l'UI plus tard
        }
    }

    // On combine le kilométrage actuel de la voiture AVEC la liste des items
    // pour calculer l'état de chaque pièce en temps réel.
    val maintenanceListState = combine(
        repository.maintenanceItems,
        repository.carProfile
    ) { items, profile ->
        val currentKm = profile?.totalMileage ?: 0.0

        // On renvoie une liste d'objets "UI" calculés
        items.map { item ->
            val distanceDriven = currentKm - item.lastServiceKm
            val remainingKm = item.intervalKm - distanceDriven
            val progressPercent = (distanceDriven / item.intervalKm * 100).toInt().coerceIn(0, 100)

            MaintenanceUiState(
                item = item,
                currentCarKm = currentKm,
                remainingKm = remainingKm,
                progressPercent = progressPercent,
                statusColor = when {
                    remainingKm < 0 -> 0xFFFF5252.toInt() // Rouge (Dépassé)
                    remainingKm < item.warningThreshold -> 0xFFFFAB00.toInt() // Orange (Bientôt)
                    else -> 0xFF4CAF50.toInt() // Vert (OK)
                }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addOrUpdateItem(name: String, interval: Int, lastKm: Double, warning: Int) {
        viewModelScope.launch {
            val newItem = MaintenanceItem(
                name = name,
                intervalKm = interval,
                lastServiceKm = lastKm,
                lastServiceDate = System.currentTimeMillis(),
                warningThreshold = warning
            )
            repository.saveMaintenanceItem(newItem)
        }
    }
}

// Une petite classe helper pour transporter les données déjà calculées vers l'écran
data class MaintenanceUiState(
    val item: MaintenanceItem,
    val currentCarKm: Double,
    val remainingKm: Double,
    val progressPercent: Int,
    val statusColor: Int // Code couleur ARGB
)