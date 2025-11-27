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
        repository = CarRepository(db.carDao())
    }

    // Récupérer le profil actuel (one-shot)
    suspend fun getCurrentProfile(): CarProfile? {
        return repository.carProfile.firstOrNull()
    }

    // Sauvegarder
    fun saveProfile(model: String, km: Double, fuel: String) {
        viewModelScope.launch {
            val profile = CarProfile(id = 1, totalMileage = km, carModel = model, fuelType = fuel)
            repository.updateCurrentMileage(km) // Met à jour tout le profil en fait
            // Note: updateCurrentMileage dans repository ne mettait à jour que le KM ?
            // Vérifions le Repository : il faisait "profile = CarProfile(...)".
            // Donc il écrasait tout. Pour bien faire, il faudrait séparer ou passer l'objet complet.
            // MODIFICATION RAPIDE: Utilise directement le DAO ici pour être sûr ou adapte le repository.
            // Pour faire simple ici :
            repository.insertOrUpdateProfileFull(profile)
        }
    }
}