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
import com.windscribe.vpn.state.VPNConnectionStateManager
import com.windscribe.vpn.workers.WindScribeWorkManager
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            userRepository.reload()

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

        override suspend fun importAccountsBulk(
            credentials: List<Pair<String, String>>,
            onProgress: (current: Int, total: Int, status: BulkImportStatus) -> Unit,
        ): BulkImportResult {
            val activeAccount = accountDao.getActiveAccount()
            val initialProfile =
                if (activeAccount != null && activeAccount.virtualCuid.isNotBlank()) {
                    VirtualDeviceProfile(activeAccount.virtualCuid, activeAccount.virtualMac, activeAccount.virtualHostName)
                } else {
                    null
                }

            var successCount = 0
            var failedCount = 0
            val failures = mutableListOf<Pair<String, String>>()
            val total = credentials.size

            try {
                credentials.forEachIndexed { index, (rawUsername, rawPassword) ->
                    val username = rawUsername.trim()
                    val password = rawPassword.trim()
                    val currentIdx = index + 1

                    if (username.isBlank() || password.isBlank()) {
                        failedCount++
                        val err = "Username or password cannot be blank"
                        failures.add(Pair(username, err))
                        onProgress(currentIdx, total, BulkImportStatus.Failed(username, err))
                        return@forEachIndexed
                    }

                    onProgress(currentIdx, total, BulkImportStatus.Starting(username))

                    val existing = accountDao.getAccountByUsername(username)
                    val profile =
                        if (existing != null && existing.virtualCuid.isNotBlank()) {
                            VirtualDeviceProfile(existing.virtualCuid, existing.virtualMac, existing.virtualHostName)
                        } else {
                            virtualDeviceManager.generateNewProfile(username)
                        }

                    virtualDeviceManager.applyProfileToCd(profile, cdLib)

                    var authResp = apiManager.authTokenLogin(username, false)
                    if (authResp.errorClass?.errorCode == 429) {
                        onProgress(currentIdx, total, BulkImportStatus.RateLimited(username, 10))
                        kotlinx.coroutines.delay(10_000L)
                        authResp = apiManager.authTokenLogin(username, false)
                    }

                    val authToken = authResp.dataClass
                    if (authToken == null) {
                        val err = authResp.errorClass?.errorMessage ?: "Failed to get auth token"
                        failedCount++
                        failures.add(Pair(username, err))
                        onProgress(currentIdx, total, BulkImportStatus.Failed(username, err))
                    } else if (authToken.captcha != null) {
                        val err = "Captcha verification required by server"
                        failedCount++
                        failures.add(Pair(username, err))
                        onProgress(currentIdx, total, BulkImportStatus.Failed(username, err))
                    } else {
                        val loginResp =
                            apiManager.logUserIn(
                                username = username,
                                password = password,
                                twoFa = null,
                                secureToken = authToken.token,
                                captchaSolution = null,
                                captchaTrailX = floatArrayOf(),
                                captchaTrailY = floatArrayOf(),
                            )

                        val loginData = loginResp.dataClass
                        if (loginData == null) {
                            val err = loginResp.errorClass?.errorMessage ?: "Invalid credentials or login failure"
                            failedCount++
                            failures.add(Pair(username, err))
                            onProgress(currentIdx, total, BulkImportStatus.Failed(username, err))
                        } else {
                            val sessionHash = loginData.sessionAuthHash
                            if (sessionHash.isNullOrBlank()) {
                                val err = "No session auth hash returned"
                                failedCount++
                                failures.add(Pair(username, err))
                                onProgress(currentIdx, total, BulkImportStatus.Failed(username, err))
                            } else {
                                val trafficMax = loginData.trafficMax?.toLongOrNull() ?: 0L
                                val trafficUsed = loginData.trafficUsed?.toLongOrNull() ?: 0L
                                val dataLeft = maxOf(0L, trafficMax - trafficUsed)
                                val isPro = (loginData.isPremium ?: 0) == 1

                                val sessionResponse =
                                    UserSessionResponse().apply {
                                        this.userName = loginData.userName
                                        this.userID = loginData.userID
                                        this.isPremium = loginData.isPremium
                                        this.trafficMax = loginData.trafficMax
                                        this.trafficUsed = loginData.trafficUsed
                                        this.userAccountStatus = loginData.userAccountStatus
                                        this.billingPlanID = loginData.billingPlanID
                                        this.alcList = loginData.alcList
                                        this.locationHash = loginData.locationHash
                                        this.locationRevision = loginData.locationRevision
                                    }
                                val rawSessionJson = Gson().toJson(sessionResponse)

                                val accountEntity =
                                    AccountEntity(
                                        id = existing?.id ?: 0L,
                                        username = username,
                                        sessionAuthHash = sessionHash,
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

                                accountDao.insertOrUpdate(accountEntity)
                                successCount++
                                onProgress(currentIdx, total, BulkImportStatus.Success(username, isPro, dataLeft))
                            }
                        }
                    }

                    if (index < credentials.size - 1) {
                        val jitter = kotlin.random.Random.nextLong(3000L, 5001L)
                        kotlinx.coroutines.delay(jitter)
                    }
                }
            } finally {
                if (initialProfile != null) {
                    virtualDeviceManager.applyProfileToCd(initialProfile, cdLib)
                } else {
                    cdLib.clearVirtualProfile()
                }
            }

            return BulkImportResult(
                totalProcessed = total,
                successCount = successCount,
                failedCount = failedCount,
                failures = failures,
            )
        }
    }
