package fr.alerteresidents.desktop.data

import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.store.ConfigStore
import fr.alerteresidents.util.PhotoStore

/** Accès aux fiches pour la synchronisation automatique entre appareils (stockage fichier Windows). */
class DesktopConfigStore(private val store: DesktopStore) : ConfigStore {
    override suspend fun residents(): List<Resident> = store.residents.value
    override suspend fun insertResident(resident: Resident): Long = store.insertResident(resident)
    override suspend fun updateProfile(profile: ResidentProfile) = store.updateProfile(profile)
    override suspend fun setResidentSyncId(id: Long, syncId: String) = store.setResidentSyncId(id, syncId)

    override suspend fun removeResident(id: Long) {
        val photo = store.residents.value.find { it.id == id }?.photoUri
        store.deleteResident(id)
        PhotoStore.delete(photo)
    }

    override suspend fun accounts(): List<WeenectAccount> = store.accounts.value
    override suspend fun insertAccount(account: WeenectAccount): Long = store.insert(account)
    override suspend fun updateAccount(account: WeenectAccount) = store.update(account)

    override suspend fun removeAccount(account: WeenectAccount) {
        store.detachAccount(account.id)
        store.delete(account)
    }

    override suspend fun zone(): FacilityZone = store.zone.value
    override suspend fun saveZone(zone: FacilityZone) = store.saveZone(zone)
}
