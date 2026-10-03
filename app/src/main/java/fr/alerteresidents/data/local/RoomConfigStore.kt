package fr.alerteresidents.data.local

import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.store.ConfigStore
import fr.alerteresidents.util.PhotoStore

/** Accès aux fiches pour la synchronisation automatique entre appareils (base Room). */
class RoomConfigStore(private val db: AppDatabase) : ConfigStore {
    private val residentDao = db.residentDao()
    private val accountDao = db.weenectAccountDao()
    private val zoneDao = db.facilityZoneDao()

    override suspend fun residents(): List<Resident> = residentDao.getAllResidentsOnce()
    override suspend fun insertResident(resident: Resident): Long = residentDao.insertResident(resident)
    override suspend fun updateProfile(profile: ResidentProfile) = residentDao.updateProfile(profile)
    override suspend fun setResidentSyncId(id: Long, syncId: String) = residentDao.setSyncId(id, syncId)

    override suspend fun removeResident(id: Long) {
        val photo = residentDao.getResidentById(id)?.photoUri
        residentDao.deleteResidentById(id)
        PhotoStore.delete(photo)
    }

    override suspend fun accounts(): List<WeenectAccount> = accountDao.getAllOnce()
    override suspend fun insertAccount(account: WeenectAccount): Long = accountDao.insert(account)
    override suspend fun updateAccount(account: WeenectAccount) = accountDao.update(account)

    override suspend fun removeAccount(account: WeenectAccount) {
        residentDao.detachAccount(account.id)
        accountDao.delete(account)
    }

    override suspend fun zone(): FacilityZone = zoneDao.getFacilityZoneOnce() ?: FacilityZone()
    override suspend fun saveZone(zone: FacilityZone) = zoneDao.insertOrUpdate(zone)
}
