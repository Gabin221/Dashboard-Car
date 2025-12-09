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

    val savedAddresses = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addFavorite(name: String, address: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            val newFav = SavedAddress(
                name = name,
                addressStr = address,
                latitude = lat,
                longitude = lng,
                isFavorite = true
            )
            dao.insert(newFav)
        }
    }

    fun deleteFavorite(address: SavedAddress) {
        viewModelScope.launch {
            dao.delete(address)
        }
    }

    fun updateFavoriteName(item: SavedAddress, newName: String) {
        viewModelScope.launch {
            if (newName.isNotBlank()) {
                val updatedItem = item.copy(name = newName)
                dao.updateAddress(updatedItem)
            }
        }
    }
}