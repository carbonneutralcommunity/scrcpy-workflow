package com.example.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.model.DeviceProfileResolver
import com.example.service.RescueAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class RoutineState {
    object Idle : RoutineState()
    object Running : RoutineState()
    object Success : RoutineState()
    data class Error(
        val step: Int,
        val message: String,
        val isPermissionMissing: Boolean,
        val isAutoBlockerSuspected: Boolean,
        val fixCommand: String? = null
    ) : RoutineState()
}

data class StepStatus(
    val step1DevOptions: Boolean = false,
    val step1WasAlreadyOn: Boolean = false,
    val step2AdbEnabled: Boolean = false,
    val step2WasAlreadyOn: Boolean = false,
    val step3AccessibilityEnabled: Boolean = false,
    val step4AllOkAlarm: Boolean = false,
    val isAdbTimeoutDisabled: Boolean = false,
    val isRunning: Boolean = false,
    val errorMessage: String? = null,
    val fixCommand: String? = null
)

object RescueEngine {

    private val _routineState = MutableStateFlow<RoutineState>(RoutineState.Idle)
    val routineState: StateFlow<RoutineState> = _routineState.asStateFlow()

    private val _stepStatus = MutableStateFlow(StepStatus())
    val stepStatus: StateFlow<StepStatus> = _stepStatus.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isExecuting = false
    private var lastExecutionTime = 0L

