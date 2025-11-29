package com.example.dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dashboard.data.AppDatabase
import com.example.dashboard.data.SavedAddress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SavedAddressViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).savedAddressDao()

    // Liste des favoris en temps réel
    val savedAddresses = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addFavorite(name: String, address: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            val newFav = SavedAddress(
                name = name,
                addressStr = address,
                latitude = lat,
                longitude = lng,
                isFavorite = true // On le met favori direct
            )
            dao.insert(newFav)
        }
    }
}