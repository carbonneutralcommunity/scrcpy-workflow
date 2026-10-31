package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.RescueAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectivityState(
    val isUsbConnected: Boolean = false,
    val isPowerConnected: Boolean = false,
    val powerSource: String = "Battery",
    val batteryPercent: Int = -1,
    val isAdbEnabled: Boolean = false,
    val isDevOptionsEnabled: Boolean = false,
    val isAccessibilityActive: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

object ConnectivityMonitor {

    private val _state = MutableStateFlow(ConnectivityState())
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var isMonitoring = false
    private var receiver: BroadcastReceiver? = null
    private var settingsObserver: ContentObserver? = null

    fun startMonitoring(context: Context) {
        if (isMonitoring) {
            refresh(context)
            return
        }
        val appCtx = context.applicationContext
        appContext = appCtx
        isMonitoring = true

        // 1. Initial snapshot
        refresh(appCtx)

        // 2. Register dynamic receiver for USB & Battery state
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return

                var usbConnected = _state.value.isUsbConnected
                if (action == "android.hardware.usb.action.USB_STATE") {
                    usbConnected = intent.getBooleanExtra("connected", false)
                }

                evaluateBatteryAndPower(appCtx, usbOverride = if (action == "android.hardware.usb.action.USB_STATE") usbConnected else null)
            }
        }

        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }

        try {
            ContextCompat.registerReceiver(
                appCtx,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (t: Throwable) {
            RescueLogger.w("ConnectivityMonitor receiver error: ${t.message}")
        }

        // 3. Register ContentObserver for instant Settings changes (ADB & Dev Options)
        try {
            val handler = Handler(Looper.getMainLooper())
            settingsObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    refreshSettingsOnly(appCtx)
                }
            }

            val cr = appCtx.contentResolver
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false,
                settingsObserver!!
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
                false,
                settingsObserver!!
            )
        } catch (t: Throwable) {
            RescueLogger.w("Settings observer registration: ${t.message}")
        }
    }

    fun stopMonitoring() {
        val appCtx = appContext ?: return
        isMonitoring = false

        receiver?.let {
            try {
                appCtx.unregisterReceiver(it)
            } catch (ignored: Throwable) {}
            receiver = null
        }

        settingsObserver?.let {
            try {
                appCtx.contentResolver.unregisterContentObserver(it)
            } catch (ignored: Throwable) {}
            settingsObserver = null
        }
    }

    fun refresh(context: Context) {
        val appCtx = context.applicationContext
        evaluateBatteryAndPower(appCtx, null)
        refreshSettingsOnly(appCtx)
    }

    private fun refreshSettingsOnly(context: Context) {
        val cr = context.contentResolver
        val adb = try {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }

        val dev = try {
            Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }

        val accessibility = RescueAccessibilityService.isConnected()

        _state.value = _state.value.copy(
            isAdbEnabled = adb,
            isDevOptionsEnabled = dev,
            isAccessibilityActive = accessibility,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun evaluateBatteryAndPower(context: Context, usbOverride: Boolean?) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        // Inspect sticky battery intent for real-time status
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, iFilter)

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val wirelessPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS

        val sourceName = when {
            usbPlugged -> "USB Port"
            acPlugged -> "AC Wall Charger"
            wirelessPlugged -> "Wireless Pad"
            isCharging -> "External Power"
            else -> "Battery"
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val finalUsbConnected = usbOverride ?: (usbPlugged || _state.value.isUsbConnected)

        _state.value = _state.value.copy(
            isUsbConnected = finalUsbConnected,
            isPowerConnected = isCharging,
            powerSource = sourceName,
            batteryPercent = percent,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
