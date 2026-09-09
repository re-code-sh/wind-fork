package com.windscribe.vpn.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CdLib(
    private val deviceInfo: AndroidDeviceIdentity = AndroidDeviceIdentityImpl(),
) {
    @Volatile
    private var isLoaded = false

    private var _virtualProfile: VirtualDeviceProfile? = null
    val virtualProfile: VirtualDeviceProfile?
        get() = _virtualProfile

    var virtualCuid: String? = null
    var virtualHostName: String? = null
    var virtualMacAddress: String? = null

    init {
        // Load device info asynchronously to avoid blocking app startup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deviceInfo.load()
                isLoaded = true
            } catch (ignored: Throwable) {
                // In unit test or environment where Android context is absent
            }
        }
    }

    external fun startCd(
        cuid: String,
        homeDir: String,
        proto: String,
        logPath: String,
        hostName: String,
        lanIp: String,
        macAddress: String,
    )

    external fun stopCd(
        restart: Boolean,
        pin: Int,
    ): Int

    external fun isCdRunning(): Boolean

    fun setVirtualProfile(profile: VirtualDeviceProfile?) {
        _virtualProfile = profile
        virtualCuid = profile?.cuid
        virtualHostName = profile?.hostName
        virtualMacAddress = profile?.macAddress
        activeProfile = profile
    }

    fun applyVirtualProfile(profile: VirtualDeviceProfile) = setVirtualProfile(profile)

    fun clearVirtualProfile() {
        setVirtualProfile(null)
    }

    fun getHostName(): String =
        virtualHostName
            ?: _virtualProfile?.hostName
            ?: activeProfile?.hostName
            ?: (deviceInfo.deviceHostName ?: "")

    fun getLanIP(): String = deviceInfo.deviceLanIp ?: ""

    fun getMacAddress(): String =
        virtualMacAddress
            ?: _virtualProfile?.macAddress
            ?: activeProfile?.macAddress
            ?: (deviceInfo.deviceMacAddress ?: "")

    fun getCuid(): String =
        virtualCuid
            ?: _virtualProfile?.cuid
            ?: activeProfile?.cuid
            ?: ""

    companion object {
        @Volatile
        var activeProfile: VirtualDeviceProfile? = null
    }
}
