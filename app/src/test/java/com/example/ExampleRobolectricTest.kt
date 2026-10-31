package com.example

import com.example.core.LogLevel
import com.example.core.RescueLogger
import com.example.model.DeviceProfileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueUnitTest {

    @Test
    fun testSonyProfileConfiguration() {
        val sony = DeviceProfileResolver.SONY_PROFILE
        assertNotNull(sony)
        assertEquals("sony", sony.manufacturer)
        assertTrue(sony.checkboxViewIds.contains("android:id/alwaysUse"))
        assertTrue(sony.allowButtonViewIds.contains("android:id/button1"))
        assertTrue(sony.targetPackages.contains("com.android.systemui"))
    }

    @Test
    fun testSamsungProfileConfiguration() {
        val samsung = DeviceProfileResolver.SAMSUNG_PROFILE
        assertNotNull(samsung)
        assertEquals("samsung", samsung.manufacturer)
        assertTrue(samsung.targetPackages.contains("com.samsung.android.settings"))
        assertTrue(samsung.checkboxViewIds.contains("com.android.systemui:id/alwaysUse"))
        assertTrue(samsung.allowButtonViewIds.contains("android:id/button1"))
    }

    @Test
    fun testGenericProfileConfiguration() {
        val generic = DeviceProfileResolver.GENERIC_PROFILE
        assertNotNull(generic)
        assertEquals("generic", generic.manufacturer)
        assertTrue(generic.checkboxTexts.any { it.contains("always allow") })
        assertTrue(generic.allowButtonTexts.any { it.contains("allow") })
    }

    @Test
    fun testRescueLoggerMemoryBuffer() {
        RescueLogger.clear()
        RescueLogger.i("Test informational message")
        RescueLogger.s("Test success message")
        RescueLogger.w("Test warning message")
        RescueLogger.e("Test error message")

        val currentLogs = RescueLogger.logs.value
        assertEquals(4, currentLogs.size)
        assertEquals(LogLevel.INFO, currentLogs[0].level)
        assertEquals(LogLevel.SUCCESS, currentLogs[1].level)
        assertEquals(LogLevel.WARN, currentLogs[2].level)
        assertEquals(LogLevel.ERROR, currentLogs[3].level)
    }

    @Test
    fun testConnectivityStateDataModel() {
        val defaultState = com.example.core.ConnectivityState()
        assertEquals(false, defaultState.isUsbConnected)
        assertEquals(false, defaultState.isPowerConnected)
        assertEquals(false, defaultState.isAdbEnabled)
        assertEquals("Battery", defaultState.powerSource)

        val updatedState = defaultState.copy(
            isUsbConnected = true,
            isAdbEnabled = true,
            isPowerConnected = true,
            powerSource = "USB Port",
            batteryPercent = 88
        )
        assertTrue(updatedState.isUsbConnected)
        assertTrue(updatedState.isAdbEnabled)
        assertTrue(updatedState.isPowerConnected)
        assertEquals("USB Port", updatedState.powerSource)
        assertEquals(88, updatedState.batteryPercent)
    }
}
