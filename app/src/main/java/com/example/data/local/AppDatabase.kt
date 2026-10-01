package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AlertEvent
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Resident::class, FacilityZone::class, AlertEvent::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun residentDao(): ResidentDao
    abstract fun facilityZoneDao(): FacilityZoneDao
    abstract fun alertEventDao(): AlertEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "securi_resident_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getInstance(context)
                                database.facilityZoneDao().insertOrUpdate(
                                    FacilityZone(
                                        id = 1,
                                        name = "MAS l'Épeau (Bouguenais)",
                                        address = "1 rue Urbain le Verrier, 44340 Bouguenais",
                                        centerLatitude = 47.1787,
                                        centerLongitude = -1.6192,
                                        radiusMeters = 150.0,
                                        isZoneActive = true,
                                        zoneType = "CIRCLE",
                                        polygonPointsJson = "",
                                        soundAlertsEnabled = true,
                                        vibrateAlertsEnabled = true,
                                        refreshIntervalSeconds = 15
                                    )
                                )
                            }
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
