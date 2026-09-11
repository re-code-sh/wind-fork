/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.repository

import com.windscribe.vpn.api.response.UserLoginResponse
import com.windscribe.vpn.api.response.UserSessionResponse
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed class BulkImportStatus {
    data class Starting(
        val username: String,
    ) : BulkImportStatus()

    data class Success(
        val username: String,
        val isPro: Boolean,
        val dataLeft: Long,
    ) : BulkImportStatus()

    data class RateLimited(
        val username: String,
        val backoffSeconds: Long,
    ) : BulkImportStatus()

    data class Failed(
        val username: String,
        val reason: String,
    ) : BulkImportStatus()
}

data class BulkImportResult(
    val totalProcessed: Int,
    val successCount: Int,
    val failedCount: Int,
    val failures: List<Pair<String, String>> = emptyList(),
)

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

    suspend fun importAccountsBulk(
        credentials: List<Pair<String, String>>,
        onProgress: (current: Int, total: Int, status: BulkImportStatus) -> Unit = { _, _, _ -> },
    ): BulkImportResult
}
