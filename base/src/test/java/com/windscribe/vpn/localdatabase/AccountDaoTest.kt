/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.localdatabase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.windscribe.vpn.localdatabase.tables.AccountEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {
    private lateinit var database: WindscribeDatabase
    private lateinit var accountDao: AccountDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, WindscribeDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        accountDao = database.accountDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    private fun createSampleAccount(
        username: String = "testuser",
        isActive: Boolean = false,
        dataLeft: Long = 10L * 1024 * 1024 * 1024,
        trafficMax: Long = 10L * 1024 * 1024 * 1024,
        trafficUsed: Long = 0L,
        isPro: Boolean = false,
        cuid: String = "cuid-12345",
        mac: String = "02:00:00:12:34:56",
        hostName: String = "Pixel-8",
    ): AccountEntity =
        AccountEntity(
            username = username,
            sessionAuthHash = "auth_hash_$username",
            rawSessionJson = """{"username":"$username","status":1}""",
            dataLeft = dataLeft,
            trafficMax = trafficMax,
            trafficUsed = trafficUsed,
            isPro = isPro,
            isActive = isActive,
            virtualCuid = cuid,
            virtualMac = mac,
            virtualHostName = hostName,
            sessionStatus = "VALID",
            lastSyncTimestamp = 1700000000000L,
        )

    @Test
    fun testInsertAndGetAccountById() =
        runTest {
            val account = createSampleAccount("alice")
            val id = accountDao.insertOrUpdate(account)
            assertTrue("Generated ID must be > 0", id > 0)

            val retrieved = accountDao.getAccountById(id)
            assertNotNull(retrieved)
            assertEquals(id, retrieved?.id)
            assertEquals("alice", retrieved?.username)
            assertEquals("auth_hash_alice", retrieved?.sessionAuthHash)
            assertEquals("cuid-12345", retrieved?.virtualCuid)
            assertEquals("02:00:00:12:34:56", retrieved?.virtualMac)
            assertEquals("Pixel-8", retrieved?.virtualHostName)
            assertEquals(false, retrieved?.isActive)
            assertEquals(false, retrieved?.isPro)
            assertEquals("VALID", retrieved?.sessionStatus)
        }

    @Test
    fun testGetAccountByUsername() =
        runTest {
            val account = createSampleAccount("Bob_User")
            val id = accountDao.insertOrUpdate(account)

            val exactMatch = accountDao.getAccountByUsername("Bob_User")
            assertNotNull(exactMatch)
            assertEquals(id, exactMatch?.id)

            // Case-insensitive lookup test
            val lowerMatch = accountDao.getAccountByUsername("bob_user")
            assertNotNull(lowerMatch)
            assertEquals(id, lowerMatch?.id)

            val notFound = accountDao.getAccountByUsername("non_existent")
            assertNull(notFound)
        }

    @Test
    fun testInsertOrUpdateUpdatesExistingAccountByUsername() =
        runTest {
            val initial = createSampleAccount("charlie", dataLeft = 5L * 1024 * 1024 * 1024)
            val initialId = accountDao.insertOrUpdate(initial)

            val updated = createSampleAccount("charlie", dataLeft = 8L * 1024 * 1024 * 1024)
            val updatedId = accountDao.insertOrUpdate(updated)

            assertEquals("ID should remain unchanged on update by username", initialId, updatedId)
            val retrieved = accountDao.getAccountById(initialId)
            assertNotNull(retrieved)
            assertEquals(8L * 1024 * 1024 * 1024, retrieved?.dataLeft)

            val all = accountDao.getAllAccountsSync()
            assertEquals("Should only have 1 account in database", 1, all.size)
        }

    @Test
    fun testGetAllAccountsAndSync() =
        runTest {
            accountDao.insertOrUpdate(createSampleAccount("user1"))
            accountDao.insertOrUpdate(createSampleAccount("user2"))

            val syncList = accountDao.getAllAccountsSync()
            assertEquals(2, syncList.size)
            assertEquals("user1", syncList[0].username)
            assertEquals("user2", syncList[1].username)

            val flowList = accountDao.getAllAccounts().first()
            assertEquals(2, flowList.size)
        }

    @Test
    fun testSetActiveAccountTogglesProperly() =
        runTest {
            val id1 = accountDao.insertOrUpdate(createSampleAccount("user1", isActive = true))
            val id2 = accountDao.insertOrUpdate(createSampleAccount("user2", isActive = false))

            var active = accountDao.getActiveAccount()
            assertNotNull(active)
            assertEquals(id1, active?.id)
            assertEquals("user1", active?.username)

            // Switch active to user2
            accountDao.setActiveAccount(id2)

            active = accountDao.getActiveAccount()
            assertNotNull(active)
            assertEquals(id2, active?.id)
            assertEquals("user2", active?.username)

            // Verify user1 is now inactive
            val user1 = accountDao.getAccountById(id1)
            assertNotNull(user1)
            assertFalse(user1!!.isActive)

            // Verify user2 is active
            val user2 = accountDao.getAccountById(id2)
            assertNotNull(user2)
            assertTrue(user2!!.isActive)
        }

    @Test
    fun testUpdateTraffic() =
        runTest {
            val id =
                accountDao.insertOrUpdate(
                    createSampleAccount(
                        username = "traffictest",
                        dataLeft = 1000L,
                        trafficMax = 2000L,
                        trafficUsed = 1000L,
                    ),
                )

            accountDao.updateTraffic(id, dataUsed = 1500L, dataMax = 2000L, dataLeft = 500L)

            val retrieved = accountDao.getAccountById(id)
            assertNotNull(retrieved)
            assertEquals(1500L, retrieved?.trafficUsed)
            assertEquals(2000L, retrieved?.trafficMax)
            assertEquals(500L, retrieved?.dataLeft)
        }

    @Test
    fun testDeleteAccountById() =
        runTest {
            val id1 = accountDao.insertOrUpdate(createSampleAccount("delete1"))
            val id2 = accountDao.insertOrUpdate(createSampleAccount("delete2"))

            accountDao.deleteAccountById(id1)

            assertNull(accountDao.getAccountById(id1))
            assertNotNull(accountDao.getAccountById(id2))

            val all = accountDao.getAllAccountsSync()
            assertEquals(1, all.size)
            assertEquals("delete2", all[0].username)
        }

    @Test
    fun testGetAccountsWithDataAbove() =
        runTest {
            val lowData = createSampleAccount("low", dataLeft = 1L * 1024 * 1024 * 1024)
            val highData1 = createSampleAccount("high1", dataLeft = 3L * 1024 * 1024 * 1024)
            val highData2 = createSampleAccount("high2", dataLeft = 5L * 1024 * 1024 * 1024)
            val expiredHighData =
                createSampleAccount("expired", dataLeft = 10L * 1024 * 1024 * 1024)
                    .copy(sessionStatus = "EXPIRED")

            accountDao.insertOrUpdate(lowData)
            accountDao.insertOrUpdate(highData1)
            accountDao.insertOrUpdate(highData2)
            accountDao.insertOrUpdate(expiredHighData)

            val threshold = 2L * 1024 * 1024 * 1024
            val results = accountDao.getAccountsWithDataAbove(threshold)

            assertEquals(2, results.size)
            // Ordered by dataLeft DESC
            assertEquals("high2", results[0].username)
            assertEquals("high1", results[1].username)
        }
}
