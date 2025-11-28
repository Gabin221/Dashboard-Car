package com.example.dashboard.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_addresses")
data class SavedAddress(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,       // Ex: "Maison", "Boulot"
    val addressStr: String, // Ex: "10 rue de la Paix, Paris"
    val latitude: Double,
    val longitude: Double,
    val isFavorite: Boolean = false // Pour le tri
)