package com.example.dashboard.ui

import android.app.Application
import android.util.Log
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

    fun saveItem(id: Int, name: String, interval: Int, lastKm: Double) {
        viewModelScope.launch {
            val item = MaintenanceItem(
                id = id, // Si 0 -> Insert, Sinon -> Update
                name = name,
                intervalKm = interval,
                lastServiceKm = lastKm,
                lastServiceDate = System.currentTimeMillis()
            )
            repository.saveMaintenanceItem(item)
        }
    }

    // Supprimer un item
    fun deleteItem(item: MaintenanceItem) {
        viewModelScope.launch {
            repository.deleteMaintenanceItem(item) // Il faudra ajouter ça au Repository/DAO
        }
    }

    // Générer le CSV pour l'export
    fun exportData(context: android.content.Context) {
        viewModelScope.launch {
            val items = repository.maintenanceItems.first()
            val sb = StringBuilder()
            sb.append("Nom,Intervalle,Dernier KM,Derniere Date\n")

            items.forEach {
                sb.append("${it.name},${it.intervalKm},${it.lastServiceKm},${java.util.Date(it.lastServiceDate)}\n")
            }

            // Création de l'intent de partage
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Export Entretien 206+")
                putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
            }

            // Lancer le partage (Gmail, Drive, WhatsApp...)
            context.startActivity(android.content.Intent.createChooser(intent, "Exporter via..."))
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