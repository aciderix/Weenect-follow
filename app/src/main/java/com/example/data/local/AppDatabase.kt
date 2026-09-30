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
    version = 1,
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
                            // Populate initial facility zone & default sample resident
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getInstance(context)
                                database.facilityZoneDao().insertOrUpdate(
                                    FacilityZone(
                                        id = 1,
                                        name = "MAS l'Épeau",
                                        centerLatitude = 48.856614,
                                        centerLongitude = 2.3522219,
                                        radiusMeters = 150.0,
                                        isZoneActive = true,
                                        soundAlertsEnabled = true,
                                        vibrateAlertsEnabled = true,
                                        refreshIntervalSeconds = 15
                                    )
                                )
                                database.residentDao().insertResident(
                                    Resident(
                                        name = "Michel Dupont",
                                        roomNumber = "Chambre 12 - RDC",
                                        avatarColorHex = "#1E88E5",
                                        weenectUsername = "famille.dupont@email.com",
                                        trackerId = 104281,
                                        trackerName = "Balise Weenect Michel",
                                        lastLatitude = 48.856800,
                                        lastLongitude = 2.352400,
                                        lastBattery = 92,
                                        lastSpeed = 0.8,
                                        lastUpdatedTime = System.currentTimeMillis() - 45000,
                                        isInZone = true,
                                        distanceFromCenterMeters = 24.5,
                                        emergencyContact = "Poste Soins: 01 44 20 00 12",
                                        notes = "Résident à mobilité réduite, porteur de la balise en pendentif.",
                                        isTrackingActive = true
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
