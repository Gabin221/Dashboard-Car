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

    fun deleteFavorite(address: SavedAddress) {
        viewModelScope.launch {
            dao.delete(address)
        }
    }

    // Fonction helper pour trouver Domicile/Travail
    suspend fun getAddressByName(name: String): SavedAddress? {
        // Note: Il faut ajouter cette Query dans le DAO : @Query("SELECT * FROM saved_addresses WHERE name = :name LIMIT 1")
        return dao.getByName(name) // Supposons que tu ajoutes cette méthode au DAO
    }

    suspend fun updateAddress(address: SavedAddress) {
        dao.updateAddress(address)
    }

    // Fonction pour sauvegarder/mettre à jour (utile pour la création et la modification)
    suspend fun saveAddress(address: SavedAddress) {
        dao.insert(address) // Si l'adresse a un ID, la fonction insert va la remplacer.
    }

    fun updateFavoriteName(item: SavedAddress, newName: String) {
        viewModelScope.launch {
            if (newName.isNotBlank()) {
                // Crée une nouvelle instance avec le nouveau nom
                val updatedItem = item.copy(name = newName)
                dao.updateAddress(updatedItem)
                // Note: Si vous n'avez pas de fonction updateAddress dans le repository,
                // utilisez repository.saveAddress(updatedItem) si saveAddress est configuré en REPLACE.
            }
        }
    }

//    fun updateFavoriteName(oldItem: SavedAddress, newName: String) {
//        viewModelScope.launch {
//            if (newName.isNotBlank() && newName != oldItem.name) {
//                // Crée une copie de l'objet avec le nouveau nom
//                val updatedItem = oldItem.copy(name = newName)
//
//                repository.updateSavedAddress(updatedItem)
//            }
//        }
//    }
}