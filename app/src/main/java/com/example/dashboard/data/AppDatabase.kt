package com.example.dashboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CarProfile::class, MaintenanceItem::class, SavedAddress::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedAddressDao(): SavedAddressDao // <--- Ajoute ça
    abstract fun carDao(): CarDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "car_dash_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}