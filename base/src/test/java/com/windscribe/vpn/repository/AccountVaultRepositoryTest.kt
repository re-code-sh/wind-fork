/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.repository

import com.google.gson.Gson
import com.windscribe.vpn.api.IApiCallManager
import com.windscribe.vpn.api.response.ApiErrorResponse
import com.windscribe.vpn.api.response.GenericResponseClass
import com.windscribe.vpn.api.response.UserLoginResponse
import com.windscribe.vpn.api.response.UserSessionResponse
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.backend.CdLib
import com.windscribe.vpn.backend.VirtualDeviceManager
import com.windscribe.vpn.localdatabase.AccountDao
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountVaultRepositoryTest {
    private lateinit var accountDao: AccountDao
    private lateinit var preferencesHelper: PreferencesHelper
    private lateinit var userRepository: UserRepository
    private lateinit var apiManager: IApiCallManager
    private lateinit var virtualDeviceManager: VirtualDeviceManager
    private lateinit var cdLib: CdLib
    private lateinit var allAccountsFlow: MutableStateFlow<List<AccountEntity>>

    @Before
    fun setUp() {
        accountDao = mockk(relaxed = true)
        preferencesHelper = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        apiManager = mockk(relaxed = true)
        virtualDeviceManager = VirtualDeviceManager()
        cdLib = CdLib()
        allAccountsFlow = MutableStateFlow(emptyList())

        every { accountDao.getAllAccounts() } returns allAccountsFlow
        coEvery { accountDao.setActiveAccount(any()) } answers {
            val id = firstArg<Long>()
            val current = allAccountsFlow.value
            val existing =
                current.find { it.id == id }
                    ?: kotlinx.coroutines.runBlocking { accountDao.getAccountById(id) }
            if (existing != null) {
                allAccountsFlow.value =
                    current.filter { it.id != id }.map { it.copy(isActive = false) } + existing.copy(isActive = true)
            }
        }
        coEvery { accountDao.clearAllActiveAccounts() } answers {
            allAccountsFlow.value = allAccountsFlow.value.map { it.copy(isActive = false) }
        }
        coEvery { accountDao.deleteAccountById(any()) } answers {
            val id = firstArg<Long>()
            allAccountsFlow.value = allAccountsFlow.value.filter { it.id != id }
        }
    }

    @After
    fun tearDown() {
        CdLib.activeProfile = null
    }

    private fun TestScope.buildRepository(): AccountVaultRepositoryImpl =
        AccountVaultRepositoryImpl(
            accountDao = accountDao,
            virtualDeviceManager = virtualDeviceManager,
            preferencesHelper = preferencesHelper,
            userRepository = userRepository,
            cdLib = cdLib,
            apiManager = apiManager,
            scope = this,
        )

    private fun TestScope.cleanup() = coroutineContext.cancelChildren()

    private fun createLoginResponse(
        username: String = "alice",
        sessionHash: String = "hash_alice",
        isPremium: Int = 1,
        trafficMax: String = "10000000000",
        trafficUsed: String = "2000000000",
    ): UserLoginResponse =
        Gson().fromJson(
            """
            {
                "username": "$username",
                "session_auth_hash": "$sessionHash",
                "is_premium": $isPremium,
                "traffic_max": "$trafficMax",
                "traffic_used": "$trafficUsed"
            }
            """.trimIndent(),
            UserLoginResponse::class.java,
        )

    private fun createSessionResponse(
        username: String = "alice",
        isPremium: Int = 1,
        trafficMax: String = "10000000000",
        trafficUsed: String = "2000000000",
    ): UserSessionResponse =
        UserSessionResponse().apply {
            this.userName = username
            this.isPremium = isPremium
            this.trafficMax = trafficMax
            this.trafficUsed = trafficUsed
        }

    private fun createAccountEntity(
        id: Long,
        username: String,
        sessionAuthHash: String = "hash_$username",
        rawSessionJson: String = """{"username":"$username"}""",
        dataLeft: Long = 8_000_000_000L,
        trafficMax: Long = 10_000_000_000L,
        trafficUsed: Long = 2_000_000_000L,
        isPro: Boolean = true,
        isActive: Boolean = false,
        virtualCuid: String = "cuid-$username",
        virtualMac: String = "00:11:22:33:44:55",
        virtualHostName: String = "Galaxy-S24",
        sessionStatus: String = "VALID",
    ): AccountEntity =
        AccountEntity(
            id = id,
            username = username,
            sessionAuthHash = sessionAuthHash,
            rawSessionJson = rawSessionJson,
            dataLeft = dataLeft,
            trafficMax = trafficMax,
            trafficUsed = trafficUsed,
            isPro = isPro,
            isActive = isActive,
            virtualCuid = virtualCuid,
            virtualMac = virtualMac,
            virtualHostName = virtualHostName,
            sessionStatus = sessionStatus,
        )

    // ==========================================
    // 1. addOrUpdateAccount tests
    // ==========================================

    @Test
    fun `addOrUpdateAccount for new account generates virtual profile and switches to it`() =
        runTest {
            val repository = buildRepository()
            val login = createLoginResponse("alice", "hash_alice", isPremium = 1)
            val session = createSessionResponse("alice", isPremium = 1)

            coEvery { accountDao.getAccountByUsername("alice") } returns null
            val entitySlot = slot<AccountEntity>()
            coEvery { accountDao.insertOrUpdate(capture(entitySlot)) } returns 1L

            // Mock getAccountById after insert
            coEvery { accountDao.getAccountById(1L) } answers {
                entitySlot.captured.copy(id = 1L)
            }

            val resultId = repository.addOrUpdateAccount(login, session)
            advanceUntilIdle()

            assertEquals(1L, resultId)
            assertTrue(entitySlot.isCaptured)
            val saved = entitySlot.captured
            assertEquals("alice", saved.username)
            assertEquals("hash_alice", saved.sessionAuthHash)
            assertEquals(8_000_000_000L, saved.dataLeft)
            assertTrue(saved.isPro)
            assertFalse(saved.isActive)
            assertTrue(saved.virtualCuid.isNotBlank())
            assertTrue(saved.virtualMac.isNotBlank())
            assertTrue(saved.virtualHostName.isNotBlank())

            // Switched to account
            coVerify { accountDao.setActiveAccount(1L) }
            verify { preferencesHelper.sessionHash = "hash_alice" }
            verify { preferencesHelper.userName = "alice" }
            verify { preferencesHelper.userStatus = 1 }
            verify { preferencesHelper.getSession = any() }
            verify { userRepository.reload() }
            assertEquals("alice", repository.activeAccount.value?.username)
            cleanup()
        }

    @Test
    fun `addOrUpdateAccount for existing account preserves existing virtual profile`() =
        runTest {
            val repository = buildRepository()
            val existing =
                createAccountEntity(
                    id = 42L,
                    username = "bob",
                    virtualCuid = "existing-cuid-42",
                    virtualMac = "AA:BB:CC:DD:EE:FF",
                    virtualHostName = "Pixel-8",
                )
            coEvery { accountDao.getAccountByUsername("bob") } returns existing

            val login = createLoginResponse("bob", "new_hash_bob", isPremium = 0, trafficMax = "5000", trafficUsed = "1000")
            val session = createSessionResponse("bob", isPremium = 0, trafficMax = "5000", trafficUsed = "1000")

            val entitySlot = slot<AccountEntity>()
            coEvery { accountDao.insertOrUpdate(capture(entitySlot)) } returns 42L
            coEvery { accountDao.getAccountById(42L) } answers { entitySlot.captured }

            val resultId = repository.addOrUpdateAccount(login, session)
            advanceUntilIdle()

            assertEquals(42L, resultId)
            val saved = entitySlot.captured
            assertEquals("existing-cuid-42", saved.virtualCuid)
            assertEquals("AA:BB:CC:DD:EE:FF", saved.virtualMac)
            assertEquals("Pixel-8", saved.virtualHostName)
            assertEquals(4000L, saved.dataLeft)
            assertFalse(saved.isPro)
            cleanup()
        }

    // ==========================================
    // 2. switchToAccount tests
    // ==========================================

    @Test
    fun `switchToAccount updates preferences and CdLib and reloads userRepository`() =
        runTest {
            val repository = buildRepository()
            val account =
                createAccountEntity(
                    id = 10L,
                    username = "carol",
                    sessionAuthHash = "hash_carol",
                    rawSessionJson = """{"username":"carol"}""",
                    isPro = true,
                    virtualCuid = "cuid-carol-123",
                    virtualMac = "11:22:33:44:55:66",
                    virtualHostName = "Xiaomi-14",
                )
            coEvery { accountDao.getAccountById(10L) } returns account

            val result = repository.switchToAccount(10L)
            advanceUntilIdle()

            assertTrue(result)
            coVerify { accountDao.setActiveAccount(10L) }
            verify { preferencesHelper.sessionHash = "hash_carol" }
            verify { preferencesHelper.getSession = """{"username":"carol"}""" }
            verify { preferencesHelper.userName = "carol" }
            verify { preferencesHelper.userStatus = 1 }
            assertEquals("cuid-carol-123", cdLib.getCuid())
            assertEquals("11:22:33:44:55:66", cdLib.getMacAddress())
            assertEquals("Xiaomi-14", cdLib.getHostName())
            verify { userRepository.reload() }
            assertEquals(10L, repository.activeAccount.value?.id)
            cleanup()
        }

    @Test
    fun `switchToAccount returns false if account does not exist in DAO`() =
        runTest {
            val repository = buildRepository()
            coEvery { accountDao.getAccountById(999L) } returns null

            val result = repository.switchToAccount(999L)
            advanceUntilIdle()

            assertFalse(result)
            coVerify(exactly = 0) { accountDao.setActiveAccount(any()) }
            verify(exactly = 0) { userRepository.reload() }
            cleanup()
        }

    @Test
    fun `CRITICAL switchToAccount NEVER calls apiManager deleteSession`() =
        runTest {
            val repository = buildRepository()
            val account1 = createAccountEntity(id = 1L, username = "acc1")
            val account2 = createAccountEntity(id = 2L, username = "acc2")
            coEvery { accountDao.getAccountById(1L) } returns account1
            coEvery { accountDao.getAccountById(2L) } returns account2

            repository.switchToAccount(1L)
            repository.switchToAccount(2L)
            advanceUntilIdle()

            coVerify(exactly = 0) { apiManager.deleteSession() }
            cleanup()
        }

    // ==========================================
    // 3. removeAccount tests
    // ==========================================

    @Test
    fun `removeAccount on active account switches to next available valid account`() =
        runTest {
            val repository = buildRepository()
            val activeAcc = createAccountEntity(id = 1L, username = "active", isActive = true)
            val nextAcc = createAccountEntity(id = 2L, username = "next", sessionStatus = "VALID")

            coEvery { accountDao.getAccountById(1L) } returns activeAcc
            coEvery { accountDao.getAccountById(2L) } returns nextAcc
            coEvery { accountDao.getAllAccountsSync() } returns listOf(nextAcc)

            repository.removeAccount(1L)
            advanceUntilIdle()

            coVerify { accountDao.deleteAccountById(1L) }
            coVerify { accountDao.setActiveAccount(2L) }
            verify { preferencesHelper.userName = "next" }
            assertEquals(2L, repository.activeAccount.value?.id)
            cleanup()
        }

    @Test
    fun `removeAccount on last active account clears preferences and activeAccount`() =
        runTest {
            val repository = buildRepository()
            val activeAcc = createAccountEntity(id = 1L, username = "last_acc", isActive = true)

            coEvery { accountDao.getAccountById(1L) } returns activeAcc
            coEvery { accountDao.getAllAccountsSync() } returns emptyList()

            repository.removeAccount(1L)
            advanceUntilIdle()

            coVerify { accountDao.deleteAccountById(1L) }
            coVerify { accountDao.clearAllActiveAccounts() }
            verify { preferencesHelper.sessionHash = null }
            verify { preferencesHelper.getSession = null }
            verify { preferencesHelper.userName = "" }
            verify { preferencesHelper.userStatus = 0 }
            assertNull(cdLib.virtualProfile)
            assertEquals("", cdLib.getCuid())
            verify { userRepository.reload() }
            assertNull(repository.activeAccount.value)
            cleanup()
        }

    @Test
    fun `removeAccount on inactive account does not switch active account`() =
        runTest {
            val repository = buildRepository()
            val inactiveAcc = createAccountEntity(id = 2L, username = "inactive", isActive = false)
            val currentActive = createAccountEntity(id = 1L, username = "active", isActive = true)

            coEvery { accountDao.getAccountById(2L) } returns inactiveAcc
            coEvery { accountDao.getActiveAccount() } returns currentActive

            repository.removeAccount(2L)
            advanceUntilIdle()

            coVerify { accountDao.deleteAccountById(2L) }
            coVerify(exactly = 0) { accountDao.setActiveAccount(any()) }
            cleanup()
        }

    // ==========================================
    // 4. refreshCurrentAccountTraffic tests
    // ==========================================

    @Test
    fun `refreshCurrentAccountTraffic updates DAO traffic and returns Result success`() =
        runTest {
            val repository = buildRepository()
            val activeAcc =
                createAccountEntity(
                    id = 5L,
                    username = "active_traffic",
                    isActive = true,
                    dataLeft = 5_000_000_000L,
                )
            coEvery { accountDao.getActiveAccount() } returns activeAcc

            val updatedSession =
                createSessionResponse(
                    username = "active_traffic",
                    trafficMax = "10000000000",
                    trafficUsed = "4000000000",
                )
            val genericResponse: GenericResponseClass<UserSessionResponse?, ApiErrorResponse?> =
                GenericResponseClass(updatedSession, null)
            coEvery { apiManager.getSessionGeneric(null) } returns genericResponse

            val result = repository.refreshCurrentAccountTraffic()
            advanceUntilIdle()

            assertTrue(result.isSuccess)
            assertEquals(updatedSession, result.getOrNull())
            coVerify {
                accountDao.updateTraffic(
                    id = 5L,
                    dataUsed = 4_000_000_000L,
                    dataMax = 10_000_000_000L,
                    dataLeft = 6_000_000_000L,
                )
            }
            verify { userRepository.reload(updatedSession) }
            cleanup()
        }

    @Test
    fun `refreshCurrentAccountTraffic on API failure returns Result failure`() =
        runTest {
            val repository = buildRepository()
            val apiError = ApiErrorResponse().apply { errorMessage = "Network timeout" }
            val genericResponse: GenericResponseClass<UserSessionResponse?, ApiErrorResponse?> =
                GenericResponseClass(null, apiError)
            coEvery { apiManager.getSessionGeneric(null) } returns genericResponse

            val result = repository.refreshCurrentAccountTraffic()
            advanceUntilIdle()

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { accountDao.updateTraffic(any(), any(), any(), any()) }
            cleanup()
        }

    // ==========================================
    // 5. activeAccount and allAccounts Flow tests
    // ==========================================

    @Test
    fun `allAccounts and activeAccount observe emissions from accountDao`() =
        runTest {
            val repository = buildRepository()
            val acc1 = createAccountEntity(id = 1L, username = "acc1", isActive = false)
            val acc2 = createAccountEntity(id = 2L, username = "acc2", isActive = true)

            allAccountsFlow.value = listOf(acc1, acc2)
            advanceUntilIdle()

            assertEquals(2L, repository.activeAccount.value?.id)
            assertEquals("acc2", repository.activeAccount.value?.username)
            cleanup()
        }
}
