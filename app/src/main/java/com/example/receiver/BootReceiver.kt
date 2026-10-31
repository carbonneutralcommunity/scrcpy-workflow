package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.RescueLogger
import com.example.service.RescueForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        RescueLogger.init(context)
        RescueLogger.i("BootReceiver received action: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == action ||
            "android.intent.action.QUICKBOOT_POWERON" == action
        ) {
            RescueLogger.s("Device boot detected! Starting RescueForegroundService in background...")
            RescueForegroundService.startService(context)
        }
    }
}
