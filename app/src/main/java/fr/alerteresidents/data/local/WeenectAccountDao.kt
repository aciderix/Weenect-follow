package fr.alerteresidents.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.store.AccountStore
import kotlinx.coroutines.flow.Flow

@Dao
interface WeenectAccountDao : AccountStore {
    @Query("SELECT * FROM weenect_accounts ORDER BY label ASC")
    fun getAll(): Flow<List<WeenectAccount>>

    @Query("SELECT * FROM weenect_accounts ORDER BY label ASC")
    suspend fun getAllOnce(): List<WeenectAccount>

    @Query("SELECT * FROM weenect_accounts WHERE id = :id")
    override suspend fun getById(id: Long): WeenectAccount?

    @Query("SELECT * FROM weenect_accounts WHERE username = :username LIMIT 1")
    override suspend fun findByUsername(username: String): WeenectAccount?

    @Insert
    override suspend fun insert(account: WeenectAccount): Long

    @Update
    override suspend fun update(account: WeenectAccount)

    @Delete
    override suspend fun delete(account: WeenectAccount)
}
