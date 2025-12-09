package com.example.dashboard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedAddressDao {
    @Query("SELECT * FROM saved_addresses ORDER BY isFavorite DESC, name ASC")
    fun getAll(): Flow<List<SavedAddress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(address: SavedAddress)

    @Delete
    suspend fun delete(address: SavedAddress)

    @Query("SELECT * FROM saved_addresses WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SavedAddress?

    @Update
    suspend fun update(address: SavedAddress)

    @Update
    suspend fun updateAddress(address: SavedAddress)
}