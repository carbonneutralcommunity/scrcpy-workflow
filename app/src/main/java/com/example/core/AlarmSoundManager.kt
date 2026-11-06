package com.example.core

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages alarm sound selection, preview, and safe volume management.
 *
 * STEP 3 REQUIREMENTS COMPLIANT:
 * 1. "The alarm should only play when it's really a PC."
 * 2. "my original volume must always be restored, even if the app crashes."
 *
 * HOW CRASH-PROOF VOLUME RESTORATION WORKS:
 * - Before increasing stream volume to 100%, the user's current alarm volume is synchronously
 *   persisted to Device-Protected (DE) storage via SharedPreferences (.commit()).
 * - A global UncaughtExceptionHandler is installed to restore the original volume immediately
 *   if any thread crashes.
 * - A JVM shutdown hook is installed as an additional fallback.
 * - On every startup (init), the manager checks for orphaned volume flags from interrupted sessions
 *   or sudden OS process kills, immediately rolling back the volume to the user's original level.
 */
object AlarmSoundManager {

    private const val PREFS_NAME = "windows_android_alarm_prefs"
    private const val KEY_CUSTOM_URI = "key_custom_alarm_uri"
    private const val KEY_CUSTOM_TITLE = "key_custom_alarm_title"
    private const val KEY_SAVED_ORIGINAL_VOLUME = "key_saved_original_volume"
    private const val KEY_VOLUME_OVERRIDDEN = "key_volume_overridden"
    private const val KEY_OVERRIDE_TIMESTAMP = "key_override_timestamp"

    private var savedOriginalVolume: Int = -1
    private var maxVolume: Int = -1
    private var isCrashHandlerRegistered = false

    private val _currentSoundTitle = MutableStateFlow("Default Alarm")
    val currentSoundTitle: StateFlow<String> = _currentSoundTitle.asStateFlow()

    private val _volumeStatus = MutableStateFlow("Volume: Normal (Original Restored)")
    val volumeStatus: StateFlow<String> = _volumeStatus.asStateFlow()

