package com.example.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO, SUCCESS, WARN, ERROR
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

object RescueLogger {

    private const val TAG = "ScrcpyRescue"
    private const val LOG_FILE_NAME = "scrcpy_rescue.log"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var logFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            val isNewFile = !logFile!!.exists()
            if (isNewFile) {
                logHeader()
            }
        }
    }

    private fun logHeader() {
        val header = buildString {
            appendLine("==================================================")
            appendLine("Scrcpy Rescue - Session Initialized: ${fullDateFormat.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
            }
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                appendLine("OEM Check: Samsung One UI detected. Note: Auto Blocker must be disabled for USB commands.")
            } else if (Build.MANUFACTURER.equals("sony", ignoreCase = true)) {
                appendLine("OEM Check: Sony Xperia detected. Stock dialog mappings active.")
            }
            appendLine("==================================================")
        }
        appendToFile(header)
    }

    @Synchronized
    fun log(level: LogLevel, message: String) {
        val time = dateFormat.format(Date())
        val entry = LogEntry(timestamp = time, level = level, message = message)

        try {
            when (level) {
                LogLevel.INFO -> Log.i(TAG, message)
                LogLevel.SUCCESS -> Log.i(TAG, "SUCCESS: $message")
                LogLevel.WARN -> Log.w(TAG, message)
                LogLevel.ERROR -> Log.e(TAG, message)
            }
        } catch (ignored: Throwable) {}

        val updated = _logs.value + entry
        // Keep in-memory buffer at most 500 lines
        _logs.value = if (updated.size > 500) updated.takeLast(500) else updated

        appendToFile("[$time] [${level.name}] $message\n")
    }

    fun i(msg: String) = log(LogLevel.INFO, msg)
    fun s(msg: String) = log(LogLevel.SUCCESS, msg)
    fun w(msg: String) = log(LogLevel.WARN, msg)
    fun e(msg: String) = log(LogLevel.ERROR, msg)

    private fun appendToFile(text: String) {
        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.write(text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing to log file: ${t.message}")
            }
        }
    }

    fun getLogFile(): File? = logFile

    fun readLogFileText(): String {
        return try {
            logFile?.readText() ?: "No log file available"
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    fun clear() {
        _logs.value = emptyList()
        try {
            logFile?.delete()
            logHeader()
        } catch (ignored: Exception) {}
    }
}
