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
import com.windscribe.vpn.backend.utils.WindVpnController
import com.windscribe.vpn.localdatabase.AccountDao
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import com.windscribe.vpn.model.User
import com.windscribe.vpn.state.VPNConnectionStateManager
import com.windscribe.vpn.workers.WindScribeWorkManager
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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
        private val workManager: Lazy<WindScribeWorkManager>,
        private val vpnConnectionStateManager: Lazy<VPNConnectionStateManager>,
        private val vpnController: Lazy<WindVpnController>,
    ) : AccountVaultRepository {
        private val logger = LoggerFactory.getLogger("vault")
        private val _activeAccount = MutableStateFlow<AccountEntity?>(null)
        override val activeAccount: StateFlow<AccountEntity?> = _activeAccount.asStateFlow()
        override val allAccounts: Flow<List<AccountEntity>> = accountDao.getAllAccounts()
        private var isAutoSwitching = false
        private var lastAutoSwitchTimestamp = 0L

        init {
            scope.launch {
                accountDao.getAllAccounts().collect { accounts ->
                    _activeAccount.value = accounts.firstOrNull { it.isActive }
                }
            }

            scope.launch {
                userRepository.user.filterNotNull().collect { user ->
                    syncCurrentTraffic(user)
                    checkAndAutoSwitch()
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

        override suspend fun addOrUpdateAccount(
            sessionResponse: UserSessionResponse,
            sessionAuthHash: String,
        ): Long {
            val username = sessionResponse.userName ?: preferencesHelper.userName
            val existing = accountDao.getAccountByUsername(username)
            val profile =
                if (existing != null && existing.virtualCuid.isNotBlank()) {
                    VirtualDeviceProfile(existing.virtualCuid, existing.virtualMac, existing.virtualHostName)
                } else {
                    virtualDeviceManager.generateNewProfile(username)
                }

            val trafficMax = sessionResponse.trafficMax?.toLongOrNull() ?: 0L
            val trafficUsed = sessionResponse.trafficUsed?.toLongOrNull() ?: 0L
            val dataLeft = maxOf(0L, trafficMax - trafficUsed)
            val isPro = sessionResponse.isPremium == 1
            val finalHash =
                sessionAuthHash.ifBlank {
                    existing?.sessionAuthHash ?: preferencesHelper.sessionHash ?: ""
                }
            val rawSessionJson = Gson().toJson(sessionResponse)

            val accountEntity =
                AccountEntity(
                    id = existing?.id ?: 0L,
                    username = username,
                    sessionAuthHash = finalHash,
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

        override suspend fun saveCurrentSessionToVault(): Long? {
            val currentHash = preferencesHelper.sessionHash ?: return null
            val currentSessionJson = preferencesHelper.getSession ?: return null
            val sessionResponse =
                try {
                    Gson().fromJson(currentSessionJson, UserSessionResponse::class.java)
                } catch (_: Exception) {
                    null
                } ?: return null

            val username = sessionResponse.userName ?: preferencesHelper.userName
            val existing = accountDao.getAccountByUsername(username)
            if (existing != null) {
                val active = accountDao.getActiveAccount()
                if (active == null) {
                    switchToAccount(existing.id)
                }
                return existing.id
            }

            return addOrUpdateAccount(sessionResponse, currentHash)
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

            val sessionResponse =
                try {
                    Gson().fromJson(account.rawSessionJson, UserSessionResponse::class.java)
                } catch (e: Exception) {
                    logger.error("Failed to parse rawSessionJson for account ${account.username}: ${e.message}")
                    null
                } ?: UserSessionResponse().apply {
                    this.userName = account.username
                    this.isPremium = if (account.isPro) 1 else 0
                    this.trafficMax = account.trafficMax.toString()
                    this.trafficUsed = account.trafficUsed.toString()
                    this.userAccountStatus = 1
                }

            sessionResponse.apply {
                if (account.trafficMax > 0L) {
                    this.trafficMax = account.trafficMax.toString()
                }
                this.trafficUsed = account.trafficUsed.toString()
                this.userName = account.username
                this.isPremium = if (account.isPro) 1 else 0
            }

            userRepository.reload(sessionResponse)

            try {
                workManager.get().updateSession()
                workManager.get().updateCredentialsUpdate()
            } catch (e: Exception) {
                logger.warn("Failed to trigger live session update: ${e.message}")
            }

            try {
                if (vpnConnectionStateManager.get().isVPNActive()) {
                    vpnController.get().connect()
                }
            } catch (e: Exception) {
                logger.error("Failed to execute seamless tunnel handoff: ${e.message}")
            }

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

        private suspend fun syncCurrentTraffic(user: User) {
            val active = _activeAccount.value ?: accountDao.getActiveAccount() ?: return
            if (active.username == user.userName) {
                val dataUsed = user.dataUsed
                val dataMax = user.maxData
                val dataLeft = user.dataLeft
                if (active.trafficUsed != dataUsed || active.trafficMax != dataMax || active.dataLeft != dataLeft) {
                    accountDao.updateTraffic(
                        id = active.id,
                        dataUsed = dataUsed,
                        dataMax = dataMax,
                        dataLeft = dataLeft,
                    )
                }
            }
        }

        override suspend fun checkAndAutoSwitch(): Boolean {
            if (isAutoSwitching) return false
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAutoSwitchTimestamp < 30_000L) {
                return false
            }

            val currentActive = _activeAccount.value ?: accountDao.getActiveAccount() ?: return false
            if (currentActive.isPro) return false

            val currentUser = userRepository.user.value
            if (currentUser != null && currentUser.userName == currentActive.username) {
                if (currentUser.isPro) return false
                if (currentUser.dataLeft >= AccountVaultRepository.AUTO_SWITCH_THRESHOLD_BYTES) {
                    return false
                }
            } else if (currentActive.dataLeft >= AccountVaultRepository.AUTO_SWITCH_THRESHOLD_BYTES) {
                return false
            }

            val candidates =
                accountDao
                    .getAccountsWithDataAbove(AccountVaultRepository.AUTO_SWITCH_THRESHOLD_BYTES)
                    .filter { it.id != currentActive.id }

            val bestCandidate = candidates.maxByOrNull { it.dataLeft } ?: return false

            isAutoSwitching = true
            try {
                logger.info(
                    "Auto-switching account: '${currentActive.username}' has low data (${currentActive.dataLeft} bytes). " +
                        "Switching to '${bestCandidate.username}' (${bestCandidate.dataLeft} bytes).",
                )
                val success = switchToAccount(bestCandidate.id)
                if (success) {
                    lastAutoSwitchTimestamp = System.currentTimeMillis()
                }
                return success
            } finally {
                isAutoSwitching = false
            }
        }

        internal fun resetAutoSwitchCooldown() {
            lastAutoSwitchTimestamp = 0L
        }
    }
