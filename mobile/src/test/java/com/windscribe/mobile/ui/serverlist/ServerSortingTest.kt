/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.mobile.ui.serverlist

import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.AZ_LIST_SELECTION_MODE
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.DEFAULT_LIST_SELECTION_MODE
import com.windscribe.vpn.localdatabase.LocalDbInterface
import com.windscribe.vpn.repository.FavouriteRepository
import com.windscribe.vpn.repository.LatencyRepository
import com.windscribe.vpn.repository.ServerListRepository
import com.windscribe.vpn.repository.StaticIpRepository
import com.windscribe.vpn.repository.UserRepository
import com.windscribe.vpn.serverlist.entity.Datacenter
import com.windscribe.vpn.serverlist.entity.Location
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSortingTest {
    private val testDispatcher = StandardTestDispatcher()

    private val serverRepository: ServerListRepository = mockk(relaxed = true)
    private val favouriteRepository: FavouriteRepository = mockk(relaxed = true)
    private val staticIpRepository: StaticIpRepository = mockk(relaxed = true)
    private val localDbInterface: LocalDbInterface = mockk(relaxed = true)
    private val preferencesHelper: PreferencesHelper = mockk(relaxed = true)
    private val latencyRepository: LatencyRepository = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { serverRepository.serversState } returns MutableStateFlow(mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ServerViewModelImpl =
        ServerViewModelImpl(
            serverRepository = serverRepository,
            favouriteRepository = favouriteRepository,
            staticIpRepository = staticIpRepository,
            localDbInterface = localDbInterface,
            preferencesHelper = preferencesHelper,
            latencyRepository = latencyRepository,
            userRepository = userRepository,
        )

    @Test
    fun `sortCities places free servers before pro servers`() {
        every { preferencesHelper.selection } returns DEFAULT_LIST_SELECTION_MODE
        val viewModel = createViewModel()

        val proCityA =
            Datacenter().apply {
                id = 1
                nodeName = "Atlanta"
                pro = 1
            }
        val proCityB =
            Datacenter().apply {
                id = 2
                nodeName = "Boston"
                pro = 1
            }
        val freeCityC =
            Datacenter().apply {
                id = 3
                nodeName = "Chicago"
                pro = 0
            }
        val freeCityD =
            Datacenter().apply {
                id = 4
                nodeName = "Dallas"
                pro = 0
            }

        val input = listOf(proCityB, freeCityD, proCityA, freeCityC)

        with(viewModel) {
            val sorted = input.sortCities()

            // Free cities come first, then sorted by nodeName
            assertEquals(listOf(freeCityC, freeCityD, proCityA, proCityB), sorted)
        }
    }

    @Test
    fun `sortRegions places regions with free datacenters first deterministically`() {
        every { preferencesHelper.selection } returns DEFAULT_LIST_SELECTION_MODE
        val viewModel = createViewModel()

        val freeLocation1 =
            ServerListItem(
                id = 10,
                region = Location(10, "US Central", "US", "US-C", 1, "North America"),
                datacenters =
                    listOf(
                        Datacenter().apply {
                            pro = 0
                            nodeName = "Dallas"
                        },
                    ),
            )
        val freeLocation2 =
            ServerListItem(
                id = 20,
                region = Location(20, "Canada East", "CA", "CA-E", 2, "North America"),
                datacenters =
                    listOf(
                        Datacenter().apply {
                            pro = 0
                            nodeName = "Toronto"
                        },
                    ),
            )
        val proLocation1 =
            ServerListItem(
                id = 30,
                region = Location(30, "Austria", "AT", "AT", 3, "Europe"),
                datacenters =
                    listOf(
                        Datacenter().apply {
                            pro = 1
                            nodeName = "Vienna"
                        },
                    ),
            )
        val proLocation2 =
            ServerListItem(
                id = 40,
                region = Location(40, "Belgium", "BE", "BE", 4, "Europe"),
                datacenters =
                    listOf(
                        Datacenter().apply {
                            pro = 1
                            nodeName = "Brussels"
                        },
                    ),
            )

        // Mixed order input
        val input = listOf(proLocation2, freeLocation2, proLocation1, freeLocation1)

        with(viewModel) {
            val sorted = input.sortRegions()

            // Both free locations must be at the top, ordered by sortOrder
            assertEquals(10, sorted[0].id)
            assertEquals(20, sorted[1].id)
            // Pro locations follow, ordered by sortOrder
            assertEquals(30, sorted[2].id)
            assertEquals(40, sorted[3].id)
        }
    }

    @Test
    fun `sortRegions places free regions first in AZ selection mode`() {
        every { preferencesHelper.selection } returns AZ_LIST_SELECTION_MODE
        val viewModel = createViewModel()

        val freeZ =
            ServerListItem(
                id = 1,
                region = Location(1, "Zurich Free", "CH", "CH", 1, "Europe"),
                datacenters = listOf(Datacenter().apply { pro = 0 }),
            )
        val freeA =
            ServerListItem(
                id = 2,
                region = Location(2, "Amsterdam Free", "NL", "NL", 2, "Europe"),
                datacenters = listOf(Datacenter().apply { pro = 0 }),
            )
        val proA =
            ServerListItem(
                id = 3,
                region = Location(3, "Athens Pro", "GR", "GR", 3, "Europe"),
                datacenters = listOf(Datacenter().apply { pro = 1 }),
            )

        val input = listOf(proA, freeZ, freeA)

        with(viewModel) {
            val sorted = input.sortRegions()

            // Free locations come first in alphabetical order (Amsterdam, then Zurich), followed by pro locations (Athens)
            assertEquals(listOf(freeA, freeZ, proA), sorted)
        }
    }
}
