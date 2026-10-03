package fr.alerteresidents.data.store

import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.ResidentTracking
import fr.alerteresidents.data.model.WeenectAccount

/*
 * Accès aux données utilisés par le moteur de surveillance. Implémentés par les DAO Room
 * sur Android et par un stockage fichier sur Windows.
 */

interface ResidentStore {
    suspend fun getResidentById(id: Long): Resident?
    suspend fun getAllResidentsOnce(): List<Resident>
    suspend fun updateTracking(tracking: ResidentTracking)
    suspend fun attachAccount(id: Long, accountId: Long)
    suspend fun detachAccount(accountId: Long)
}

interface ZoneStore {
    suspend fun getFacilityZoneOnce(): FacilityZone?
}

interface AlertStore {
    suspend fun insertAlert(alert: AlertEvent): Long
    suspend fun acknowledgeForResident(residentId: Long, type: String, staffName: String, at: Long)
}

interface AccountStore {
    suspend fun getById(id: Long): WeenectAccount?
    suspend fun findByUsername(username: String): WeenectAccount?
    suspend fun insert(account: WeenectAccount): Long
    suspend fun update(account: WeenectAccount)
    suspend fun delete(account: WeenectAccount)
}

/** Fiches, comptes et zone : lus et écrits par la synchronisation automatique entre appareils. */
interface ConfigStore {
    suspend fun residents(): List<Resident>
    suspend fun insertResident(resident: Resident): Long
    suspend fun updateProfile(profile: ResidentProfile)
    suspend fun setResidentSyncId(id: Long, syncId: String)
    suspend fun removeResident(id: Long)

    suspend fun accounts(): List<WeenectAccount>
    suspend fun insertAccount(account: WeenectAccount): Long
    suspend fun updateAccount(account: WeenectAccount)
    /** Supprime le compte et détache les résidents qui l'utilisaient. */
    suspend fun removeAccount(account: WeenectAccount)

    suspend fun zone(): FacilityZone
    suspend fun saveZone(zone: FacilityZone)
}
