package com.example.dashboard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CarDao {
    // Gestion Profil
    @Query("SELECT * FROM car_profile WHERE id = 1 LIMIT 1")
    fun getProfile(): Flow<CarProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: CarProfile)

    // Gestion Entretiens
    @Query("SELECT * FROM maintenance_items")
    fun getAllMaintenanceItems(): Flow<List<MaintenanceItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMaintenanceItem(item: MaintenanceItem)

    @Update
    suspend fun updateMaintenanceItem(item: MaintenanceItem)
}