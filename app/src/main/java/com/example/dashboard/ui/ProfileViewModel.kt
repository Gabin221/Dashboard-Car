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

    fun saveProfile(model: String, km: Double, fuel: String) {
        viewModelScope.launch {
            val profile = CarProfile(id = 1, totalMileage = km, carModel = model, fuelType = fuel)
            repository.saveProfile(profile)
        }
    }

    fun addDistanceToProfile(kmTraveled: Double) {
        viewModelScope.launch {
            val current = repository.carProfile.firstOrNull() ?: CarProfile(totalMileage = 0.0)
            val newKm = current.totalMileage + kmTraveled
            repository.saveProfile(current.copy(totalMileage = newKm))
        }
    }
}