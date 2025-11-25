package com.example.dashboard.data

import kotlinx.coroutines.flow.Flow

class CarRepository(private val carDao: CarDao) {

    // Récupérer le profil (et donc le KM total actuel de la voiture)
    val carProfile: Flow<CarProfile?> = carDao.getProfile()

    // Récupérer la liste des entretiens
    val maintenanceItems: Flow<List<MaintenanceItem>> = carDao.getAllMaintenanceItems()

    // Mettre à jour le KM total (sera appelé par le Dashboard plus tard)
    suspend fun updateCurrentMileage(newMileage: Double) {
        // On récupère le profil existant ou on en crée un par défaut
        val profile = CarProfile(id = 1, totalMileage = newMileage)
        carDao.insertOrUpdateProfile(profile)
    }

    // Ajouter ou modifier un entretien
    suspend fun saveMaintenanceItem(item: MaintenanceItem) {
        if (item.id == 0) {
            carDao.addMaintenanceItem(item)
        } else {
            carDao.updateMaintenanceItem(item)
        }
    }

    // Initialiser un profil vide si inexistant (pour éviter les crashs au premier lancement)
    suspend fun initProfileIfNeeded() {
        val profile = CarProfile(id = 1, totalMileage = 110000.0, carModel = "Peugeot 206+")
        carDao.insertOrUpdateProfile(profile)
    }
}