package com.example.dashboard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Table pour ton profil et kilométrage global
@Entity(tableName = "car_profile")
data class CarProfile(
    @PrimaryKey val id: Int = 1, // On aura toujours une seule ligne, l'ID 1
    var totalMileage: Double = 110000.0, // Kilométrage compteur
    var carModel: String = "Peugeot 206+",
    var fuelType: String = "Essence"
)

// Table pour les entretiens (Pneus, Vidange...)
@Entity(tableName = "maintenance_items")
data class MaintenanceItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,           // Ex: "Pneus Avant"
    var intervalKm: Int,        // Ex: 40000
    var lastServiceKm: Double,  // Ex: 119345.0
    var lastServiceDate: Long,  // Timestamp
    var warningThreshold: Int = (intervalKm*0.8).toInt() // Prévenir 3000km avant
)