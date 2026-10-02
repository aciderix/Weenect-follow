package fr.alerteresidents.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.ResidentTracking
import kotlinx.coroutines.flow.Flow

@Dao
interface ResidentDao {
    @Query("SELECT * FROM residents ORDER BY name ASC")
    fun getAllResidents(): Flow<List<Resident>>

    @Query("SELECT * FROM residents ORDER BY name ASC")
    suspend fun getAllResidentsOnce(): List<Resident>

    @Query("SELECT * FROM residents WHERE id = :id")
    suspend fun getResidentById(id: Long): Resident?

    @Query("SELECT * FROM residents WHERE isInZone = 0")
    fun getResidentsOutOfZone(): Flow<List<Resident>>

    @Query("SELECT * FROM residents WHERE trackerId = :trackerId AND id != :excludeId")
    suspend fun findByTracker(trackerId: Long, excludeId: Long = 0): List<Resident>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResident(resident: Resident): Long

    /** Mise à jour complète : réservée aux imports / tests. Préférer [updateProfile] ou [updateTracking]. */
    @Update
    suspend fun updateResident(resident: Resident)

    @Update(entity = Resident::class)
    suspend fun updateProfile(profile: ResidentProfile)

    @Update(entity = Resident::class)
    suspend fun updateTracking(tracking: ResidentTracking)

    @Delete
    suspend fun deleteResident(resident: Resident)

    @Query("DELETE FROM residents WHERE id = :id")
    suspend fun deleteResidentById(id: Long)

    @Query("DELETE FROM residents")
    suspend fun deleteAllResidents()

    @Query("UPDATE residents SET accountId = :accountId, weenectUsername = '', weenectPassword = '' WHERE id = :id")
    suspend fun attachAccount(id: Long, accountId: Long)

    @Query("UPDATE residents SET accountId = NULL WHERE accountId = :accountId")
    suspend fun detachAccount(accountId: Long)
}
