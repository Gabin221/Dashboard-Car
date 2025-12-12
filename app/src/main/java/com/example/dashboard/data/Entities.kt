package com.example.dashboard.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "car_profile")
data class CarProfile(
    @PrimaryKey val id: Int = 1,
    val totalMileage: Double = 0.0,
    val carModel: String = "Peugeot 206+",
    val fuelType: String = "Essence",
    val histovecLink: String = ""
)

@Entity(tableName = "maintenance_items")
data class MaintenanceItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val intervalKm: Int,
    val intervalMonths: Int = 0,
    val lastServiceKm: Double,
    val lastServiceDate: Long,
    val warningThreshold: Int = 2000
)

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
    val comment: String = "",
    val doneByOwner: Boolean
)

data class MaintenanceUiState(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val item: MaintenanceItem,
    val currentCarKm: Double,
    val remainingKm: Double,
    val progressPercent: Int,
    val statusColor: Int
)

data class JsonRoot(
    val meta: JsonMeta?,
    val items: List<JsonItem>?
)

data class JsonMeta(
    val title: String?,
    val exportDate: String?,
    val vehicle: String?
)

data class JsonItem(
    val name: String,
    val intervalKm: Int,
    val lastServiceKm: Double,
    val logs: List<JsonLog>?
)

data class JsonLog(
    val date: String,
    val km: Double,
    val comment: String?
)