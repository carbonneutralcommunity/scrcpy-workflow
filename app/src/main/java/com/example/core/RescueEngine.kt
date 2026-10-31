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
    data class Error(val step: Int, val message: String, val isPermissionMissing: Boolean, val isAutoBlockerSuspected: Boolean) : RoutineState()
}

data class StepStatus(
    val step1DevOptions: Boolean = false,
    val step2AdbEnabled: Boolean = false,
    val step3AccessibilityEnabled: Boolean = false,
    val step4AllOkAlarm: Boolean = false,
    val isRunning: Boolean = false,
    val errorMessage: String? = null
)

object RescueEngine {

    private val _routineState = MutableStateFlow<RoutineState>(RoutineState.Idle)
    val routineState: StateFlow<RoutineState> = _routineState.asStateFlow()

    private val _stepStatus = MutableStateFlow(StepStatus())
    val stepStatus: StateFlow<StepStatus> = _stepStatus.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isExecuting = false
    private var lastExecutionTime = 0L

    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }

    fun executeRoutineAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (isExecuting || (now - lastExecutionTime < 2000L)) {
            return
        }
        coroutineScope.launch {
            isExecuting = true
            try {
                val result = executeRoutine(context.applicationContext)
                lastExecutionTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(result)
                }
            } finally {
                isExecuting = false
            }
        }
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

            RescueLogger.s("[TEST MODE] Step 3: Accessibility Auto-Allow Service = ACTIVE [VERIFIED]")
            _stepStatus.value = _stepStatus.value.copy(step3AccessibilityEnabled = true)
            delay(350)

            RescueLogger.s("==================================================")
            RescueLogger.s("[TEST MODE] ALL 3 SETTINGS VERIFIED OK!")
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

    suspend fun executeRoutine(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        RescueLogger.init(context)
        _routineState.value = RoutineState.Running
        _stepStatus.value = StepStatus(isRunning = true)

        val profile = DeviceProfileResolver.resolve()
        RescueLogger.i("==================================================")
        RescueLogger.i("Executing zero-touch scrcpy preparation routine...")
        RescueLogger.i("Device Profile: ${profile.name} (${Build.MANUFACTURER} ${Build.MODEL})")
        RescueLogger.i("==================================================")

        val hasPermission = hasWriteSecureSettingsPermission(context)
        RescueLogger.i("Permission android.permission.WRITE_SECURE_SETTINGS: ${if (hasPermission) "GRANTED" else "NOT GRANTED"}")

        if (!hasPermission) {
            val grantCommand = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            val errMsg = "One-time setup required: WRITE_SECURE_SETTINGS is not yet granted.\nRun once via PC terminal:\n$grantCommand"
            RescueLogger.w(errMsg)

            val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            if (isSamsung) {
                RescueLogger.w("Samsung One UI Note: Also ensure Auto Blocker is turned OFF in Settings > Security and privacy > Auto Blocker.")
            }

            _stepStatus.value = _stepStatus.value.copy(
                isRunning = false,
                errorMessage = errMsg
            )
            _routineState.value = RoutineState.Error(
                step = 0,
                message = errMsg,
                isPermissionMissing = true,
                isAutoBlockerSuspected = isSamsung
            )
            ConnectivityMonitor.refresh(context)
            return@withContext false
        }

        // ---------------------------------------------------------
        // STEP 1: Developer Options
        // ---------------------------------------------------------
        RescueLogger.i("[Step 1/3] Checking Settings.Global.DEVELOPMENT_SETTINGS_ENABLED...")
        var devEnabled = try {
            Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        } catch (e: Exception) {
            -1
        }

        if (devEnabled == 1) {
            RescueLogger.i("Developer options already ENABLED (value = 1). Skipping write.")
            _stepStatus.value = _stepStatus.value.copy(step1DevOptions = true)
        } else {
            RescueLogger.i("Developer options currently OFF (value = $devEnabled). Enabling via putInt...")
            try {
                val success = Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                val verified = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
                if (verified == 1) {
                    RescueLogger.s("Developer options successfully enabled and verified! (write result = $success, verified = 1)")
                    _stepStatus.value = _stepStatus.value.copy(step1DevOptions = true)
                } else {
                    return@withContext handleStepFailure(context, 1, "Failed to verify DEVELOPMENT_SETTINGS_ENABLED (read back $verified).")
                }
            } catch (t: Throwable) {
                return@withContext handleStepFailure(context, 1, "Exception writing DEVELOPMENT_SETTINGS_ENABLED: ${t.message}")
            }
        }

        // ---------------------------------------------------------
        // STEP 2: USB Debugging (ADB_ENABLED)
        // ---------------------------------------------------------
        RescueLogger.i("[Step 2/3] Checking Settings.Global.ADB_ENABLED...")
        var adbEnabled = try {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
        } catch (e: Exception) {
            -1
        }

        if (adbEnabled == 1) {
            RescueLogger.i("USB debugging already ENABLED (value = 1). Skipping write.")
            _stepStatus.value = _stepStatus.value.copy(step2AdbEnabled = true)
        } else {
            RescueLogger.i("USB debugging currently OFF (value = $adbEnabled). Enabling via putInt...")
            try {
                val success = Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                val verified = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
                if (verified == 1) {
                    RescueLogger.s("USB debugging successfully enabled and verified! (write result = $success, verified = 1)")
                    _stepStatus.value = _stepStatus.value.copy(step2AdbEnabled = true)
                } else {
                    return@withContext handleStepFailure(context, 2, "Failed to verify ADB_ENABLED (read back $verified).")
                }
            } catch (t: Throwable) {
                return@withContext handleStepFailure(context, 2, "Exception writing ADB_ENABLED: ${t.message}")
            }
        }

        // ---------------------------------------------------------
        // STEP 3: Accessibility Service
        // ---------------------------------------------------------
        RescueLogger.i("[Step 3/3] Checking Accessibility Service registration...")
        val serviceComponent = ComponentName(context, RescueAccessibilityService::class.java)
        val componentFlat = serviceComponent.flattenToString()
        val componentShort = serviceComponent.flattenToShortString()

        try {
            val enabledServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val isServiceActive = enabledServices.split(":").map { it.trim() }.any {
                it.equals(componentFlat, ignoreCase = true) || it.equals(componentShort, ignoreCase = true)
            }

            if (!isServiceActive) {
                RescueLogger.i("Service not yet in ENABLED_ACCESSIBILITY_SERVICES. Appending '$componentFlat'...")
                val updatedServices = if (enabledServices.isBlank()) componentFlat else "$enabledServices:$componentFlat"
                val writeRes = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updatedServices)
                RescueLogger.i("Writing ENABLED_ACCESSIBILITY_SERVICES result: $writeRes")
            } else {
                RescueLogger.i("Service already present in ENABLED_ACCESSIBILITY_SERVICES.")
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
        // STEP 4: All steps OK!
        // ---------------------------------------------------------
        RescueLogger.s("==================================================")
        RescueLogger.s("ALL 3 SETTINGS VERIFIED OK!")
        RescueLogger.s("Triggering 10s alarm audio and 10s vibration feedback...")
        RescueLogger.s("==================================================")

        _stepStatus.value = _stepStatus.value.copy(
            isRunning = false,
            step4AllOkAlarm = true
        )
        _routineState.value = RoutineState.Success

        // Play loud alarm and vibrate for 10 seconds
        HapticAndSoundFeedback.playSuccessFeedback(context)

        // Refresh live connectivity monitor with new ADB and settings state
        ConnectivityMonitor.refresh(context)

        // Start 60s dialog scan session for the USB debugging prompt
        RescueAccessibilityService.startDialogScan(60_000L)

        return@withContext true
    }

    private fun handleStepFailure(context: Context, step: Int, message: String): Boolean {
        ConnectivityMonitor.refresh(context)
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val fullMsg = buildString {
            append("Step $step FAILED: $message\n")
            if (isSamsung) {
                append("CRITICAL SAMSUNG NOTICE: One UI Auto Blocker actively blocks USB settings and commands!\n")
                append("Disable Auto Blocker in: Settings > Security and privacy > Auto Blocker (toggle OFF).")
            }
        }

        RescueLogger.e(fullMsg)
        _stepStatus.value = _stepStatus.value.copy(
            isRunning = false,
            errorMessage = fullMsg
        )
        _routineState.value = RoutineState.Error(
            step = step,
            message = fullMsg,
            isPermissionMissing = false,
            isAutoBlockerSuspected = isSamsung
        )

        // Step 5: A step failed -> 3 short vibrations
        HapticAndSoundFeedback.playErrorFeedback(context)
        return false
    }
}
