package com.example.dashboard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CarDao {
    // --- PROFIL ---
    @Query("SELECT * FROM car_profile WHERE id = 1 LIMIT 1")
    fun getProfile(): Flow<CarProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: CarProfile)

    // --- ENTRETIEN ---
    @Query("SELECT * FROM maintenance_items")
    fun getAllMaintenanceItems(): Flow<List<MaintenanceItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMaintenanceItem(item: MaintenanceItem)

    @Update
    suspend fun updateMaintenanceItem(item: MaintenanceItem)

    @Delete
    suspend fun deleteMaintenanceItem(item: MaintenanceItem)

    // --- LOGS ---
    @Query("SELECT * FROM maintenance_logs WHERE itemId = :itemId ORDER BY dateDone DESC")
    fun getLogsForItem(itemId: Int): Flow<List<MaintenanceLog>>

    // Version synchrone pour l'export (suspend)
    @Query("SELECT * FROM maintenance_logs WHERE itemId = :itemId ORDER BY dateDone DESC")
    suspend fun getLogsForItemSync(itemId: Int): List<MaintenanceLog>

    @Insert
    suspend fun insertLog(log: MaintenanceLog)
}