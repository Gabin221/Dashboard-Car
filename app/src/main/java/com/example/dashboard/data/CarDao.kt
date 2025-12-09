package com.example.dashboard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CarDao {
    @Query("SELECT * FROM car_profile WHERE id = 1 LIMIT 1")
    fun getProfile(): Flow<CarProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: CarProfile)

    @Query("SELECT * FROM maintenance_items")
    fun getAllMaintenanceItems(): Flow<List<MaintenanceItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMaintenanceItem(item: MaintenanceItem): Long

    @Update
    suspend fun updateMaintenanceItem(item: MaintenanceItem)

    @Delete
    suspend fun deleteMaintenanceItem(item: MaintenanceItem)

    @Query("SELECT * FROM maintenance_logs WHERE itemId = :itemId ORDER BY dateDone DESC")
    fun getLogsForItem(itemId: Int): Flow<List<MaintenanceLog>>

    @Query("SELECT * FROM maintenance_logs WHERE itemId = :itemId ORDER BY dateDone DESC")
    suspend fun getLogsForItemSync(itemId: Int): List<MaintenanceLog>

    @Insert
    suspend fun insertLog(log: MaintenanceLog)

    @Query("DELETE FROM maintenance_items")
    suspend fun deleteAllItems()
}