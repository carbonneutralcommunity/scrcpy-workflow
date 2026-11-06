package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.RescueAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConnectivityState(
    val isUsbConnected: Boolean = false,
    val isComputerUsb: Boolean = false,
    val isWallCharger: Boolean = false,
    val isPowerConnected: Boolean = false,
    val powerSource: String = "Battery",
    val batteryPercent: Int = -1,
    val usbConfigured: Boolean = false,
    val usbAdbActive: Boolean = false,
    val isPcFastCharging: Boolean = false,
    val detectionDetail: String = "Unplugged",
    val isAdbEnabled: Boolean = false,
    val isDevOptionsEnabled: Boolean = false,
    val isAccessibilityActive: Boolean = false,
    val isAdbTimeoutDisabled: Boolean = false,
    val hasWritePermission: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val fixCommand: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Monitors USB, battery, charging type, developer security settings, and battery optimization state.
 *
 * STEP 4 BACKGROUND RELIABILITY:
 * - Monitors BatteryManager, USB Gadget state, and PowerManager.isIgnoringBatteryOptimizations.
 * - Works across Android 10 (Q) through Android 16 (Baklava).
 * - Distinguishes AC Wall Chargers and Computer USB ports reliably.
 */
object ConnectivityMonitor {

    private val _state = MutableStateFlow(ConnectivityState())
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var isMonitoring = false
    private var receiver: BroadcastReceiver? = null
    private var settingsObserver: ContentObserver? = null
    private val monitorScope = CoroutineScope(Dispatchers.IO)

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
                var usbConfigured = _state.value.usbConfigured
                var usbAdb = _state.value.usbAdbActive

                if (action == "android.hardware.usb.action.USB_STATE") {
                    usbConnected = intent.getBooleanExtra("connected", false)
                    usbConfigured = intent.getBooleanExtra("configured", false)
                    usbAdb = intent.getBooleanExtra("adb", false)
                }

                evaluateBatteryAndPower(
                    appCtx,
                    usbOverride = if (action == "android.hardware.usb.action.USB_STATE") usbConnected else null,
                    configuredOverride = if (action == "android.hardware.usb.action.USB_STATE") usbConfigured else null,
                    adbOverride = if (action == "android.hardware.usb.action.USB_STATE") usbAdb else null
                )

                // If USB attached, ensure developer settings are preserved
                monitorScope.launch {
                    RescueEngine.autoRestoreSettingsIfSwitchedOff(appCtx)
                }
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

        // 3. Register ContentObserver for instant Settings changes (ADB, Dev Options, Accessibility, Timeout)
        try {
            val handler = Handler(Looper.getMainLooper())
            settingsObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    refreshSettingsOnly(appCtx)

                    // Auto-Switch Back ON if any of Developer Options, ADB, or Accessibility were toggled OFF
                    monitorScope.launch {
                        RescueEngine.autoRestoreSettingsIfSwitchedOff(appCtx)
                    }
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
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                settingsObserver!!
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                settingsObserver!!
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cr.registerContentObserver(
                    Settings.Global.getUriFor("adb_allowed_connection_time"),
                    false,
                    settingsObserver!!
                )
            }
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
        evaluateBatteryAndPower(appCtx, null, null, null)
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

        val hasPermission = RescueEngine.hasWriteSecureSettingsPermission(context)
        val fixCmd = RescueEngine.getFixCommand(context)

        // Check if our accessibility service is listed in ENABLED_ACCESSIBILITY_SERVICES
        val rawServices = try {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (e: Exception) {
            ""
        }
        val isServiceConfigured = rawServices.contains(context.packageName, ignoreCase = true)
        val accessibilityActive = RescueAccessibilityService.isConnected() || isServiceConfigured

        val adbTimeoutDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Settings.Global.getLong(cr, "adb_allowed_connection_time", -1L) == 0L
            } catch (e: Exception) {
                false
            }
        } else {
            true // Prior to Android 11, keys did not time out
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }

        _state.value = _state.value.copy(
            isAdbEnabled = adb,
            isDevOptionsEnabled = dev,
            isAccessibilityActive = accessibilityActive,
            isAdbTimeoutDisabled = adbTimeoutDisabled,
            hasWritePermission = hasPermission,
            isBatteryOptimizationIgnored = isBatteryOptIgnored,
            fixCommand = fixCmd,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun evaluateBatteryAndPower(
        context: Context,
        usbOverride: Boolean?,
        configuredOverride: Boolean?,
        adbOverride: Boolean?
    ) {
        // 1. Inspect sticky battery intent for real-time electrical status
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, iFilter)

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val rawUsbPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val rawAcPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val rawWirelessPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS

        // 2. Inspect sticky USB gadget intent for host enumeration
        val usbFilter = IntentFilter("android.hardware.usb.action.USB_STATE")
        val stickyUsbIntent: Intent? = context.registerReceiver(null, usbFilter)

        val usbConnected = usbOverride ?: (stickyUsbIntent?.getBooleanExtra("connected", false) ?: (rawUsbPlugged || _state.value.isUsbConnected))
        val usbConfigured = configuredOverride ?: (stickyUsbIntent?.getBooleanExtra("configured", false) ?: _state.value.usbConfigured)
        val usbAdb = adbOverride ?: (stickyUsbIntent?.getBooleanExtra("adb", false) ?: _state.value.usbAdbActive)

        // 3. Trustworthy Decision Logic:
        val isFastChargePcPort = rawAcPlugged && (usbConfigured || usbAdb)
        val isComputerHost = rawUsbPlugged || usbConfigured || usbAdb || isFastChargePcPort
        val isWall = (rawAcPlugged && !isComputerHost) || rawWirelessPlugged

        val sourceName = when {
            isFastChargePcPort -> "PC Host (Fast-Charge / USB-PD)"
            rawUsbPlugged -> "PC USB Port (Standard SDP)"
            isComputerHost -> "PC USB Link (Configured)"
            rawAcPlugged -> "AC Wall Charger"
            rawWirelessPlugged -> "Wireless Pad"
            isCharging -> "External Power"
            else -> "Battery"
        }

        val detectionInfo = when {
            isFastChargePcPort -> "PC Fast-Charge Port (Verified Host Enumeration)"
            isComputerHost -> "Verified PC Host (USB Configured=${usbConfigured}, ADB=${usbAdb})"
            isWall -> "AC Wall Charger (Ignored - Alarm Silenced)"
            else -> "Unplugged"
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        _state.value = _state.value.copy(
            isUsbConnected = usbConnected,
            isComputerUsb = isComputerHost,
            isWallCharger = isWall,
            isPowerConnected = isCharging,
            powerSource = sourceName,
            batteryPercent = percent,
            usbConfigured = usbConfigured,
            usbAdbActive = usbAdb,
            isPcFastCharging = isFastChargePcPort,
            detectionDetail = detectionInfo,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Trustworthy PC detection function:
     * Returns true ONLY if confirmed connected to a real computer USB host.
     * Rejects AC wall chargers, power banks, and wireless chargers.
     */
    fun isTrustworthyPcConnection(context: Context): Boolean {
        val usbFilter = IntentFilter("android.hardware.usb.action.USB_STATE")
        val usbIntent: Intent? = context.registerReceiver(null, usbFilter)
        val configured = usbIntent?.getBooleanExtra("configured", false) ?: false
        val adb = usbIntent?.getBooleanExtra("adb", false) ?: false

        val batFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batIntent: Intent? = context.registerReceiver(null, batFilter)
        val plug = batIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val isUsbPlug = plug == BatteryManager.BATTERY_PLUGGED_USB
        val isAcPlug = plug == BatteryManager.BATTERY_PLUGGED_AC

        return isUsbPlug || configured || adb || (isAcPlug && (configured || adb))
    }

    /**
     * Trustworthy Wall Charger check:
     * Returns true if connected to a wall AC charger, power bank, or wireless charger
     * without any PC USB host configuration.
     */
    fun isTrustworthyWallCharger(context: Context): Boolean {
        val batFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batIntent: Intent? = context.registerReceiver(null, batFilter)
        val plug = batIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        if (plug == BatteryManager.BATTERY_PLUGGED_WIRELESS) return true

        val usbFilter = IntentFilter("android.hardware.usb.action.USB_STATE")
        val usbIntent: Intent? = context.registerReceiver(null, usbFilter)
        val configured = usbIntent?.getBooleanExtra("configured", false) ?: false
        val adb = usbIntent?.getBooleanExtra("adb", false) ?: false

        val isAcPlug = plug == BatteryManager.BATTERY_PLUGGED_AC
        val isUsbPlug = plug == BatteryManager.BATTERY_PLUGGED_USB

        return isAcPlug && !configured && !adb && !isUsbPlug
    }

    fun isComputerUsbConnection(context: Context): Boolean = isTrustworthyPcConnection(context)
    fun isAcWallChargerConnection(context: Context): Boolean = isTrustworthyWallCharger(context)
}
