package com.example.core

import android.content.Context
import android.os.Build

/**
 * StorageHelper ensures that saved settings and logs are stored in Device Protected (DE) Storage.
 *
 * Android devices with File-Based Encryption (FBE) operate in "Direct Boot" mode after a reboot
 * until the user unlocks the screen with their PIN, password, or pattern.
 *
 * During Direct Boot:
 * - Credential-Encrypted (CE) storage is locked and inaccessible.
 * - Device-Protected (DE) storage is unlocked and accessible immediately.
 *
 * By using Device-Protected storage context, settings (such as alarm configuration) and logs
 * survive reboots and are fully accessible even BEFORE the phone is unlocked.
 */
object StorageHelper {

    /**
     * Returns a Context configured for Device Protected Storage on Android 7.0+ (API 24+).
     * On older platforms, returns the base context.
     */
    fun getSafeContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    }

    /**
     * Safely migrates existing SharedPreferences from Credential Encrypted (CE) storage
     * to Device Protected (DE) storage so that previously saved user settings are not lost.
     */
    fun migratePreferencesIfNecessary(context: Context, prefsName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val deContext = context.createDeviceProtectedStorageContext()
                // moveSharedPreferencesFrom copies the file from CE to DE and deletes the CE version
                deContext.moveSharedPreferencesFrom(context, prefsName)
            } catch (ignored: Throwable) {
                // If migration is not possible (e.g. already migrated or storage locked), continue safely
            }
        }
    }
}
