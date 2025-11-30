package com.example.dashboard.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedAddressDao {
    // Tri : Favoris d'abord, puis par nom
    @Query("SELECT * FROM saved_addresses ORDER BY isFavorite DESC, name ASC")
    fun getAll(): Flow<List<SavedAddress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(address: SavedAddress)

    @Delete
    suspend fun delete(address: SavedAddress)

    @Query("SELECT * FROM saved_addresses WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SavedAddress?
}