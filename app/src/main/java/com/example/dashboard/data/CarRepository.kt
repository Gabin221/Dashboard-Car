package com.example.dashboard.data

import kotlinx.coroutines.flow.Flow

class CarRepository(private val carDao: CarDao) {

    val carProfile: Flow<CarProfile?> = carDao.getProfile()
    val maintenanceItems: Flow<List<MaintenanceItem>> = carDao.getAllMaintenanceItems()

    suspend fun saveProfile(profile: CarProfile) {
        carDao.insertOrUpdateProfile(profile)
    }

    suspend fun saveMaintenanceItem(item: MaintenanceItem): Long {
        return if (item.id == 0) {
            carDao.addMaintenanceItem(item)
        } else {
            carDao.updateMaintenanceItem(item)
            item.id.toLong()
        }
    }

    suspend fun deleteMaintenanceItem(item: MaintenanceItem) {
        carDao.deleteMaintenanceItem(item)
    }

    suspend fun deleteAllItems() {
        carDao.deleteAllItems()
    }

    suspend fun getLogsSync(itemId: Int): List<MaintenanceLog> = carDao.getLogsForItemSync(itemId)

    suspend fun insertLog(log: MaintenanceLog) {
        carDao.insertLog(log)
    }
}