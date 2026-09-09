/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.repository

import com.google.gson.Gson
import com.windscribe.vpn.api.IApiCallManager
import com.windscribe.vpn.api.response.UserLoginResponse
import com.windscribe.vpn.api.response.UserSessionResponse
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.backend.CdLib
import com.windscribe.vpn.backend.VirtualDeviceManager
import com.windscribe.vpn.backend.VirtualDeviceProfile
import com.windscribe.vpn.localdatabase.AccountDao
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountVaultRepositoryImpl
    @Inject
    constructor(
        private val accountDao: AccountDao,
        private val virtualDeviceManager: VirtualDeviceManager,
        private val preferencesHelper: PreferencesHelper,
        private val userRepository: UserRepository,
        private val cdLib: CdLib,
        private val apiManager: IApiCallManager,
        private val scope: CoroutineScope,
    ) : AccountVaultRepository {
        private val _activeAccount = MutableStateFlow<AccountEntity?>(null)
        override val activeAccount: StateFlow<AccountEntity?> = _activeAccount.asStateFlow()
        override val allAccounts: Flow<List<AccountEntity>> = accountDao.getAllAccounts()

        init {
            scope.launch {
                accountDao.getAllAccounts().collect { accounts ->
                    _activeAccount.value = accounts.firstOrNull { it.isActive }
                }
            }
        }

        override suspend fun addOrUpdateAccount(
            loginResponse: UserLoginResponse,
            sessionResponse: UserSessionResponse,
        ): Long {
            val username =
                when {
                    !sessionResponse.userName.isNullOrBlank() -> sessionResponse.userName!!
                    loginResponse.userName.isNotBlank() && loginResponse.userName != "na" -> loginResponse.userName
                    else -> loginResponse.userName
                }

            val existing = accountDao.getAccountByUsername(username)
            val profile =
                if (existing != null && existing.virtualCuid.isNotBlank()) {
                    VirtualDeviceProfile(existing.virtualCuid, existing.virtualMac, existing.virtualHostName)
                } else {
                    virtualDeviceManager.generateNewProfile(username)
                }

            val trafficMax =
                sessionResponse.trafficMax?.toLongOrNull()
                    ?: loginResponse.trafficMax?.toLongOrNull()
                    ?: 0L
            val trafficUsed =
                sessionResponse.trafficUsed?.toLongOrNull()
                    ?: loginResponse.trafficUsed?.toLongOrNull()
                    ?: 0L
            val dataLeft = maxOf(0L, trafficMax - trafficUsed)

            val isPro = sessionResponse.isPremium == 1 || (loginResponse.isPremium ?: 0) == 1
            val sessionAuthHash =
                loginResponse.sessionAuthHash
                    ?: existing?.sessionAuthHash
                    ?: preferencesHelper.sessionHash
                    ?: ""
            val rawSessionJson = Gson().toJson(sessionResponse)

            val accountEntity =
                AccountEntity(
                    id = existing?.id ?: 0L,
                    username = username,
                    sessionAuthHash = sessionAuthHash,
                    rawSessionJson = rawSessionJson,
                    dataLeft = dataLeft,
                    trafficMax = trafficMax,
                    trafficUsed = trafficUsed,
                    isPro = isPro,
                    isActive = false,
                    virtualCuid = profile.cuid,
                    virtualMac = profile.macAddress,
                    virtualHostName = profile.hostName,
                    sessionStatus = "VALID",
                    lastSyncTimestamp = System.currentTimeMillis(),
                )

            val id = accountDao.insertOrUpdate(accountEntity)
            switchToAccount(id)
            return id
        }

        override suspend fun switchToAccount(accountId: Long): Boolean {
            val account = accountDao.getAccountById(accountId) ?: return false

            accountDao.setActiveAccount(accountId)

            preferencesHelper.sessionHash = account.sessionAuthHash
            preferencesHelper.getSession = account.rawSessionJson
            preferencesHelper.userName = account.username
            preferencesHelper.userStatus = if (account.isPro) 1 else 0

            virtualDeviceManager.applyProfileToCd(
                VirtualDeviceProfile(account.virtualCuid, account.virtualMac, account.virtualHostName),
                cdLib,
            )

            _activeAccount.value = account.copy(isActive = true)
            userRepository.reload()
            return true
        }

        override suspend fun removeAccount(accountId: Long) {
            val target = accountDao.getAccountById(accountId)
            val wasActive = target?.isActive == true || _activeAccount.value?.id == accountId

            accountDao.deleteAccountById(accountId)

            if (wasActive) {
                val remainingAccounts = accountDao.getAllAccountsSync()
                val nextAccount =
                    remainingAccounts.firstOrNull { it.sessionStatus == "VALID" }
                        ?: remainingAccounts.firstOrNull()

                if (nextAccount != null) {
                    switchToAccount(nextAccount.id)
                } else {
                    accountDao.clearAllActiveAccounts()
                    _activeAccount.value = null
                    preferencesHelper.sessionHash = null
                    preferencesHelper.getSession = null
                    preferencesHelper.userName = ""
                    preferencesHelper.userStatus = 0
                    cdLib.clearVirtualProfile()
                    userRepository.reload()
                }
            }
        }

        override suspend fun refreshCurrentAccountTraffic(): Result<UserSessionResponse> =
            try {
                val response = apiManager.getSessionGeneric(null)
                val sessionResponse = response.dataClass
                if (sessionResponse != null) {
                    val active = accountDao.getActiveAccount()
                    if (active != null) {
                        val trafficUsed = sessionResponse.trafficUsed?.toLongOrNull() ?: 0L
                        val trafficMax = sessionResponse.trafficMax?.toLongOrNull() ?: 0L
                        val dataLeft = maxOf(0L, trafficMax - trafficUsed)
                        val rawJson = Gson().toJson(sessionResponse)

                        accountDao.updateTraffic(
                            id = active.id,
                            dataUsed = trafficUsed,
                            dataMax = trafficMax,
                            dataLeft = dataLeft,
                        )

                        preferencesHelper.getSession = rawJson
                        userRepository.reload(sessionResponse)

                        _activeAccount.value =
                            active.copy(
                                trafficUsed = trafficUsed,
                                trafficMax = trafficMax,
                                dataLeft = dataLeft,
                                rawSessionJson = rawJson,
                            )
                    }
                    Result.success(sessionResponse)
                } else {
                    val errorMsg = response.errorClass?.errorMessage ?: "Failed to refresh traffic"
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
    }
