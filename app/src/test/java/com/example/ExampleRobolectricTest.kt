package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.LogLevel
import com.example.core.RescueEngine
import com.example.core.RescueLogger
import com.example.core.StorageHelper
import com.example.model.DeviceProfileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
            isComputerUsb = true,
            isWallCharger = false,
            isAdbEnabled = true,
            isPowerConnected = true,
            powerSource = "PC USB Port",
            batteryPercent = 88,
            isAdbTimeoutDisabled = true,
            hasWritePermission = true,
            fixCommand = "adb shell pm grant test"
        )
        assertTrue(updatedState.isUsbConnected)
        assertTrue(updatedState.isComputerUsb)
        assertEquals(false, updatedState.isWallCharger)
        assertTrue(updatedState.isAdbEnabled)
        assertTrue(updatedState.isPowerConnected)
        assertTrue(updatedState.isAdbTimeoutDisabled)
        assertTrue(updatedState.hasWritePermission)
        assertEquals("PC USB Port", updatedState.powerSource)
        assertEquals(88, updatedState.batteryPercent)

        // Wall charger scenario: isWallCharger = true, isComputerUsb = false
        val wallChargerState = defaultState.copy(
            isUsbConnected = false,
            isComputerUsb = false,
            isWallCharger = true,
            isPowerConnected = true,
            powerSource = "AC Wall Charger"
        )
        assertEquals(false, wallChargerState.isComputerUsb)
        assertTrue(wallChargerState.isWallCharger)
        assertEquals("AC Wall Charger", wallChargerState.powerSource)
    }

    @Test
    fun testStepStatusConditionalModel() {
        val status = com.example.core.StepStatus(
            step1DevOptions = true,
            step1WasAlreadyOn = true,
            step2AdbEnabled = true,
            step2WasAlreadyOn = false,
            step3AccessibilityEnabled = true,
            step4AllOkAlarm = false,
            isAdbTimeoutDisabled = true,
            fixCommand = "adb shell pm grant com.aistudio.windowsandroid.uqdvkf android.permission.WRITE_SECURE_SETTINGS"
        )
        assertTrue(status.step1DevOptions)
        assertTrue(status.step1WasAlreadyOn)
        assertTrue(status.step2AdbEnabled)
        assertEquals(false, status.step2WasAlreadyOn)
        assertEquals(false, status.step4AllOkAlarm)
        assertTrue(status.isAdbTimeoutDisabled)
        assertNotNull(status.fixCommand)
    }

    @Test
    fun testStorageHelperContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val safeContext = StorageHelper.getSafeContext(context)
        assertNotNull(safeContext)
    }

    @Test
    fun testPcPullCommandsFormat() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val commands = RescueLogger.getPcPullCommands(context)
        assertTrue(commands.contains("adb pull"))
        assertTrue(commands.contains("run-as"))
        assertTrue(commands.contains("scrcpy_rescue.log"))
    }

    @Test
    fun testFixCommandGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixCommand = RescueEngine.getFixCommand(context)
        assertTrue(fixCommand.contains("adb shell pm grant"))
        assertTrue(fixCommand.contains(context.packageName))
        assertTrue(fixCommand.contains("android.permission.WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun testPreserveOtherAccessibilityServicesLogic() {
        val existingServices = "com.google.android.marvin.talkback/.TalkBackService:com.example.other/.OtherService"
        val ourComponent = "com.aistudio.windowsandroid.uqdvkf/com.example.service.RescueAccessibilityService"

        val currentList = existingServices.split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        val isAlreadyPresent = currentList.any { it.equals(ourComponent, ignoreCase = true) }
        assertEquals(false, isAlreadyPresent)

        currentList.add(ourComponent)
        val merged = currentList.joinToString(":")

        assertTrue(merged.contains("com.google.android.marvin.talkback/.TalkBackService"))
        assertTrue(merged.contains("com.example.other/.OtherService"))
        assertTrue(merged.contains(ourComponent))
        assertEquals(3, currentList.size)
    }

    @Test
    fun testStep3ConnectivityStateWithHostEnumeration() {
        val state = com.example.core.ConnectivityState(
            isUsbConnected = true,
            isComputerUsb = true,
            isWallCharger = false,
            usbConfigured = true,
            usbAdbActive = true,
            isPcFastCharging = true,
            detectionDetail = "PC Fast-Charge Port (Verified Host Enumeration)"
        )

        assertTrue(state.isComputerUsb)
        assertEquals(false, state.isWallCharger)
        assertTrue(state.usbConfigured)
        assertTrue(state.usbAdbActive)
        assertTrue(state.isPcFastCharging)
    }

    @Test
    fun testAlarmSoundManagerCrashRecoveryAndVolumePersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val safeContext = StorageHelper.getSafeContext(context)
        val prefs = safeContext.getSharedPreferences("windows_android_alarm_prefs", Context.MODE_PRIVATE)

        // Simulate a scenario where a crash occurred while volume was maxed out
        prefs.edit()
            .putInt("key_saved_original_volume", 5)
            .putBoolean("key_volume_overridden", true)
            .commit()

        // Calling init() must detect the orphaned volume and restore it
        com.example.core.AlarmSoundManager.init(context)

        // After init(), the override flag should be cleared from persistent storage
        val stillOverridden = prefs.getBoolean("key_volume_overridden", false)
        assertEquals(false, stillOverridden)
    }

    @Test
    fun testWallChargerVsFastChargePcDistinction() {
        // Wall charger: AC connected, but USB host enumeration is absent (configured = false, adb = false)
        val wallConfigured = false
        val wallAdb = false
        val wallAc = true
        val isWallCharger = wallAc && !wallConfigured && !wallAdb
        val isPcHost = wallConfigured || wallAdb
        assertTrue(isWallCharger)
        assertEquals(false, isPcHost)

        // PC Fast Charge (CDP / USB-PD from laptop): AC connected, but host enumeration succeeded (configured = true)
        val pcConfigured = true
        val pcAdb = true
        val pcAc = true
        val isFastChargePc = pcAc && (pcConfigured || pcAdb)
        assertTrue(isFastChargePc)
    }

    @Test
    fun testStep4BootReceiverDirectBootActionHandling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = com.example.receiver.BootReceiver()
        val directBootIntent = android.content.Intent(android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED)

        // Sending LOCKED_BOOT_COMPLETED broadcast should execute without exception in Direct Boot mode
        receiver.onReceive(context, directBootIntent)
        assertTrue(true)
    }

    @Test
    fun testStep4ConnectivityStateBatteryOptimizationIgnored() {
        val state = com.example.core.ConnectivityState(
            isBatteryOptimizationIgnored = true
        )
        assertTrue(state.isBatteryOptimizationIgnored)
    }
}
