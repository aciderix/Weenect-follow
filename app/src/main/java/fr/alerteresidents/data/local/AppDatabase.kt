package fr.alerteresidents.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount

@Database(
    entities = [Resident::class, FacilityZone::class, AlertEvent::class, WeenectAccount::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun residentDao(): ResidentDao
    abstract fun facilityZoneDao(): FacilityZoneDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun weenectAccountDao(): WeenectAccountDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v3 → v4 : comptes Weenect séparés, état d'alerte, heure de fix, sorties accompagnées,
         * unités / niveau de risque, zones annexes et mode nuit. Aucune donnée n'est perdue.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "accountId INTEGER",
                    "unit TEXT NOT NULL DEFAULT ''",
                    "riskLevel INTEGER NOT NULL DEFAULT 0",
                    "lastSyncTime INTEGER",
                    "lastSyncError TEXT",
                    "lastSyncErrorAt INTEGER",
                    "accuracyMeters INTEGER",
                    "lastFixId TEXT",
                    "pendingExitFixId TEXT",
                    "pendingExitAt INTEGER",
                    "exitedAt INTEGER",
                    "alertState TEXT NOT NULL DEFAULT 'NONE'",
                    "alertHandledBy TEXT",
                    "alertHandledAt INTEGER",
                    "lastAlarmAt INTEGER",
                    "pausedUntil INTEGER",
                    "pauseReason TEXT",
                    "lowBatteryNotified INTEGER NOT NULL DEFAULT 0",
                    "offlineNotified INTEGER NOT NULL DEFAULT 0",
                    "isInDeepSleep INTEGER NOT NULL DEFAULT 0"
                ).forEach { db.execSQL("ALTER TABLE residents ADD COLUMN $it") }
                // Les résidents déjà hors zone gardent une alerte active.
                db.execSQL("UPDATE residents SET alertState = 'ACTIVE' WHERE isInZone = 0")
                // L'ancienne colonne contenait l'heure de la requête, pas celle du fix : on l'efface
                // pour ne pas afficher une fraîcheur trompeuse avant la prochaine synchro.
                db.execSQL("UPDATE residents SET lastUpdatedTime = NULL")

                listOf(
                    "acknowledgedAt INTEGER",
                    "isDrill INTEGER NOT NULL DEFAULT 0",
                    "details TEXT"
                ).forEach { db.execSQL("ALTER TABLE alert_events ADD COLUMN $it") }
                // Les retours en zone sont informatifs.
                db.execSQL("UPDATE alert_events SET isAcknowledged = 1 WHERE alertType = 'ENTER_ZONE'")

                listOf(
                    "extraZonesJson TEXT NOT NULL DEFAULT ''",
                    "nightModeEnabled INTEGER NOT NULL DEFAULT 0",
                    "nightStartHour INTEGER NOT NULL DEFAULT 21",
                    "nightEndHour INTEGER NOT NULL DEFAULT 7",
                    "nightRefreshIntervalSeconds INTEGER NOT NULL DEFAULT 10"
                ).forEach { db.execSQL("ALTER TABLE facility_zone ADD COLUMN $it") }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weenect_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT NOT NULL, `username` TEXT NOT NULL, `encryptedPassword` TEXT NOT NULL)"
                )
            }
        }

        /** v4 → v5 : identifiant de synchronisation entre appareils (Supabase). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE residents ADD COLUMN syncId TEXT")
                db.execSQL("ALTER TABLE weenect_accounts ADD COLUMN syncId TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "securi_resident_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            val z = FacilityZone()
                            db.execSQL(
                                "INSERT OR IGNORE INTO facility_zone (id, name, address, centerLatitude, centerLongitude, " +
                                    "radiusMeters, isZoneActive, zoneType, polygonPointsJson, soundAlertsEnabled, " +
                                    "vibrateAlertsEnabled, refreshIntervalSeconds, extraZonesJson, nightModeEnabled, " +
                                    "nightStartHour, nightEndHour, nightRefreshIntervalSeconds) " +
                                    "VALUES (1, ?, '', ?, ?, ?, 1, 'CIRCLE', '', 1, 1, ?, '', 0, ?, ?, ?)",
                                arrayOf<Any>(
                                    z.name, z.centerLatitude, z.centerLongitude, z.radiusMeters,
                                    z.refreshIntervalSeconds, z.nightStartHour, z.nightEndHour, z.nightRefreshIntervalSeconds
                                )
                            )
                        }
                    })
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    // Une montée de version sans migration doit faire échouer le build/les tests,
                    // jamais effacer silencieusement les résidents.
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
