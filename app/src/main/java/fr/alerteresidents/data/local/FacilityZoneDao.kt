package fr.alerteresidents.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.store.ZoneStore
import kotlinx.coroutines.flow.Flow

@Dao
interface FacilityZoneDao : ZoneStore {
    @Query("SELECT * FROM facility_zone WHERE id = 1 LIMIT 1")
    fun getFacilityZone(): Flow<FacilityZone?>

    @Query("SELECT * FROM facility_zone WHERE id = 1 LIMIT 1")
    override suspend fun getFacilityZoneOnce(): FacilityZone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(zone: FacilityZone)

    @Update
    suspend fun update(zone: FacilityZone)
}
