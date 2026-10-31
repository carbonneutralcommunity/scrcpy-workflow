package com.example.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object HapticAndSoundFeedback {

    private var activeMediaPlayer: MediaPlayer? = null
    private var lastAppContext: Context? = null

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Step 4: All steps OK.
     * Play a loud alarm sound and vibrate for 10 seconds, even on silent.
     * Maximizes alarm volume immediately and uses AudioAttributes USAGE_ALARM.
     */
    fun playSuccessFeedback(context: Context) {
        val appContext = context.applicationContext
        lastAppContext = appContext

        // 1. Maximize alarm volume right now (capturing previous level to restore later)
        AlarmSoundManager.maxOutAlarmVolume(appContext)

        // 2. Vibrate for 10 seconds (10,000 ms)
        try {
            val vibrator = getVibrator(appContext)
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createOneShot(10_000L, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(10_000L)
                }
            }
        } catch (t: Throwable) {
            RescueLogger.e("Vibration failed: ${t.message}")
        }

        // 3. Play alarm sound (default or customized) on USAGE_ALARM
        try {
            stopAudio()

            val alarmUri: Uri = AlarmSoundManager.getAlarmUri(appContext)

            if (alarmUri != Uri.EMPTY) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                activeMediaPlayer = MediaPlayer().apply {
                    setDataSource(appContext, alarmUri)
                    setAudioAttributes(audioAttributes)
                    isLooping = true
                    prepare()
                    start()
                }

                // Stop alarm sound after 10 seconds and restore original volume
                CoroutineScope(Dispatchers.Default).launch {
                    delay(10_000L)
                    stopAudio()
                }
            }
        } catch (t: Throwable) {
            RescueLogger.e("Alarm playback failed: ${t.message}")
        }
    }

    /**
     * Step 5: A step failed.
     * 3 short vibrations.
     */
    fun playErrorFeedback(context: Context) {
        try {
            stopAudio()
            val vibrator = getVibrator(context.applicationContext)
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 200, 150, 200, 150, 200)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (t: Throwable) {
            RescueLogger.e("Error vibration failed: ${t.message}")
        }
    }

    /**
     * Step 6: Dialog not found within timeout (60 seconds).
     * 2 long vibrations.
     */
    fun playTimeoutFeedback(context: Context) {
        try {
            val vibrator = getVibrator(context.applicationContext)
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 1000, 400, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (t: Throwable) {
            RescueLogger.e("Timeout vibration failed: ${t.message}")
        }
    }

    /**
     * Confirmation feedback when the USB debugging dialog has been successfully tapped!
     */
    fun playDialogConfirmedFeedback(context: Context) {
        try {
            val vibrator = getVibrator(context.applicationContext)
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 100, 80, 250)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (ignored: Throwable) {}
    }

    fun stopAudio() {
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            activeMediaPlayer = null
        } catch (ignored: Throwable) {}

        // Restore original volume when audio stops
        lastAppContext?.let { ctx ->
            AlarmSoundManager.restoreAlarmVolume(ctx)
        }
    }
}
