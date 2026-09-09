package com.windscribe.vpn.backend

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VirtualDeviceManagerTest {
    private val manager = VirtualDeviceManager()

    @After
    fun tearDown() {
        CdLib.activeProfile = null
    }

    @Test
    fun `generateNewProfile produces valid UUID for CUID`() {
        val profile = manager.generateNewProfile("testuser")
        assertNotNull(profile.cuid)
        val parsed = UUID.fromString(profile.cuid)
        assertNotNull(parsed)
        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        assertTrue("CUID ${profile.cuid} does not match UUID regex", profile.cuid.matches(uuidRegex))
    }

    @Test
    fun `generateNewProfile produces valid MAC address with 6 hex pairs`() {
        val profile = manager.generateNewProfile("testuser")
        assertNotNull(profile.macAddress)
        val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        assertTrue("MAC ${profile.macAddress} does not match 6-pair uppercase hex pattern", profile.macAddress.matches(macRegex))

        val pairs = profile.macAddress.split(":")
        assertEquals(6, pairs.size)
        pairs.forEach { pair ->
            assertEquals(2, pair.length)
            val byteVal = pair.toInt(16)
            assertTrue(byteVal in 0..255)
        }
    }

    @Test
    fun `generateNewProfile produces non-blank valid hostname`() {
        val profile = manager.generateNewProfile("testuser")
        assertTrue(profile.hostName.isNotBlank())
        val hostRegex = Regex("^[A-Za-z0-9-]+$")
        assertTrue("HostName ${profile.hostName} should be alphanumeric with hyphens", profile.hostName.matches(hostRegex))
        assertTrue(
            "HostName should be one of known realistic device models",
            VirtualDeviceManager.KNOWN_DEVICE_MODELS.contains(profile.hostName),
        )
    }

    @Test
    fun `profiles generated for different accounts are strictly distinct`() {
        val profile1 = manager.generateNewProfile("account_alpha")
        val profile2 = manager.generateNewProfile("account_beta")

        assertNotEquals("Profiles should not be equal", profile1, profile2)
        assertNotEquals("CUIDs should be strictly distinct", profile1.cuid, profile2.cuid)
        assertNotEquals("MAC addresses should be strictly distinct", profile1.macAddress, profile2.macAddress)
    }

    @Test
    fun `multiple profiles generated for same account are distinct`() {
        val profile1 = manager.generateNewProfile("same_account")
        val profile2 = manager.generateNewProfile("same_account")

        assertNotEquals("Profiles generated in separate calls should be distinct", profile1, profile2)
        assertNotEquals("CUIDs must differ across invocations", profile1.cuid, profile2.cuid)
        assertNotEquals("MAC addresses must differ across invocations", profile1.macAddress, profile2.macAddress)
    }

    @Test
    fun `generateNewProfile handles empty or blank username gracefully`() {
        val profileEmpty = manager.generateNewProfile("")
        assertTrue(profileEmpty.cuid.isNotBlank())
        assertTrue(profileEmpty.macAddress.isNotBlank())
        assertTrue(profileEmpty.hostName.isNotBlank())

        val profileBlank = manager.generateNewProfile("   ")
        assertTrue(profileBlank.cuid.isNotBlank())
        assertTrue(profileBlank.macAddress.isNotBlank())
        assertTrue(profileBlank.hostName.isNotBlank())
    }

    @Test
    fun `applyProfileToCd updates CdLib with virtual profile credentials`() {
        val profile = manager.generateNewProfile("user_cd_test")
        val cdLib = CdLib()

        manager.applyProfileToCd(profile, cdLib)

        assertEquals("CdLib hostName should match profile hostName", profile.hostName, cdLib.getHostName())
        assertEquals("CdLib macAddress should match profile macAddress", profile.macAddress, cdLib.getMacAddress())
        assertEquals("CdLib cuid should match profile cuid", profile.cuid, cdLib.getCuid())
    }

    @Test
    fun `CdLib direct override and clear profile works correctly`() {
        val cdLib = CdLib()
        val customProfile =
            VirtualDeviceProfile(
                cuid = "custom-cuid-1234",
                macAddress = "AA:BB:CC:DD:EE:FF",
                hostName = "Custom-Device-Model",
            )

        cdLib.setVirtualProfile(customProfile)
        assertEquals("custom-cuid-1234", cdLib.getCuid())
        assertEquals("AA:BB:CC:DD:EE:FF", cdLib.getMacAddress())
        assertEquals("Custom-Device-Model", cdLib.getHostName())

        cdLib.clearVirtualProfile()
        assertEquals("", cdLib.getCuid())
    }
}
