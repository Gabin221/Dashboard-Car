package com.example.dashboard.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

// 1. PROFIL VOITURE
@Entity(tableName = "car_profile")
data class CarProfile(
    @PrimaryKey val id: Int = 1, // Toujours ID 1 car une seule voiture
    val totalMileage: Double = 0.0,
    val carModel: String = "Peugeot 206+",
    val fuelType: String = "Essence"
)

// 2. ITEMS D'ENTRETIEN (Mise à jour avec Mois et Historique)
@Entity(tableName = "maintenance_items")
data class MaintenanceItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val intervalKm: Int,
    val intervalMonths: Int = 0, // Nouveau champ (par défaut 0 pour éviter crashs migration)
    val lastServiceKm: Double,
    val lastServiceDate: Long,
    val warningThreshold: Int = 2000
)

// 4. LOGS HISTORIQUE (Nouveau)
@Entity(
    tableName = "maintenance_logs",
    foreignKeys = [ForeignKey(
        entity = MaintenanceItem::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class MaintenanceLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val itemId: Int,
    val dateDone: Long,
    val kmDone: Double,
    val cost: Double = 0.0,
    val comment: String = ""
)

data class MaintenanceUiState(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val item: MaintenanceItem,
    val currentCarKm: Double,
    val remainingKm: Double,
    val progressPercent: Int,
    val statusColor: Int
)
