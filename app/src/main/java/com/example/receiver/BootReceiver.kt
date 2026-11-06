package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.AlarmSoundManager
import com.example.core.RescueEngine
import com.example.core.RescueLogger
import com.example.service.RescueForegroundService

/**
 * BootReceiver triggers automatically upon device restart on all Android versions:
 * 1. ACTION_LOCKED_BOOT_COMPLETED: Fired when phone finishes booting in Direct Boot mode,
 *    BEFORE the user enters their PIN, password, or pattern.
 * 2. ACTION_BOOT_COMPLETED: Fired after the user unlocks the device.
 * 3. ACTION_MY_PACKAGE_REPLACED: Fired when the app is updated or reinstalled.
 * 4. QUICKBOOT_POWERON & REBOOT: Fired on fast-boot devices (Xiaomi, HTC, OnePlus, etc.).
 *
 * This ensures the broken-screen rescue system is armed and ready immediately on reboot
 * without requiring the user to touch or unlock the phone first!
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        RescueLogger.init(context)
        RescueLogger.i("BootReceiver received action: $action [Direct Boot Aware]")

        val isBootAction = Intent.ACTION_LOCKED_BOOT_COMPLETED == action ||
                Intent.ACTION_BOOT_COMPLETED == action ||
                Intent.ACTION_MY_PACKAGE_REPLACED == action ||
                Intent.ACTION_REBOOT == action ||
                "android.intent.action.QUICKBOOT_POWERON" == action ||
                "com.htc.intent.action.QUICKBOOT_POWERON" == action

        if (isBootAction) {
            RescueLogger.s("Device boot detected ($action)! Starting background recovery system before/after unlock...")

            // 1. Recover any interrupted alarm volume state
            try {
                AlarmSoundManager.init(context)
            } catch (t: Throwable) {
                RescueLogger.w("Boot volume recovery check: ${t.message}")
            }

            // 2. Start Foreground Service (exempt from background start restrictions during boot)
            try {
                RescueForegroundService.startService(context)
            } catch (t: Throwable) {
                RescueLogger.e("BootReceiver could not start foreground service: ${t.message}")
            }

            // 3. Silently verify and auto-restore developer options, ADB, and accessibility service
            try {
                RescueEngine.executeRoutineAsync(context, triggerAlarmOnSuccess = false)
            } catch (t: Throwable) {
                RescueLogger.w("Boot routine trigger: ${t.message}")
            }
        }
    }
}
