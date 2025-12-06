package com.example.dashboard.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

class CarRepository(private val carDao: CarDao, private val addressDao: SavedAddressDao) {

    // Flux de données
    val carProfile: Flow<CarProfile?> = carDao.getProfile()
    val maintenanceItems: Flow<List<MaintenanceItem>> = carDao.getAllMaintenanceItems()

    // Gestion Profil
    suspend fun saveProfile(profile: CarProfile) {
        carDao.insertOrUpdateProfile(profile)
    }

    suspend fun updateCurrentMileage(newMileage: Double) {
        val current = carProfile.firstOrNull() ?: CarProfile()
        // On garde les infos existantes, on change juste le kilométrage
        val updated = current.copy(totalMileage = newMileage)
        carDao.insertOrUpdateProfile(updated)
    }

    // Gestion Maintenance
    suspend fun saveMaintenanceItem(item: MaintenanceItem) {
        if (item.id == 0) {
            carDao.addMaintenanceItem(item)
        } else {
            carDao.updateMaintenanceItem(item)
        }
    }

    suspend fun deleteMaintenanceItem(item: MaintenanceItem) {
        carDao.deleteMaintenanceItem(item)
    }

    suspend fun deleteAllItems() {
        carDao.deleteAllItems()
    }


    // Gestion Logs
    fun getLogs(itemId: Int): Flow<List<MaintenanceLog>> = carDao.getLogsForItem(itemId)

    suspend fun getLogsSync(itemId: Int): List<MaintenanceLog> = carDao.getLogsForItemSync(itemId)

    suspend fun insertLog(log: MaintenanceLog) {
        carDao.insertLog(log)
    }

    suspend fun updateSavedAddress(address: SavedAddress) {
        // Tu dois injecter le SavedAddressDao dans le CarRepository si ce n'est pas déjà fait !
        // Exemple : CarRepository(private val carDao: CarDao, private val addressDao: SavedAddressDao)
        // addressDao.update(address)

        // Si tu as centralisé les deux DAOs dans un seul objet, utilise-le :
        // carDao.updateSavedAddress(address)

        // Supposons que tu as un SavedAddressDao accessible :
        // addressDao.update(address) // <-- Utilise la dépendance appropriée

        // POUR NE PAS CASSER TON CODE, je vais supposer que tu as un `savedAddressDao` accessible :
        addressDao.update(address)
    }
}