    /**
     * Initializes the manager, migrates legacy CE preferences to DE storage,
     * installs global crash handlers, and performs startup crash recovery if
     * the app previously crashed while volume was maxed out.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext

        // 1. Migrate legacy prefs to device-protected storage if necessary
        StorageHelper.migratePreferencesIfNecessary(appContext, PREFS_NAME)

        val safeContext = StorageHelper.getSafeContext(appContext)
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 2. Install crash handler & shutdown hook for active session crash protection
        registerCrashHandler(appContext)

        // 3. CRASH RECOVERY: Check if a previous run was killed or crashed while volume was maxed
        if (prefs.getBoolean(KEY_VOLUME_OVERRIDDEN, false)) {
            val orphanedVolume = prefs.getInt(KEY_SAVED_ORIGINAL_VOLUME, -1)
            RescueLogger.w("CRASH RECOVERY: Found orphaned volume override from interrupted session ($orphanedVolume). Restoring...")
            if (orphanedVolume >= 0) {
                try {
                    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, orphanedVolume, 0)
                    RescueLogger.s("CRASH RECOVERY: Successfully restored original volume to $orphanedVolume after prior crash/kill!")
                } catch (t: Throwable) {
                    RescueLogger.e("Crash recovery volume restore failed: ${t.message}")
                }
            }
            prefs.edit()
                .remove(KEY_SAVED_ORIGINAL_VOLUME)
                .putBoolean(KEY_VOLUME_OVERRIDDEN, false)
                .remove(KEY_OVERRIDE_TIMESTAMP)
                .commit()
            _volumeStatus.value = "Volume: Restored ($orphanedVolume) [Crash Recovery]"
        } else {
            _volumeStatus.value = "Volume: Normal (Protected)"
        }

        val title = prefs.getString(KEY_CUSTOM_TITLE, null)
        _currentSoundTitle.value = title ?: "Default Alarm (System)"
    }

    /**
     * Registers a process-wide crash handler and JVM shutdown hook so that if an uncaught
     * exception or crash occurs in any thread while volume is maxed, the original volume
     * is restored synchronously before the process terminates.
     */
    @Synchronized
    private fun registerCrashHandler(context: Context) {
        if (isCrashHandlerRegistered) return
        isCrashHandlerRegistered = true

        val appContext = context.applicationContext
        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Synchronously restore original volume on fatal crash
                restoreAlarmVolume(appContext)
            } catch (ignored: Throwable) {}
            existingHandler?.uncaughtException(thread, throwable)
        }

        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    restoreAlarmVolume(appContext)
                } catch (ignored: Throwable) {}
            })
        } catch (ignored: Throwable) {}
    }

    /**
     * Maximize the alarm volume immediately when the alarm is actively sounding.
     * Captures and synchronously commits the original volume to Device-Protected storage
     * BEFORE changing the stream volume, ensuring zero data loss if killed or crashed.
     */
    @Synchronized
    fun maxOutAlarmVolume(context: Context) {
        val appContext = context.applicationContext
        val safeContext = StorageHelper.getSafeContext(appContext)
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            maxVolume = max

            // Only capture original volume if not already currently marked overridden
            val alreadyOverridden = prefs.getBoolean(KEY_VOLUME_OVERRIDDEN, false)
            if (!alreadyOverridden) {
                savedOriginalVolume = current
                // Persist synchronously to flash BEFORE touching the audio manager
                prefs.edit()
                    .putInt(KEY_SAVED_ORIGINAL_VOLUME, current)
                    .putBoolean(KEY_VOLUME_OVERRIDDEN, true)
                    .putLong(KEY_OVERRIDE_TIMESTAMP, System.currentTimeMillis())
                    .commit()
                RescueLogger.i("Preserved original alarm volume to DE flash: $current/$max (${(current * 100) / max}%).")
            } else {
                if (savedOriginalVolume == -1) {
                    savedOriginalVolume = prefs.getInt(KEY_SAVED_ORIGINAL_VOLUME, current)
                }
            }

            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            _volumeStatus.value = "Volume: Maxed (100%) [Original: $savedOriginalVolume/$max]"
            RescueLogger.s("Alarm volume maximized to 100% ($max/$max). Crash protection active.")
        } catch (t: Throwable) {
            RescueLogger.w("Could not maximize alarm volume: ${t.message}")
        }
    }

    /**
     * Restores the alarm volume back to the exact saved level (e.g. 59% or 0)
     * when alarm playback finishes, or when the app is closed, destroyed, or stopped.
     * Clears the persistent override flag from Device-Protected storage.
     */
    @Synchronized
    fun restoreAlarmVolume(context: Context) {
        val appContext = context.applicationContext
        val safeContext = StorageHelper.getSafeContext(appContext)
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val isOverridden = prefs.getBoolean(KEY_VOLUME_OVERRIDDEN, false) || savedOriginalVolume != -1
            if (isOverridden) {
                val targetVol = if (savedOriginalVolume != -1) {
                    savedOriginalVolume
                } else {
                    prefs.getInt(KEY_SAVED_ORIGINAL_VOLUME, -1)
                }

                if (targetVol != -1) {
                    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
                    RescueLogger.s("Restored alarm volume back to original level: $targetVol/$maxVolume.")
                    _volumeStatus.value = "Volume: Restored to original ($targetVol/$maxVolume)"
                }

                // Clear persistent override flag synchronously
                prefs.edit()
                    .remove(KEY_SAVED_ORIGINAL_VOLUME)
                    .putBoolean(KEY_VOLUME_OVERRIDDEN, false)
                    .remove(KEY_OVERRIDE_TIMESTAMP)
                    .commit()
                savedOriginalVolume = -1
            }
        } catch (t: Throwable) {
            RescueLogger.w("Could not restore alarm volume: ${t.message}")
        }
    }

    fun isVolumeOverridden(context: Context): Boolean {
        val safeContext = StorageHelper.getSafeContext(context.applicationContext)
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VOLUME_OVERRIDDEN, false) || savedOriginalVolume != -1
    }

    fun getAlarmUri(context: Context): Uri {
        val safeContext = StorageHelper.getSafeContext(context)
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customUriString = prefs.getString(KEY_CUSTOM_URI, null)

        if (!customUriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(customUriString)
                if (uri != null) {
                    return uri
                }
            } catch (ignored: Throwable) {}
        }

        // Fallback to default system alarm
        var defaultAlarm: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (defaultAlarm == null) {
            defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        if (defaultAlarm == null) {
            defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
        return defaultAlarm ?: Uri.EMPTY
    }

    fun setCustomAlarmUri(context: Context, uri: Uri?, title: String?) {
        val safeContext = StorageHelper.getSafeContext(context)
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (uri != null) {
                putString(KEY_CUSTOM_URI, uri.toString())
                val soundTitle = title ?: getTitleFromUri(context, uri)
                putString(KEY_CUSTOM_TITLE, soundTitle)
                _currentSoundTitle.value = soundTitle
                RescueLogger.s("Configured custom alarm sound in device-protected storage: '$soundTitle'.")
            } else {
                remove(KEY_CUSTOM_URI)
                remove(KEY_CUSTOM_TITLE)
                _currentSoundTitle.value = "Default Alarm (System)"
                RescueLogger.i("Reset alarm sound to system default in device-protected storage.")
            }
        }.apply()
    }

    fun resetToDefault(context: Context) {
        setCustomAlarmUri(context, null, null)
    }

    fun getTitleFromUri(context: Context, uri: Uri): String {
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.getTitle(context) ?: "Custom Sound"
        } catch (e: Exception) {
            "Custom Sound"
        }
    }
}
