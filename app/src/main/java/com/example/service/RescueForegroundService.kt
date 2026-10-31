package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.core.ConnectivityMonitor
import com.example.core.RescueEngine
import com.example.core.RescueLogger

class RescueForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "rescue_foreground_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_ROUTINE = "com.example.action.START_ROUTINE"

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

    private var wakeLock: PowerManager.WakeLock? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var lastUsbTriggerTime = 0L

    override fun onCreate() {
        super.onCreate()
        RescueLogger.init(this)
        RescueLogger.i("RescueForegroundService onCreate()")

        // 1. Create notification channel and start foreground safely
        createNotificationChannel()
        startForegroundSafely()

        // 2. Acquire WakeLock to keep CPU active even if screen goes dark
        acquireWakeLock()

        // 3. Dynamically register USB and Power state receivers
        registerDynamicReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RescueLogger.i("RescueForegroundService onStartCommand(action=${intent?.action})")
        RescueEngine.executeRoutineAsync(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        RescueLogger.w("RescueForegroundService onDestroy()")
        unregisterDynamicReceivers()
        releaseWakeLock()
    }

    private fun startForegroundSafely() {
        try {
            val notification = buildNotification("Monitoring USB connection for zero-touch scrcpy...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+): Use specialUse foregroundServiceType
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29-33: Start with 0 / default
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            RescueLogger.w("startForeground failed: ${t.message}. Continuing in background mode.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Scrcpy Rescue Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps zero-touch USB connection monitor and broken screen recovery active"
                    setShowBadge(false)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            } catch (t: Throwable) {
                RescueLogger.w("createNotificationChannel failed: ${t.message}")
            }
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scrcpy Rescue Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ScrcpyRescue:ForegroundWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 minutes timeout safe limit
            }
            RescueLogger.i("Acquired PartialWakeLock for continuous background execution.")
        } catch (t: Throwable) {
            RescueLogger.w("Could not acquire WakeLock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            RescueLogger.i("WakeLock released.")
        } catch (ignored: Throwable) {}
    }

    /**
     * Register USB and Power receivers dynamically inside the service, NEVER in the manifest.
     * Uses ContextCompat.RECEIVER_EXPORTED on Android 14+ to prevent SecurityException.
     */
    private fun registerDynamicReceivers() {
        if (usbReceiver != null) return

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                val now = System.currentTimeMillis()

                if (now - lastUsbTriggerTime < 3000L) return

                RescueLogger.i("Dynamic Receiver triggered with action: $action")

                if (action == "android.hardware.usb.action.USB_STATE") {
                    val connected = intent.getBooleanExtra("connected", false)
                    val configured = intent.getBooleanExtra("configured", false)
                    val adb = intent.getBooleanExtra("adb", false)
                    RescueLogger.i("USB_STATE changed: connected=$connected, configured=$configured, adb=$adb")
                    ConnectivityMonitor.refresh(this@RescueForegroundService)

                    if (connected) {
                        lastUsbTriggerTime = now
                        RescueLogger.s("USB cable attached! Auto-triggering zero-touch scrcpy routine...")
                        RescueEngine.executeRoutineAsync(this@RescueForegroundService)
                    }
                } else if (action == Intent.ACTION_POWER_CONNECTED) {
                    lastUsbTriggerTime = now
                    ConnectivityMonitor.refresh(this@RescueForegroundService)
                    RescueLogger.s("Power cable plugged in! Auto-triggering zero-touch scrcpy routine...")
                    RescueEngine.executeRoutineAsync(this@RescueForegroundService)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(Intent.ACTION_POWER_CONNECTED)
        }

        try {
            ContextCompat.registerReceiver(
                this,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            RescueLogger.i("Registered dynamic USB_STATE and ACTION_POWER_CONNECTED receivers inside ForegroundService.")
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