    fun getFixCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }

    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }

    fun executeRoutineAsync(
        context: Context,
        triggerAlarmOnSuccess: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        if (isExecuting || (now - lastExecutionTime < 1500L)) {
            return
        }
        coroutineScope.launch {
            isExecuting = true
            try {
                val result = executeRoutine(context.applicationContext, triggerAlarmOnSuccess)
                lastExecutionTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(result)
                }
            } finally {
                isExecuting = false
            }
        }
    }

    /**
     * Auto-Restore Watchdog:
     * If Developer Options, USB Debugging, or the Accessibility Service are switched off
     * by the user, an OEM background killer, or a system timeout, this function
     * automatically switches them back ON without overwriting other accessibility services.
     */
    suspend fun autoRestoreSettingsIfSwitchedOff(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasWriteSecureSettingsPermission(context)) {
            val fixCmd = getFixCommand(context)
            RescueLogger.w("Auto-Watchdog: Missing WRITE_SECURE_SETTINGS.\nFix command for PC:\n$fixCmd")
            return@withContext false
        }

        val cr = context.contentResolver
        var restoredAny = false

        // 1. Check & restore Developer Options
        try {
            val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            if (dev != 1) {
                Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                RescueLogger.s("Auto-Watchdog: Developer Options was switched OFF! Automatically restored back to ON (1).")
                restoredAny = true
            }
        } catch (e: Exception) {
            RescueLogger.e("Auto-Watchdog Developer Options restore failed: ${e.message}")
        }

        // 2. Check & restore USB Debugging (ADB)
        try {
            val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
            if (adb != 1) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                RescueLogger.s("Auto-Watchdog: USB Debugging was switched OFF! Automatically restored back to ON (1).")
                restoredAny = true
            }
        } catch (e: Exception) {
            RescueLogger.e("Auto-Watchdog ADB restore failed: ${e.message}")
        }

        // 3. Check & restore Accessibility Service (PRESERVING other enabled services)
        try {
            val serviceComponent = ComponentName(context, RescueAccessibilityService::class.java)
            val componentFlat = serviceComponent.flattenToString()
            val componentShort = serviceComponent.flattenToShortString()

            val rawServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val servicesList = rawServices.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            val isAlreadyEnabled = servicesList.any {
                it.equals(componentFlat, ignoreCase = true) || it.equals(componentShort, ignoreCase = true)
            }

            if (!isAlreadyEnabled) {
                servicesList.add(componentFlat)
                val updated = servicesList.joinToString(":")
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                RescueLogger.s("Auto-Watchdog: Accessibility Service was switched OFF! Restored to ON while preserving ${servicesList.size - 1} other active accessibility service(s).")
                restoredAny = true
            }

            val master = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (master != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                RescueLogger.s("Auto-Watchdog: Master ACCESSIBILITY_ENABLED was 0. Restored to 1.")
                restoredAny = true
            }
        } catch (e: Exception) {
            RescueLogger.e("Auto-Watchdog Accessibility restore failed: ${e.message}")
        }

        // 4. Check & restore ADB authorization timeout (Android 11+ / API 30+)
        // Prevents Android from revoking "always allow" authorization after a few days
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val timeout = Settings.Global.getLong(cr, "adb_allowed_connection_time", -1L)
                if (timeout != 0L) {
                    Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                    RescueLogger.s("Auto-Watchdog: ADB authorization timeout was reset. Automatically set back to 0 (Disabled) so 'always allow' never expires.")
                    restoredAny = true
                }
            } catch (ignored: Throwable) {}
        }

        if (restoredAny) {
            ConnectivityMonitor.refresh(context)
        }
        return@withContext restoredAny
    }

    fun simulateSuccessRoutine(context: Context) {
        coroutineScope.launch {
            _routineState.value = RoutineState.Running
            _stepStatus.value = StepStatus(isRunning = true)
            RescueLogger.i("[TEST MODE] Simulating zero-touch scrcpy routine...")
            delay(350)

            RescueLogger.s("[TEST MODE] Step 1: DEVELOPMENT_SETTINGS_ENABLED = 1 [VERIFIED]")
            _stepStatus.value = _stepStatus.value.copy(step1DevOptions = true)
            delay(350)

            RescueLogger.s("[TEST MODE] Step 2: ADB_ENABLED = 1 [VERIFIED]")
            _stepStatus.value = _stepStatus.value.copy(step2AdbEnabled = true)
            delay(350)

            RescueLogger.s("[TEST MODE] Step 3: Accessibility Auto-Allow Service = ACTIVE [VERIFIED] (Other services preserved)")
            _stepStatus.value = _stepStatus.value.copy(step3AccessibilityEnabled = true)
            delay(350)

            RescueLogger.s("[TEST MODE] Step 4: ADB authorization timeout disabled (0 ms). 'Always allow' will never expire.")
            _stepStatus.value = _stepStatus.value.copy(isAdbTimeoutDisabled = true)
            delay(350)

            RescueLogger.s("==================================================")
            RescueLogger.s("[TEST MODE] ALL SETTINGS VERIFIED & ARMED!")
            RescueLogger.s("[TEST MODE] Triggering 10s Alarm Audio & 10s Vibration...")
            RescueLogger.s("==================================================")

            _stepStatus.value = _stepStatus.value.copy(isRunning = false, step4AllOkAlarm = true)
            _routineState.value = RoutineState.Success

            withContext(Dispatchers.Main) {
                ConnectivityMonitor.refresh(context)
                HapticAndSoundFeedback.playSuccessFeedback(context)
                RescueAccessibilityService.startDialogScan(60_000L)
            }
        }
    }

    fun simulateDialogDetected(context: Context) {
        RescueLogger.s("[TEST MODE] Found 'Allow USB debugging?' dialog!")
        RescueLogger.i("[TEST MODE] Layer 1: Found viewId 'android:id/alwaysUse'. Ticking checkbox...")
        RescueLogger.s("[TEST MODE] Ticked 'Always allow from this computer'.")
        RescueLogger.i("[TEST MODE] Layer 1: Found viewId 'android:id/button1'. Tapping Allow button...")
        RescueLogger.s("[TEST MODE] Successfully tapped 'Allow' button!")
        RescueLogger.s("Zero-touch authorization complete! Ready for wired scrcpy connection.")
        HapticAndSoundFeedback.playDialogConfirmedFeedback(context)
    }

    suspend fun executeRoutine(context: Context, triggerAlarmOnSuccess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        RescueLogger.init(context)
        _routineState.value = RoutineState.Running
        _stepStatus.value = StepStatus(isRunning = true)

        val profile = DeviceProfileResolver.resolve()
        RescueLogger.i("==================================================")
        RescueLogger.i("Executing zero-touch scrcpy preparation routine...")
        RescueLogger.i("Device Profile: ${profile.name} (${Build.MANUFACTURER} ${Build.MODEL})")
        RescueLogger.i("Mode: ${if (triggerAlarmOnSuccess) "Computer USB Triggered (Alarm ON)" else "Silent Verification (Alarm OFF)"}")
        RescueLogger.i("==================================================")

        val hasPermission = hasWriteSecureSettingsPermission(context)
        RescueLogger.i("Permission android.permission.WRITE_SECURE_SETTINGS: ${if (hasPermission) "GRANTED" else "NOT GRANTED"}")

        if (!hasPermission) {
            val grantCommand = getFixCommand(context)
            val errMsg = "One-time setup required: WRITE_SECURE_SETTINGS is not yet granted.\nRun once via PC terminal:\n$grantCommand"
            RescueLogger.w(errMsg)

            val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            if (isSamsung) {
                RescueLogger.w("Samsung One UI Note: Also ensure Auto Blocker is turned OFF in Settings > Security and privacy > Auto Blocker.")
            }

            _stepStatus.value = _stepStatus.value.copy(
                isRunning = false,
                errorMessage = errMsg,
                fixCommand = grantCommand
            )
            _routineState.value = RoutineState.Error(
                step = 0,
                message = errMsg,
                isPermissionMissing = true,
                isAutoBlockerSuspected = isSamsung,
                fixCommand = grantCommand
            )
            ConnectivityMonitor.refresh(context)
            return@withContext false
        }

        // ---------------------------------------------------------
        // STEP 1: Developer Options (Touch ONLY if turned OFF)
        // ---------------------------------------------------------
        RescueLogger.i("[Step 1/4] Checking Settings.Global.DEVELOPMENT_SETTINGS_ENABLED...")
        var devEnabled = try {
            Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        } catch (e: Exception) {
            -1
        }

        if (devEnabled == 1) {
            RescueLogger.i("Developer options: Already TURNED ON (value = 1). Leaving untouched.")
            _stepStatus.value = _stepStatus.value.copy(step1DevOptions = true, step1WasAlreadyOn = true)
        } else {
            RescueLogger.i("Developer options: Currently OFF (value = $devEnabled). Enabling only because it was turned off...")
            try {
                val success = Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                val verified = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
                if (verified == 1) {
                    RescueLogger.s("Developer options was OFF -> Successfully turned ON and verified! (write result = $success, verified = 1)")
                    _stepStatus.value = _stepStatus.value.copy(step1DevOptions = true, step1WasAlreadyOn = false)
                } else {
                    return@withContext handleStepFailure(context, 1, "Failed to verify DEVELOPMENT_SETTINGS_ENABLED (read back $verified).")
                }
            } catch (t: Throwable) {
                return@withContext handleStepFailure(context, 1, "Exception writing DEVELOPMENT_SETTINGS_ENABLED: ${t.message}")
            }
        }

        // ---------------------------------------------------------
        // STEP 2: USB Debugging (ADB_ENABLED) (Touch ONLY if turned OFF)
        // ---------------------------------------------------------
        RescueLogger.i("[Step 2/4] Checking Settings.Global.ADB_ENABLED...")
        var adbEnabled = try {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
        } catch (e: Exception) {
            -1
        }

        if (adbEnabled == 1) {
            RescueLogger.i("USB debugging: Already TURNED ON (value = 1). Leaving untouched.")
            _stepStatus.value = _stepStatus.value.copy(step2AdbEnabled = true, step2WasAlreadyOn = true)
        } else {
            RescueLogger.i("USB debugging: Currently OFF (value = $adbEnabled). Enabling only because it was turned off...")
            try {
                val success = Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                val verified = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
                if (verified == 1) {
                    RescueLogger.s("USB debugging was OFF -> Successfully turned ON and verified! (write result = $success, verified = 1)")
                    _stepStatus.value = _stepStatus.value.copy(step2AdbEnabled = true, step2WasAlreadyOn = false)
                } else {
                    return@withContext handleStepFailure(context, 2, "Failed to verify ADB_ENABLED (read back $verified).")
                }
            } catch (t: Throwable) {
                return@withContext handleStepFailure(context, 2, "Exception writing ADB_ENABLED: ${t.message}")
            }
        }

        // ---------------------------------------------------------
        // STEP 3: Accessibility Service (Preserving all other services)
        // ---------------------------------------------------------
        RescueLogger.i("[Step 3/4] Checking Accessibility Service registration...")
        val serviceComponent = ComponentName(context, RescueAccessibilityService::class.java)
        val componentFlat = serviceComponent.flattenToString()
        val componentShort = serviceComponent.flattenToShortString()

        try {
            val rawServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val currentList = rawServices.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            val isServiceActive = currentList.any {
                it.equals(componentFlat, ignoreCase = true) || it.equals(componentShort, ignoreCase = true)
            }

            if (!isServiceActive) {
                RescueLogger.i("Service not yet in ENABLED_ACCESSIBILITY_SERVICES. Preserving ${currentList.size} existing service(s) and adding '$componentFlat'...")
                currentList.add(componentFlat)
                val updatedServices = currentList.joinToString(":")
                val writeRes = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updatedServices)
                RescueLogger.i("Writing ENABLED_ACCESSIBILITY_SERVICES result: $writeRes (Preserved other accessibility services)")
            } else {
                RescueLogger.i("Service already present in ENABLED_ACCESSIBILITY_SERVICES (${currentList.size} total services active).")
            }

            // Ensure master switch ACCESSIBILITY_ENABLED is 1
            val masterEnabled = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (masterEnabled != 1) {
                RescueLogger.i("ACCESSIBILITY_ENABLED is $masterEnabled. Setting to 1...")
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }

            // Read back to verify
            val verifiedServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val verifiedMaster = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            val serviceVerified = verifiedServices.split(":").map { it.trim() }.any {
                it.equals(componentFlat, ignoreCase = true) || it.equals(componentShort, ignoreCase = true)
            }

            if (serviceVerified && verifiedMaster == 1) {
                RescueLogger.s("Accessibility service enabled and verified in Settings.Secure! (Services = '$verifiedServices', Master = 1)")
                _stepStatus.value = _stepStatus.value.copy(step3AccessibilityEnabled = true)
            } else {
                return@withContext handleStepFailure(
                    context,
                    3,
                    "Failed to verify Accessibility Service in Settings.Secure (verifiedMaster=$verifiedMaster, foundInList=$serviceVerified)."
                )
            }
        } catch (t: Throwable) {
            return@withContext handleStepFailure(context, 3, "Exception configuring Accessibility Service: ${t.message}")
        }

        // ---------------------------------------------------------
        // STEP 4: Prevent "Always Allow" Expiration (Disable ADB timeout on Android 11+)
        // ---------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            RescueLogger.i("[Step 4/4] Configuring ADB authorization timeout (adb_allowed_connection_time)...")
            try {
                val currentTimeout = Settings.Global.getLong(cr, "adb_allowed_connection_time", -1L)
                if (currentTimeout != 0L) {
                    val putOk = Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                    RescueLogger.s("Disabled ADB authorization timeout! 'Always allow from this computer' will never expire after a few days (putOk=$putOk).")
                } else {
                    RescueLogger.i("ADB authorization timeout is already disabled (0 ms). 'Always allow' will never expire.")
                }
                _stepStatus.value = _stepStatus.value.copy(isAdbTimeoutDisabled = true)
            } catch (t: Throwable) {
                RescueLogger.w("Could not write adb_allowed_connection_time: ${t.message}")
            }
        } else {
            RescueLogger.i("[Step 4/4] Android version ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) uses permanent RSA keys by default without timeout.")
            _stepStatus.value = _stepStatus.value.copy(isAdbTimeoutDisabled = true)
        }

        // ---------------------------------------------------------
        // ALL STEPS OK
        // ---------------------------------------------------------
        RescueLogger.s("==================================================")
        RescueLogger.s("ALL SETTINGS VERIFIED OK & PROTECTED BY AUTO-WATCHDOG!")
        if (triggerAlarmOnSuccess) {
            RescueLogger.s("COMPUTER USB CONNECTED: Triggering 10s alarm audio and 10s vibration feedback...")
        } else {
            RescueLogger.s("Silent check complete (Alarm will only sound when Computer USB is plugged in).")
        }
        RescueLogger.s("==================================================")

        _stepStatus.value = _stepStatus.value.copy(
            isRunning = false,
            step4AllOkAlarm = triggerAlarmOnSuccess
        )
        _routineState.value = RoutineState.Success

        if (triggerAlarmOnSuccess) {
            // Play loud alarm and vibrate for 10 seconds
            HapticAndSoundFeedback.playSuccessFeedback(context)
        }

        // Refresh live connectivity monitor with new ADB and settings state
        ConnectivityMonitor.refresh(context)

        // Start 60s dialog scan session for the USB debugging prompt
        RescueAccessibilityService.startDialogScan(60_000L)

        return@withContext true
    }

    private fun handleStepFailure(context: Context, step: Int, message: String): Boolean {
        ConnectivityMonitor.refresh(context)
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val grantCmd = getFixCommand(context)
        val fullMsg = buildString {
            append("Step $step FAILED: $message\n")
            if (isSamsung) {
                append("CRITICAL SAMSUNG NOTICE: One UI Auto Blocker actively blocks USB settings and commands!\n")
                append("Disable Auto Blocker in: Settings > Security and privacy > Auto Blocker (toggle OFF).\n")
            }
            append("Command to fix permission:\n$grantCmd")
        }

        RescueLogger.e(fullMsg)
        _stepStatus.value = _stepStatus.value.copy(
            isRunning = false,
            errorMessage = fullMsg,
            fixCommand = grantCmd
        )
        _routineState.value = RoutineState.Error(
            step = step,
            message = fullMsg,
            isPermissionMissing = false,
            isAutoBlockerSuspected = isSamsung,
            fixCommand = grantCmd
        )

        // A step failed -> 3 short vibrations
        HapticAndSoundFeedback.playErrorFeedback(context)
        return false
    }
}
