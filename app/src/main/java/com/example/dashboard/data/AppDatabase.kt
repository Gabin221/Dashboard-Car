package com.example.dashboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CarProfile::class, MaintenanceItem::class, SavedAddress::class, MaintenanceLog::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun carDao(): CarDao
    abstract fun savedAddressDao(): SavedAddressDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "car_dash_db"
                )
                    .fallbackToDestructiveMigration() // Important pour éviter le crash au changement de version
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}