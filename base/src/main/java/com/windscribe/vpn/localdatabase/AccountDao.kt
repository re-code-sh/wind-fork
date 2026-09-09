/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.localdatabase

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM account_vault WHERE id = :id")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Query("SELECT * FROM account_vault WHERE username = :username COLLATE NOCASE LIMIT 1")
    suspend fun getAccountByUsername(username: String): AccountEntity?

    @Query("SELECT * FROM account_vault WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): AccountEntity?

    @Query("SELECT * FROM account_vault ORDER BY id ASC")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account_vault ORDER BY id ASC")
    suspend fun getAllAccountsSync(): List<AccountEntity>

    @Query("SELECT * FROM account_vault WHERE dataLeft >= :threshold AND sessionStatus = 'VALID' ORDER BY dataLeft DESC")
    suspend fun getAccountsWithDataAbove(threshold: Long): List<AccountEntity>

    @Query("UPDATE account_vault SET isActive = 0")
    suspend fun clearAllActiveAccounts()

    @Query("UPDATE account_vault SET isActive = 1 WHERE id = :id")
    suspend fun setAccountActiveById(id: Long)

    @Transaction
    suspend fun setActiveAccount(id: Long) {
        clearAllActiveAccounts()
        setAccountActiveById(id)
    }

    @Transaction
    suspend fun insertOrUpdate(account: AccountEntity): Long {
        val existing =
            if (account.id > 0L) {
                getAccountById(account.id)
            } else {
                getAccountByUsername(account.username)
            }
        return if (existing != null) {
            val toUpdate = account.copy(id = existing.id)
            insert(toUpdate)
            existing.id
        } else {
            insert(account)
        }
    }

    @Query("DELETE FROM account_vault WHERE id = :id")
    suspend fun deleteAccountById(id: Long)

    @Query("UPDATE account_vault SET trafficUsed = :dataUsed, trafficMax = :dataMax, dataLeft = :dataLeft WHERE id = :id")
    suspend fun updateTraffic(
        id: Long,
        dataUsed: Long,
        dataMax: Long,
        dataLeft: Long,
    )
}
