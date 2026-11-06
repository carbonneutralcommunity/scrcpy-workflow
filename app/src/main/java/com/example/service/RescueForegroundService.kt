package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.START_STICKY
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.core.AlarmSoundManager
import com.example.core.ConnectivityMonitor
import com.example.core.HapticAndSoundFeedback
import com.example.core.RescueEngine
import com.example.core.RescueLogger
import com.example.core.StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps zero-touch recovery active 24/7.
 *
 * STEP 4 BACKGROUND RELIABILITY & SCREEN WAKE:
 * 1. Works on Android 10 (Q) through Android 16 (Baklava):
 *    - Declares and starts with FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE and SPECIAL_USE.
 *    - Immediately invokes startForeground() in onCreate() to prevent system timeouts.
 * 2. Starts by itself after a reboot, even before unlock:
 *    - android:directBootAware="true" + LOCKED_BOOT_COMPLETED.
 *    - Operates inside Device-Protected (DE) Storage prior to credential decryption.
 * 3. Wakes the screen when the PC connects:
 *    - Acquires PowerManager SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP.
 *    - Dispatches a high-priority Full-Screen Intent notification (USE_FULL_SCREEN_INTENT)
 *      which lights up the display and presents the recovery interface over the lockscreen.
 * 4. Avoids being killed by battery saving:
 *    - START_STICKY process priority.
 *    - onTaskRemoved() automatically reschedules the service via AlarmManager if swiped away.
 *    - Holds a continuous PartialWakeLock to prevent CPU sleep during Doze mode.
 *    - Monitors PowerManager.isIgnoringBatteryOptimizations.
 */
class RescueForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "rescue_foreground_service_channel"
        const val WAKE_CHANNEL_ID = "rescue_pc_wake_channel"
        const val NOTIFICATION_ID = 1001
        const val WAKE_NOTIFICATION_ID = 1002
        const val ACTION_START_ROUTINE = "com.example.action.START_ROUTINE"

        private const val SESSION_PREFS = "rescue_session_state"
        private const val KEY_LAST_PC_CONNECTED = "key_last_pc_connected"

        fun startService(context: Context) {
            try {
                val intent = Intent(context, RescueForegroundService::class.java).apply {
                    action = ACTION_START_ROUTINE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                RescueLogger.w("Unable to start foreground service: ${t.message}")
            }
        }
    }

    private var partialWakeLock: PowerManager.WakeLock? = null
    private var usbReceiver: BroadcastReceiver? = null

    // Session-based connection latch to prevent repeat alarms and restart triggers
    private var isPcCurrentlyConnected = false
    private var hasAlarmSoundedForCurrentConnection = false

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        RescueLogger.init(this)
        RescueLogger.i("RescueForegroundService onCreate() [Direct Boot Aware - Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}]")

        // 1. Immediately create notification channels and call startForeground() first
        createNotificationChannels()
        startForegroundSafely()

        // 2. Initialize AlarmSoundManager (performs crash recovery if prior process was killed)
        AlarmSoundManager.init(this)

        // 3. Initialize connection session latch from current hardware state and persistent DE storage
        initConnectionSessionLatch()

        // 4. Acquire WakeLock to keep CPU active even if screen goes dark or during Doze mode
        acquireCpuWakeLock()

        // 5. Dynamically register USB and Power state receivers
        registerDynamicReceivers()

        // 6. Start active auto-healing background watchdog (verifies & restores settings every 8s)
        startAutoHealingWatchdog()
    }

    /**
     * Initializes the connection session latch on service startup.
     * If the phone is ALREADY connected to a PC when the service starts or restarts,
     * the alarm is marked as ALREADY SOUNDED so it never screams on app restart!
     */
    private fun initConnectionSessionLatch() {
        val safeContext = StorageHelper.getSafeContext(this)
        val prefs = safeContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

        val isNowPc = ConnectivityMonitor.isTrustworthyPcConnection(this)

        if (isNowPc) {
            isPcCurrentlyConnected = true
            hasAlarmSoundedForCurrentConnection = true
            prefs.edit().putBoolean(KEY_LAST_PC_CONNECTED, true).apply()
            RescueLogger.i("Startup Check: Phone is ALREADY plugged into a verified PC host. Redundant alarm suppressed for this ongoing session.")
        } else {
            isPcCurrentlyConnected = false
            hasAlarmSoundedForCurrentConnection = false
            prefs.edit().putBoolean(KEY_LAST_PC_CONNECTED, false).apply()
            RescueLogger.i("Startup Check: No PC host attached. Rescue latch armed for next plug-in event.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RescueLogger.i("RescueForegroundService onStartCommand(action=${intent?.action})")
        // Ensure CPU WakeLock is active
        acquireCpuWakeLock()
        // Run silent check on service startup (never triggers alarm on app restart)
        RescueEngine.executeRoutineAsync(this, triggerAlarmOnSuccess = false)
        return START_STICKY
    }

    /**
     * Anti-Killer Defense:
     * When the user swipes the app away from Recents, onTaskRemoved is called.
     * We immediately schedule an alarm to resurrect the service so monitoring is never lost!
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        RescueLogger.i("RescueForegroundService: onTaskRemoved() triggered. Scheduling immediate background respawn...")

        try {
            val restartIntent = Intent(applicationContext, RescueForegroundService::class.java).apply {
                action = ACTION_START_ROUTINE
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                1004,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000L,
                    restartPendingIntent
                )
            } else {
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000L,
                    restartPendingIntent
                )
            }
        } catch (t: Throwable) {
            RescueLogger.w("Could not schedule service resurrection: ${t.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        RescueLogger.w("RescueForegroundService onDestroy()")
        watchdogJob?.cancel()
        unregisterDynamicReceivers()
        releaseCpuWakeLock()

        // Stop any active audio and guarantee original volume restoration
        HapticAndSoundFeedback.stopAudio()
        AlarmSoundManager.restoreAlarmVolume(this)
    }

    private fun startAutoHealingWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(8_000L)
                try {
                    // 1. Maintain CPU wake lock
                    acquireCpuWakeLock()

                    // 2. Verify and auto-restore developer options, ADB, and accessibility service
                    val hasPerm = RescueEngine.hasWriteSecureSettingsPermission(this@RescueForegroundService)
                    if (hasPerm) {
                        RescueEngine.autoRestoreSettingsIfSwitchedOff(this@RescueForegroundService)
                    } else {
                        val fixCmd = RescueEngine.getFixCommand(this@RescueForegroundService)
                        updateNotificationText("Setup Required! Run on PC: $fixCmd")
                    }
                } catch (ignored: Throwable) {}
            }
        }
    }

    /**
     * Starts foreground service with exact types compatible from Android 10 through Android 16.
     */
    private fun startForegroundSafely() {
        try {
            val hasPerm = RescueEngine.hasWriteSecureSettingsPermission(this)
            val initialText = if (hasPerm) {
                "Windows Android active: Background watchdog & PC auto-wake ready."
            } else {
                "Permission needed! Run on PC: ${RescueEngine.getFixCommand(this)}"
            }
            val notification = buildOngoingNotification(initialText)

            val fgsType = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    // Android 14, 15, 16 (API 34+)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    // Android 10 through 13 (API 29-33)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                else -> 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            RescueLogger.i("startForeground successful with type flag: $fgsType (API ${Build.VERSION.SDK_INT})")
        } catch (t: Throwable) {
            RescueLogger.w("startForeground failed: ${t.message}. Continuing in background mode.")
        }
    }

    private fun updateNotificationText(newText: String) {
        try {
            val notification = buildOngoingNotification(newText)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notification)
        } catch (ignored: Throwable) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return

            // 1. Ongoing service channel (silent, low importance)
            try {
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Windows Android Background Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps zero-touch USB connection monitor and recovery armed in background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(serviceChannel)
            } catch (t: Throwable) {
                RescueLogger.w("createNotificationChannel (service) failed: ${t.message}")
            }

            // 2. High-importance screen-wake channel for waking the display when PC connects
            try {
                val wakeChannel = NotificationChannel(
                    WAKE_CHANNEL_ID,
                    "PC Connection Screen Wake",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Wakes the screen and opens recovery interface when plugged into a PC"
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(wakeChannel)
            } catch (t: Throwable) {
                RescueLogger.w("createNotificationChannel (wake) failed: ${t.message}")
            }
        }
    }

    private fun buildOngoingNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Windows Android Active")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * WAKES THE SCREEN WHEN THE PC CONNECTS:
     * - Acquires a PowerManager SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP to light up the display panel.
     * - Launches MainActivity with FLAG_ACTIVITY_NEW_TASK, setShowWhenLocked(true), and setTurnScreenOn(true).
     * - Posts a Full-Screen Intent notification (USE_FULL_SCREEN_INTENT) on WAKE_CHANNEL_ID,
     *   which forces Android OS on Android 10 through 16 to wake the screen over the keyguard.
     */
    private fun wakeScreenOnPcConnected() {
        RescueLogger.s("==================================================")
        RescueLogger.s("WAKING SCREEN: Computer USB connection established!")
        RescueLogger.s("==================================================")

        // 1. Hardware Screen WakeLock with ACQUIRE_CAUSES_WAKEUP
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "WindowsAndroid:PcConnectScreenWake"
            )
            screenLock.acquire(15_000L) // Keep screen brightly awake for 15s
            RescueLogger.i("Hardware SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP engaged for 15s.")
        } catch (t: Throwable) {
            RescueLogger.w("Screen WakeLock failed: ${t.message}")
        }

        // 2. Full-Screen Intent Notification (Android 10-16 compliant lockscreen wake)
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra("extra_pc_connected_wake", true)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            1003,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val wakeNotification = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Computer Connected!")
                .setContentText("Screen awakened for zero-touch wired recovery.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(WAKE_NOTIFICATION_ID, wakeNotification)
            RescueLogger.i("Dispatched Full-Screen Intent notification to illuminate display over lockscreen.")
        } catch (t: Throwable) {
            RescueLogger.w("Wake notification dispatch failed: ${t.message}")
        }

        // 3. Direct Activity Launch attempt
        try {
            startActivity(fullScreenIntent)
            RescueLogger.i("Dispatched startActivity(MainActivity) with turnScreenOn flags.")
        } catch (t: Throwable) {
            RescueLogger.i("Direct background startActivity restricted by OS; Full-Screen Intent will handle screen wake.")
        }
    }

    private fun acquireCpuWakeLock() {
        try {
            if (partialWakeLock == null || !partialWakeLock!!.isHeld) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                partialWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WindowsAndroid:ForegroundWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(20 * 60 * 1000L) // 20 minutes safe renewal limit
                }
                RescueLogger.i("Acquired PartialWakeLock for continuous background execution.")
            }
        } catch (t: Throwable) {
            RescueLogger.w("Could not acquire WakeLock: ${t.message}")
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            partialWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            partialWakeLock = null
            RescueLogger.i("PartialWakeLock released.")
        } catch (ignored: Throwable) {}
    }

    /**
     * Register USB and Power receivers dynamically inside the service.
     * Evaluates trustworthy PC signals, debounces contact bounce, and executes the
     * non-repeating alarm logic and screen wake.
     */
    private fun registerDynamicReceivers() {
        if (usbReceiver != null) return

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return

                ConnectivityMonitor.refresh(this@RescueForegroundService)

                val isNowPc = ConnectivityMonitor.isTrustworthyPcConnection(this@RescueForegroundService)
                val isWall = ConnectivityMonitor.isTrustworthyWallCharger(this@RescueForegroundService)

                val safeContext = StorageHelper.getSafeContext(this@RescueForegroundService)
                val prefs = safeContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

                if (isWall && !isNowPc) {
                    // WALL CHARGER DETECTED -> Definitively ignore alarm and do not wake screen!
                    if (isPcCurrentlyConnected) {
                        isPcCurrentlyConnected = false
                        hasAlarmSoundedForCurrentConnection = false
                        prefs.edit().putBoolean(KEY_LAST_PC_CONNECTED, false).apply()
                    }
                    RescueLogger.i("Power connection is to an AC Wall Charger or wireless pad. Alarm and screen wake skipped.")
                    return
                }

                if (!isNowPc) {
                    // DISCONNECT EVENT
                    if (isPcCurrentlyConnected) {
                        isPcCurrentlyConnected = false
                        hasAlarmSoundedForCurrentConnection = false
                        prefs.edit().putBoolean(KEY_LAST_PC_CONNECTED, false).apply()
                        RescueLogger.i("PC USB disconnected. Connection latch reset and re-armed for next plug-in.")
                    }
                    return
                }

                // AT THIS POINT: isNowPc == true (VERIFIED PC HOST ATTACHED)
                if (!isPcCurrentlyConnected) {
                    // FRESH CONNECTION TRANSITION (Rising edge from disconnected to connected)
                    isPcCurrentlyConnected = true
                    prefs.edit().putBoolean(KEY_LAST_PC_CONNECTED, true).apply()

                    // WAKE THE SCREEN IMMEDIATELY WHEN PC CONNECTS!
                    wakeScreenOnPcConnected()

                    if (!hasAlarmSoundedForCurrentConnection) {
                        hasAlarmSoundedForCurrentConnection = true
                        RescueLogger.s("==================================================")
                        RescueLogger.s("TRUSTWORTHY PC HOST DETECTED! (USB Configured / ADB Active)")
                        RescueLogger.s("Playing arrival alarm ONCE for this connection session...")
                        RescueLogger.s("==================================================")
                        RescueEngine.executeRoutineAsync(this@RescueForegroundService, triggerAlarmOnSuccess = true)
                    } else {
                        RescueLogger.i("PC connection re-verified, but alarm was already played for this session. Will not repeat.")
                    }
                } else {
                    // ONGOING CONNECTION: Alarm was already triggered for this physical connection session.
                    RescueLogger.i("Ongoing PC USB connection ($action). Alarm already triggered for this session; not repeating.")
                    RescueEngine.executeRoutineAsync(this@RescueForegroundService, triggerAlarmOnSuccess = false)
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
                this,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            RescueLogger.i("Registered dynamic USB and Power receivers inside ForegroundService.")
        } catch (t: Throwable) {
            RescueLogger.w("Dynamic receiver registration error: ${t.message}")
        }
    }

    private fun unregisterDynamicReceivers() {
        usbReceiver?.let {
            try {
                unregisterReceiver(it)
                RescueLogger.i("Unregistered dynamic USB receivers.")
            } catch (ignored: Throwable) {}
            usbReceiver = null
        }
    }
}
