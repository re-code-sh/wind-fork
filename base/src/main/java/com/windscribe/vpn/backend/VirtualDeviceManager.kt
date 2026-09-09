package com.windscribe.vpn.backend

import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.random.asKotlinRandom

@Singleton
class VirtualDeviceManager
    @Inject
    constructor() {
        fun generateNewProfile(
            username: String,
            random: Random = SecureRandom().asKotlinRandom(),
        ): VirtualDeviceProfile {
            val cuid = UUID.randomUUID().toString()
            val macAddress = generateRandomMacAddress(random)
            val hostName = KNOWN_DEVICE_MODELS.random(random)
            return VirtualDeviceProfile(
                cuid = cuid,
                macAddress = macAddress,
                hostName = hostName,
            )
        }

        fun applyProfileToCd(
            profile: VirtualDeviceProfile,
            cdLib: CdLib,
        ) {
            cdLib.setVirtualProfile(profile)
        }

        private fun generateRandomMacAddress(random: Random): String {
            val bytes = ByteArray(6)
            random.nextBytes(bytes)
            // Ensure unicast (LSB = 0) and locally administered (bit 1 = 1)
            bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }
        }

        companion object {
            val KNOWN_DEVICE_MODELS =
                listOf(
                    "Galaxy-S24",
                    "Galaxy-S23",
                    "Galaxy-A54",
                    "Galaxy-A34",
                    "Pixel-8",
                    "Pixel-8-Pro",
                    "Pixel-7",
                    "Pixel-7-Pro",
                    "Xiaomi-14",
                    "Xiaomi-13",
                    "OnePlus-12",
                    "OnePlus-11",
                )
        }
    }
