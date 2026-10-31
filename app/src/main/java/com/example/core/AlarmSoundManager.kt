package com.example.core

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AlarmSoundManager {

    private const val PREFS_NAME = "windows_android_alarm_prefs"
    private const val KEY_CUSTOM_URI = "key_custom_alarm_uri"
    private const val KEY_CUSTOM_TITLE = "key_custom_alarm_title"

    private var savedOriginalVolume: Int = -1
    private var maxVolume: Int = -1

    private val _currentSoundTitle = MutableStateFlow("Default Alarm")
    val currentSoundTitle: StateFlow<String> = _currentSoundTitle.asStateFlow()

    private val _volumeStatus = MutableStateFlow("Volume: Normal")
    val volumeStatus: StateFlow<String> = _volumeStatus.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_CUSTOM_TITLE, null)
        _currentSoundTitle.value = title ?: "Default Alarm (System)"
    }

    /**
     * Maximize the alarm volume immediately when the app opens or when alarm triggers.
     * Captures and preserves the original volume (even if previously silent or reduced, e.g. 59%).
     */
    @Synchronized
    fun maxOutAlarmVolume(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            maxVolume = max

            // Only capture original volume if we haven't already saved it
            if (savedOriginalVolume == -1) {
                savedOriginalVolume = current
                RescueLogger.i("Preserved original alarm volume: $current/$max (${(current * 100) / max}%).")
            }

            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            _volumeStatus.value = "Volume: Maxed (100%) [Original: $savedOriginalVolume/$max]"
            RescueLogger.s("Alarm volume maximized to 100% ($max/$max).")
        } catch (t: Throwable) {
            RescueLogger.w("Could not maximize alarm volume: ${t.message}")
        }
    }

    /**
     * Restores the alarm volume back to the exact saved level (e.g. 59% or 0)
     * when the app is closed, destroyed, or stopped.
     */
    @Synchronized
    fun restoreAlarmVolume(context: Context) {
        try {
            if (savedOriginalVolume != -1) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedOriginalVolume, 0)
                RescueLogger.i("Restored alarm volume back to original level: $savedOriginalVolume/$maxVolume.")
                _volumeStatus.value = "Volume: Restored to original ($savedOriginalVolume/$maxVolume)"
                savedOriginalVolume = -1
            }
        } catch (t: Throwable) {
            RescueLogger.w("Could not restore alarm volume: ${t.message}")
        }
    }

    fun getAlarmUri(context: Context): Uri {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (uri != null) {
                putString(KEY_CUSTOM_URI, uri.toString())
                val soundTitle = title ?: getTitleFromUri(context, uri)
                putString(KEY_CUSTOM_TITLE, soundTitle)
                _currentSoundTitle.value = soundTitle
                RescueLogger.s("Configured custom alarm sound: '$soundTitle'.")
            } else {
                remove(KEY_CUSTOM_URI)
                remove(KEY_CUSTOM_TITLE)
                _currentSoundTitle.value = "Default Alarm (System)"
                RescueLogger.i("Reset alarm sound to system default.")
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
