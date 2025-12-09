package com.example.dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.CarProfile
import com.example.dashboard.data.CarRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CarRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = CarRepository(db.carDao(), db.savedAddressDao())
    }

    suspend fun getCurrentProfile(): CarProfile? {
        return repository.carProfile.firstOrNull()
    }

    fun saveProfile(model: String, km: Double, fuel: String, plate: String, link: String) {
        viewModelScope.launch {
            val profile = CarProfile(
                id = 1,
                totalMileage = km,
                carModel = model,
                fuelType = fuel,
                licensePlate = plate,
                histovecLink = link
            )
            repository.saveProfile(profile)
        }
    }

    fun addDistanceToProfile(kmTraveled: Double, context: android.content.Context) { // Ajoute le contexte
        viewModelScope.launch {
            // 1. Mise à jour KM (Ton code existant)
            val currentProfile = repository.carProfile.firstOrNull() ?: return@launch
            val newTotal = currentProfile.totalMileage + kmTraveled
            repository.saveProfile(currentProfile.copy(totalMileage = newTotal))

            // 2. VÉRIFICATION DES NOTIFICATIONS
            val items = repository.maintenanceItems.firstOrNull() ?: return@launch

            items.forEach { item ->
                val driven = newTotal - item.lastServiceKm
                val remaining = item.intervalKm - driven

                // Logique de déclenchement :
                // Si on est EN DESSOUS du seuil d'alerte (Orange)
                // ET qu'on n'était pas déjà en alerte juste avant (pour ne pas spammer tous les mètres)
                // -> Pour simplifier ici, on notifie si on passe un "cap" rond (ex: tous les 100km quand on est dans le rouge)
                // OU BIEN : On notifie une seule fois quand remaining < warningThreshold

                // Méthode simple : Si on rentre dans la zone orange (avec une petite tolérance pour ne le dire qu'une fois)
                // On considère qu'on vient de franchir le seuil si (remaining + kmTraveled) était > seuil mais maintenant < seuil

                if (remaining < item.warningThreshold && (remaining + kmTraveled) >= item.warningThreshold) {
                    com.example.dashboard.utils.NotificationHelper.sendWarningNotification(context, item.name, remaining.toInt())
                }
            }
        }
    }
}