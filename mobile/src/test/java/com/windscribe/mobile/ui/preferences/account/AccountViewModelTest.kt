/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.mobile.ui.preferences.account

import com.windscribe.vpn.api.IApiCallManager
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import com.windscribe.vpn.model.User
import com.windscribe.vpn.repository.AccountVaultRepository
import com.windscribe.vpn.repository.UserRepository
import com.windscribe.vpn.workers.WindScribeWorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val userRepository: UserRepository = mockk(relaxed = true)
    private val api: IApiCallManager = mockk(relaxed = true)
    private val workManager: WindScribeWorkManager = mockk(relaxed = true)
    private val preferencesHelper: PreferencesHelper = mockk(relaxed = true)
    private val accountVaultRepository: AccountVaultRepository = mockk(relaxed = true)

    private val userFlow = MutableStateFlow<User?>(null)
    private val allAccountsFlow = MutableStateFlow<List<AccountEntity>>(emptyList())
    private val activeAccountFlow = MutableStateFlow<AccountEntity?>(null)

    private val sampleAccount1 =
        AccountEntity(
            id = 1L,
            username = "alpha_user",
            sessionAuthHash = "hash_alpha",
            rawSessionJson = "{}",
            dataLeft = 10_000_000_000L,
            trafficMax = 10_000_000_000L,
            trafficUsed = 0L,
            isPro = false,
            isActive = true,
            virtualCuid = "cuid_1",
            virtualMac = "02:00:00:00:00:01",
            virtualHostName = "host1",
            sessionStatus = "VALID",
            lastSyncTimestamp = 1000L,
        )

    private val sampleAccount2 =
        AccountEntity(
            id = 2L,
            username = "bravo_user",
            sessionAuthHash = "hash_bravo",
            rawSessionJson = "{}",
            dataLeft = 5_000_000_000L,
            trafficMax = 10_000_000_000L,
            trafficUsed = 5_000_000_000L,
            isPro = false,
            isActive = false,
            virtualCuid = "cuid_2",
            virtualMac = "02:00:00:00:00:02",
            virtualHostName = "host2",
            sessionStatus = "VALID",
            lastSyncTimestamp = 1000L,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { userRepository.user } returns userFlow
        every { accountVaultRepository.allAccounts } returns allAccountsFlow
        every { accountVaultRepository.activeAccount } returns activeAccountFlow
        every { preferencesHelper.isSsoLogin } returns false
        every { preferencesHelper.sessionHash } returns null
        every { preferencesHelper.getSession } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AccountViewModelImpl =
        AccountViewModelImpl(
            userRepository = userRepository,
            api = api,
            workManager = workManager,
            preferencesHelper = preferencesHelper,
            accountVaultRepository = accountVaultRepository,
        )

    @Test
    fun `accountsList and activeAccount reflect emissions from AccountVaultRepository`() =
        runTest(testDispatcher) {
            allAccountsFlow.value = listOf(sampleAccount1, sampleAccount2)
            activeAccountFlow.value = sampleAccount1

            val viewModel = createViewModel()
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                viewModel.accountsList.collect()
            }
            advanceUntilIdle()

            assertEquals(2, viewModel.accountsList.value.size)
            assertEquals("alpha_user", viewModel.accountsList.value[0].username)
            assertEquals("bravo_user", viewModel.accountsList.value[1].username)

            assertNotNull(viewModel.activeAccount.value)
            assertEquals(1L, viewModel.activeAccount.value?.id)
            assertEquals("alpha_user", viewModel.activeAccount.value?.username)
        }

    @Test
    fun `onSwitchAccount calls AccountVaultRepository switchToAccount`() =
        runTest(testDispatcher) {
            coEvery { accountVaultRepository.switchToAccount(2L) } returns true

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onSwitchAccount(2L)
            advanceUntilIdle()

            coVerify(exactly = 1) { accountVaultRepository.switchToAccount(2L) }
            assertEquals(false, viewModel.showProgress.value)
        }

    @Test
    fun `onRemoveAccount calls AccountVaultRepository removeAccount`() =
        runTest(testDispatcher) {
            coEvery { accountVaultRepository.removeAccount(2L) } returns Unit

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onRemoveAccount(2L)
            advanceUntilIdle()

            coVerify(exactly = 1) { accountVaultRepository.removeAccount(2L) }
            assertEquals(false, viewModel.showProgress.value)
        }

    @Test
    fun `auto-sync vaults current session if present in preferencesHelper on init`() =
        runTest(testDispatcher) {
            coEvery { accountVaultRepository.saveCurrentSessionToVault() } returns 10L

            val viewModel = createViewModel()
            advanceUntilIdle()

            coVerify(atLeast = 1) {
                accountVaultRepository.saveCurrentSessionToVault()
            }
        }

    @Test
    fun `empty activeAccount handles null gracefully`() =
        runTest(testDispatcher) {
            allAccountsFlow.value = emptyList()
            activeAccountFlow.value = null

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(0, viewModel.accountsList.value.size)
            assertNull(viewModel.activeAccount.value)
        }

    @Test
    fun `onSwitchAccount failure emits error alert`() =
        runTest(testDispatcher) {
            coEvery { accountVaultRepository.switchToAccount(999L) } returns false

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onSwitchAccount(999L)
            advanceUntilIdle()

            val alert = viewModel.alertState.value
            org.junit.Assert.assertTrue(alert is AlertState.Error)
            assertEquals(false, viewModel.showProgress.value)
        }

    @Test
    fun `onRemoveAccount exception emits error alert`() =
        runTest(testDispatcher) {
            coEvery { accountVaultRepository.removeAccount(999L) } throws RuntimeException("DB error")

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onRemoveAccount(999L)
            advanceUntilIdle()

            val alert = viewModel.alertState.value
            org.junit.Assert.assertTrue(alert is AlertState.Error)
            assertEquals(false, viewModel.showProgress.value)
        }

    @Test
    fun `onOpenBulkImport and onDismissBulkImport control dialog state`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(false, viewModel.showBulkImportDialog.value)
            viewModel.onOpenBulkImport()
            assertEquals(true, viewModel.showBulkImportDialog.value)

            viewModel.onDismissBulkImport()
            assertEquals(false, viewModel.showBulkImportDialog.value)
            assertNull(viewModel.bulkImportState.value)
        }

    @Test
    fun `onStartBulkImport parses credentials and calls importAccountsBulk`() =
        runTest(testDispatcher) {
            val expectedResult =
                com.windscribe.vpn.repository.BulkImportResult(
                    totalProcessed = 2,
                    successCount = 2,
                    failedCount = 0,
                )
            coEvery { accountVaultRepository.importAccountsBulk(any(), any()) } returns expectedResult

            val viewModel = createViewModel()
            advanceUntilIdle()

            val text = "user1:pass1\nuser2:pass2"
            viewModel.onStartBulkImport(text)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                accountVaultRepository.importAccountsBulk(
                    listOf("user1" to "pass1", "user2" to "pass2"),
                    any(),
                )
            }

            val state = viewModel.bulkImportState.value
            assertNotNull(state)
            assertEquals(2, state?.total)
            assertEquals(2, state?.successCount)
            assertEquals(0, state?.failedCount)
            assertEquals(true, state?.isCompleted)
            assertEquals(false, state?.isRunning)
        }

    @Test
    fun `onStartBulkImport with empty or invalid input emits error alert`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onStartBulkImport("not_valid_format_line")
            advanceUntilIdle()

            val alert = viewModel.alertState.value
            org.junit.Assert.assertTrue(alert is AlertState.Error)
            coVerify(exactly = 0) { accountVaultRepository.importAccountsBulk(any(), any()) }
        }

    @Test
    fun `onCancelBulkImport marks state as cancelled and stops running`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onStartBulkImport("user1:pass1")
            viewModel.onCancelBulkImport()
            advanceUntilIdle()

            val state = viewModel.bulkImportState.value
            assertNotNull(state)
            assertEquals(false, state?.isRunning)
            assertEquals(true, state?.isCompleted)
        }
}
