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

    fun addDistanceToProfile(kmTraveled: Double, context: android.content.Context) {
        viewModelScope.launch {
            val currentProfile = repository.carProfile.firstOrNull() ?: return@launch
            val newTotal = currentProfile.totalMileage + kmTraveled
            repository.saveProfile(currentProfile.copy(totalMileage = newTotal))

            val items = repository.maintenanceItems.firstOrNull() ?: return@launch

            items.forEach { item ->
                val driven = newTotal - item.lastServiceKm
                val remaining = item.intervalKm - driven

                if (remaining < item.warningThreshold && (remaining + kmTraveled) >= item.warningThreshold) {
                    com.example.dashboard.utils.NotificationHelper.sendWarningNotification(context, item.name, remaining.toInt())
                }
            }
        }
    }
}