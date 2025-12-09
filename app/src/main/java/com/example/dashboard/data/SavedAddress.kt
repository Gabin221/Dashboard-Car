package com.example.dashboard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_addresses")
data class SavedAddress(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val addressStr: String,
    val latitude: Double,
    val longitude: Double,
    val isFavorite: Boolean = false
)