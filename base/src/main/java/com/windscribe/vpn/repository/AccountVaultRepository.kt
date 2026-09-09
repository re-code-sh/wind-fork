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

    suspend fun switchToAccount(accountId: Long): Boolean

    suspend fun removeAccount(accountId: Long)

    suspend fun refreshCurrentAccountTraffic(): Result<UserSessionResponse>
}
