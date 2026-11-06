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
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024L // 512 KB max before gentle rotation

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Primary safe log file in Device Protected (DE) storage - available even before phone unlock
    private var primarySafeFile: File? = null

    // Mirror log file in External Files directory - easy for PC to pull via ADB or MTP
    private var externalMirrorFile: File? = null

    private var cachedContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        cachedContext = context.applicationContext

        // 1. Setup Primary Safe Log File (Device-Protected Storage: survives reboots & works before unlock)
        if (primarySafeFile == null) {
            val safeContext = StorageHelper.getSafeContext(context)
            val dir = safeContext.filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            primarySafeFile = File(dir, LOG_FILE_NAME)

            // Check if legacy log exists in credential-encrypted storage to migrate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !primarySafeFile!!.exists()) {
                try {
                    val legacyFile = File(context.filesDir, LOG_FILE_NAME)
                    if (legacyFile.exists()) {
                        legacyFile.copyTo(primarySafeFile!!, overwrite = true)
                        legacyFile.delete()
                    }
                } catch (ignored: Throwable) {}
            }

            val isNew = !primarySafeFile!!.exists() || primarySafeFile!!.length() == 0L
            if (isNew) {
                logHeader()
            }
        }

        // 2. Setup External Mirror Log File (for PC adb pull)
        checkAndSyncExternalMirror(context)
    }

    private fun checkAndSyncExternalMirror(context: Context) {
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                if (!extDir.exists()) {
                    extDir.mkdirs()
                }
                externalMirrorFile = File(extDir, LOG_FILE_NAME)

                // If primary file has content and mirror is missing or smaller, sync up
                val primary = primarySafeFile
                if (primary != null && primary.exists()) {
                    val mirror = externalMirrorFile!!
                    if (!mirror.exists() || mirror.length() < primary.length()) {
                        try {
                            primary.copyTo(mirror, overwrite = true)
                        } catch (ignored: Throwable) {}
                    }
                }
            }
        } catch (ignored: Throwable) {
            // In Direct Boot before unlock, external storage may be unavailable
        }
    }

    private fun logHeader() {
        val header = buildString {
            appendLine("==================================================")
            appendLine("Windows Android - Session Initialized: ${fullDateFormat.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Storage Mode: Device-Protected Storage (Direct Boot Safe)")
            appendLine("Network: Offline Only (No Internet/Network Server)")
            appendLine("Backup: Disabled (Protected Local Storage)")
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
        // Keep in-memory buffer at most 500 lines for smooth UI rendering
        _logs.value = if (updated.size > 500) updated.takeLast(500) else updated

        appendToFile("[$time] [${level.name}] $message\n")
    }

    fun i(msg: String) = log(LogLevel.INFO, msg)
    fun s(msg: String) = log(LogLevel.SUCCESS, msg)
    fun w(msg: String) = log(LogLevel.WARN, msg)
    fun e(msg: String) = log(LogLevel.ERROR, msg)

    private fun appendToFile(text: String) {
        // Rotate if primary log file exceeds limit
        rotateLogFileIfNecessary()

        // 1. Write to Primary Safe Log File (Device-Protected Storage)
        primarySafeFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.write(text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing to primary safe log file: ${t.message}")
            }
        }

        // 2. Write to External Mirror Log File if accessible (for easy PC pulling)
        cachedContext?.let { ctx ->
            if (externalMirrorFile == null) {
                checkAndSyncExternalMirror(ctx)
            }
        }

        externalMirrorFile?.let { mirror ->
            try {
                FileWriter(mirror, true).use { writer ->
                    writer.write(text)
                }
            } catch (ignored: Throwable) {
                // If external storage is not yet mounted (Direct Boot mode), ignore mirror write
            }
        }
    }

    private fun rotateLogFileIfNecessary() {
        val file = primarySafeFile ?: return
        if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
            try {
                val lines = file.readLines()
                val keepLines = lines.takeLast(2000)
                file.writeText("[LOG ROTATED: Preserving latest 2000 entries]\n" + keepLines.joinToString("\n") + "\n")
                externalMirrorFile?.let { mirror ->
                    try {
                        file.copyTo(mirror, overwrite = true)
                    } catch (ignored: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Log rotation failed: ${t.message}")
            }
        }
    }

    fun getLogFile(): File? = primarySafeFile ?: externalMirrorFile

    fun getPrimarySafeLogFile(): File? = primarySafeFile

    fun getExternalMirrorLogFile(): File? = externalMirrorFile

    fun readLogFileText(): String {
        return try {
            primarySafeFile?.readText()
                ?: externalMirrorFile?.readText()
                ?: "No log file available"
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    fun clear() {
        _logs.value = emptyList()
        try {
            primarySafeFile?.delete()
            externalMirrorFile?.delete()
            logHeader()
        } catch (ignored: Exception) {}
    }

    /**
     * Returns clear instructions and ADB commands for pulling and reading the log from a PC.
     */
    fun getPcPullCommands(context: Context): String {
        val pkg = context.packageName
        return buildString {
            appendLine("# Option 1: Pull from external storage (Recommended when unlocked):")
            appendLine("adb pull /sdcard/Android/data/$pkg/files/$LOG_FILE_NAME ./scrcpy_rescue.log")
            appendLine()
            appendLine("# Option 2: Pull even before phone is unlocked (Direct Boot mode):")
            appendLine("adb exec-out run-as $pkg cat files/$LOG_FILE_NAME > scrcpy_rescue.log")
            appendLine()
            appendLine("# Option 3: View directly in terminal in real time:")
            appendLine("adb exec-out run-as $pkg tail -f files/$LOG_FILE_NAME")
        }
    }
}
