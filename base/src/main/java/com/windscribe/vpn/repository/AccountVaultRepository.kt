/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.repository

import com.windscribe.vpn.api.response.UserLoginResponse
import com.windscribe.vpn.api.response.UserSessionResponse
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AccountVaultRepository {
    val activeAccount: StateFlow<AccountEntity?>
    val allAccounts: Flow<List<AccountEntity>>

    suspend fun addOrUpdateAccount(
        loginResponse: UserLoginResponse,
        sessionResponse: UserSessionResponse,
    ): Long

    suspend fun addOrUpdateAccount(
        sessionResponse: UserSessionResponse,
        sessionAuthHash: String,
    ): Long

    suspend fun saveCurrentSessionToVault(): Long?

    suspend fun switchToAccount(accountId: Long): Boolean

    suspend fun removeAccount(accountId: Long)

    suspend fun refreshCurrentAccountTraffic(): Result<UserSessionResponse>

    suspend fun checkAndAutoSwitch(): Boolean

    companion object {
        const val AUTO_SWITCH_THRESHOLD_BYTES = 500L * 1024L * 1024L // 500 MB
    }
}
