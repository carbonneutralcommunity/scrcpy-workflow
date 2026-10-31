package com.example

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.AlarmSoundManager
import com.example.core.ConnectivityMonitor
import com.example.core.ConnectivityState
import com.example.core.HapticAndSoundFeedback
import com.example.core.LogLevel
import com.example.core.RescueEngine
import com.example.core.RescueLogger
import com.example.core.RoutineState
import com.example.core.StepStatus
import com.example.model.DeviceProfileResolver
import com.example.service.RescueAccessibilityService
import com.example.service.RescueForegroundService
import com.example.ui.PermissionWizardModal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ErrorRedBg
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RescueCardBorder
import com.example.ui.theme.RescueCyan
import com.example.ui.theme.RescueSlate
import com.example.ui.theme.RescueSurfaceDark
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenBg
import com.example.ui.theme.TerminalBackground
import com.example.ui.theme.TerminalText
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberBg

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            RescueLogger.init(this)
            RescueLogger.i("Windows Android launcher opened.")
        } catch (ignored: Throwable) {}

        // Start live connectivity and USB debugging monitor
        try {
            ConnectivityMonitor.startMonitoring(this)
        } catch (t: Throwable) {
            RescueLogger.w("Connectivity monitor initialization: ${t.message}")
        }

        // 1. Initialize Alarm Sound & immediately maximize alarm volume (preserving original to restore on exit)
        try {
            AlarmSoundManager.init(this)
            AlarmSoundManager.maxOutAlarmVolume(this)
        } catch (t: Throwable) {
            RescueLogger.w("Volume initialization: ${t.message}")
        }

        // 2. Keep the screen awake during the process
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
        } catch (t: Throwable) {
            RescueLogger.w("Screen flag setup: ${t.message}")
        }

        // 3. Start foreground monitor service safely
        try {
            RescueForegroundService.startService(this)
        } catch (t: Throwable) {
            RescueLogger.w("Could not start foreground service: ${t.message}")
        }

        // 4. AUTO-START: Immediately execute the zero-touch preparation routine on launch (no button presses needed)
        try {
            RescueEngine.executeRoutineAsync(this)
        } catch (t: Throwable) {
            RescueLogger.e("Initial routine trigger error: ${t.message}")
        }

        setContent {
            MyApplicationTheme(darkTheme = true) {
                RescueMainScreen(
                    onRerunRoutine = { RescueEngine.executeRoutineAsync(this) },
                    onSimulateSuccess = { RescueEngine.simulateSuccessRoutine(this) },
                    onSimulateDialog = { RescueEngine.simulateDialogDetected(this) },
                    onRequestBatteryOptimization = { requestIgnoreBatteryOptimization() },
                    onTestSensoryFeedback = { HapticAndSoundFeedback.playSuccessFeedback(this) },
                    onStopSound = { HapticAndSoundFeedback.stopAudio() },
                    onCopyGrantCommand = { copyGrantCommandToClipboard() },
                    onShareLog = { shareLogFile() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure volume is maximized while app is in foreground
        AlarmSoundManager.maxOutAlarmVolume(this)
        // Refresh live connectivity and USB debugging status
        ConnectivityMonitor.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        // Stop any active alarm audio and restore the original volume as it was prior to opening
        HapticAndSoundFeedback.stopAudio()
        AlarmSoundManager.restoreAlarmVolume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        HapticAndSoundFeedback.stopAudio()
        AlarmSoundManager.restoreAlarmVolume(this)
        ConnectivityMonitor.stopMonitoring()
        RescueLogger.i("Windows Android closed. Original volume restored.")
    }

    private fun copyGrantCommandToClipboard() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ADB Grant Command", command)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied ADB grant command to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallback)
                }
            } else {
                Toast.makeText(this, "Battery optimizations already ignored!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLogFile() {
        val file = RescueLogger.getLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Log file is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Windows Android Rescue Log - ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, file.readText())
        }
        startActivity(Intent.createChooser(sendIntent, "Share Rescue Log"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueMainScreen(
    onRerunRoutine: () -> Unit,
    onSimulateSuccess: () -> Unit,
    onSimulateDialog: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onTestSensoryFeedback: () -> Unit,
    onStopSound: () -> Unit,
    onCopyGrantCommand: () -> Unit,
    onShareLog: () -> Unit
) {
    val context = LocalContext.current
    val routineState by RescueEngine.routineState.collectAsState()
    val stepStatus by RescueEngine.stepStatus.collectAsState()
    val isAccessibilityConnected by RescueAccessibilityService.isServiceConnected.collectAsState()
    val isScanningForDialog by RescueAccessibilityService.isScanningForDialog.collectAsState()
    val isDialogConfirmed by RescueAccessibilityService.dialogConfirmed.collectAsState()
    val logs by RescueLogger.logs.collectAsState()

    val currentSoundTitle by AlarmSoundManager.currentSoundTitle.collectAsState()
    val volumeStatus by AlarmSoundManager.volumeStatus.collectAsState()
    val connectivityState by ConnectivityMonitor.state.collectAsState()
    var showWizardModal by remember { mutableStateOf(false) }

    val profile = remember { DeviceProfileResolver.resolve() }

    // Ringtone picker launcher for customizing alarm sound
    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                AlarmSoundManager.setCustomAlarmUri(context, uri, null)
                Toast.makeText(context, "Selected: ${AlarmSoundManager.currentSoundTitle.value}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Windows Android",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Auto-Start Zero-Touch Recovery",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RescueSlate
                ),
                actions = {
                    IconButton(
                        onClick = { showWizardModal = true },
                        modifier = Modifier.testTag("action_open_wizard")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Setup Wizard & Guide",
                            tint = RescueCyan
                        )
                    }
                    IconButton(
                        onClick = onShareLog,
                        modifier = Modifier.testTag("action_share_log")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Log",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            ConnectivityMonitor.refresh(context)
                            onRerunRoutine()
                        },
                        modifier = Modifier.testTag("action_refresh_routine")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Re-run Routine",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        containerColor = RescueSlate
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // 1. Live Connectivity & USB Debugging Dashboard (Prominent card at the top)
            ConnectivityDashboardCard(
                connectivityState = connectivityState,
                onRefresh = { ConnectivityMonitor.refresh(context) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Setup Wizard & Auto Permission Guide Shortcut Button
            OutlinedButton(
                onClick = { showWizardModal = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .testTag("btn_open_setup_wizard_banner"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RescueCyan),
                border = androidx.compose.foundation.BorderStroke(1.dp, RescueCyan.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Permission Wizard & Broken Screen Guide",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Overall Status Banner
            StatusBanner(
                routineState = routineState,
                stepStatus = stepStatus,
                isScanning = isScanningForDialog,
                isConfirmed = isDialogConfirmed,
                onCopyGrantCommand = onCopyGrantCommand
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 4. Alarm Sound & Auto Volume Card
            AlarmConfigCard(
                currentSoundTitle = currentSoundTitle,
                volumeStatus = volumeStatus,
                onPickCustomSound = {
                    val currentUri = AlarmSoundManager.getAlarmUri(context)
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Windows Android Alarm")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                    }
                    ringtonePickerLauncher.launch(intent)
                },
                onResetToDefault = {
                    AlarmSoundManager.resetToDefault(context)
                    Toast.makeText(context, "Reset to System Default Alarm", Toast.LENGTH_SHORT).show()
                },
                onTestSound = onTestSensoryFeedback,
                onStopSound = onStopSound
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 5. Steps Status Card
            StepsCard(
                stepStatus = stepStatus,
                isAccessibilityConnected = isAccessibilityConnected,
                isScanningForDialog = isScanningForDialog,
                isDialogConfirmed = isDialogConfirmed
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 6. Device & OEM Profile Card
            DeviceProfileCard(
                profile = profile,
                onRequestBatteryOptimization = onRequestBatteryOptimization
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 7. Interactive Action Buttons Row
            ActionButtonsRow(
                onRerunRoutine = onRerunRoutine,
                onSimulateSuccess = onSimulateSuccess,
                onSimulateDialog = onSimulateDialog
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 8. Terminal Log Viewer (Scrollable execution log with testTag)
            TerminalLogViewer(
                logs = logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Setup Wizard & Guide Modal Dialog
        if (showWizardModal) {
            PermissionWizardModal(
                onDismiss = { showWizardModal = false },
                onRerunRoutine = {
                    showWizardModal = false
                    onRerunRoutine()
                }
            )
        }
    }
}

@Composable
fun ConnectivityDashboardCard(
    connectivityState: ConnectivityState,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connectivity_dashboard_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with pulsing indicator & refresh button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (connectivityState.isUsbConnected && connectivityState.isAdbEnabled) SuccessGreen
                                else if (connectivityState.isUsbConnected || connectivityState.isAdbEnabled) WarningAmber
                                else Color(0xFF64748B)
                            )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "LIVE CONNECTIVITY & USB STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(26.dp)
                        .testTag("btn_refresh_connectivity")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Connectivity",
                        tint = RescueCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2x2 Grid Tiles for live metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConnectivityMetricTile(
                    modifier = Modifier.weight(1f),
                    title = "USB Physical Link",
                    value = if (connectivityState.isUsbConnected) "CONNECTED" else "UNPLUGGED",
                    subtitle = if (connectivityState.isUsbConnected) "PC cable detected" else "Plug in USB cable",
                    icon = Icons.Default.Usb,
                    isActive = connectivityState.isUsbConnected,
                    activeColor = SuccessGreen
                )

                ConnectivityMetricTile(
                    modifier = Modifier.weight(1f),
                    title = "USB Debugging",
                    value = if (connectivityState.isAdbEnabled) "ENABLED (1)" else "DISABLED (0)",
                    subtitle = if (connectivityState.isAdbEnabled) "ADB daemon active" else "Routine will enable",
                    icon = Icons.Default.BugReport,
                    isActive = connectivityState.isAdbEnabled,
                    activeColor = SuccessGreen
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val powerDetail = if (connectivityState.batteryPercent >= 0) {
                    "${connectivityState.powerSource} (${connectivityState.batteryPercent}%)"
                } else {
                    connectivityState.powerSource
                }

                ConnectivityMetricTile(
                    modifier = Modifier.weight(1f),
                    title = "Power & Battery",
                    value = if (connectivityState.isPowerConnected) "CHARGING" else "BATTERY",
                    subtitle = powerDetail,
                    icon = if (connectivityState.isPowerConnected) Icons.Default.Bolt else Icons.Default.Power,
                    isActive = connectivityState.isPowerConnected,
                    activeColor = RescueCyan
                )

                ConnectivityMetricTile(
                    modifier = Modifier.weight(1f),
                    title = "Auto-Allow Service",
                    value = if (connectivityState.isAccessibilityActive) "ACTIVE" else "PENDING",
                    subtitle = if (connectivityState.isAccessibilityActive) "Monitoring dialogs" else "Ready to auto-click",
                    icon = Icons.Default.AutoAwesome,
                    isActive = connectivityState.isAccessibilityActive,
                    activeColor = SuccessGreen
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live status guidance banner
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (connectivityState.isUsbConnected && connectivityState.isAdbEnabled) SuccessGreenBg else Color(0xFF0F172A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (connectivityState.isUsbConnected && connectivityState.isAdbEnabled) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (connectivityState.isUsbConnected && connectivityState.isAdbEnabled) SuccessGreen else WarningAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (connectivityState.isUsbConnected && connectivityState.isAdbEnabled) {
                            "Ready for scrcpy wired mirroring! Run watchdog script on PC."
                        } else if (!connectivityState.isUsbConnected) {
                            "Plug in USB cable connected to your PC to start mirroring."
                        } else {
                            "USB connected. Verifying and activating ADB bridge..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = if (connectivityState.isUsbConnected && connectivityState.isAdbEnabled) Color(0xFFD1FAE5) else Color(0xFFCBD5E1)
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectivityMetricTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    activeColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0F172A),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(if (isActive) activeColor.copy(alpha = 0.5f) else Color(0xFF334155))
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) activeColor else Color(0xFF64748B),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (isActive) activeColor else Color(0xFFCBD5E1)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 9.5.sp,
                color = Color(0xFF64748B),
                maxLines = 1
            )
        }
    }
}

@Composable
fun AlarmConfigCard(
    currentSoundTitle: String,
    volumeStatus: String,
    onPickCustomSound: () -> Unit,
    onResetToDefault: () -> Unit,
    onTestSound: () -> Unit,
    onStopSound: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alarm_config_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = "Alarm Icon",
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ALARM SOUND & AUTO-VOLUME",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E3A8A)
                ) {
                    Text(
                        text = "Auto-Max on Open",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF93C5FD),
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sound: $currentSoundTitle",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        fontSize = 12.5.sp
                    )
                    Text(
                        text = "$volumeStatus • Restores on Exit",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onPickCustomSound,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(32.dp)
                        .testTag("btn_pick_alarm_sound"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RescueCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Choose Sound", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onResetToDefault,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .testTag("btn_reset_default_alarm"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1))
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Default", fontSize = 11.sp)
                }

                Button(
                    onClick = onTestSound,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(32.dp)
                        .testTag("btn_preview_alarm"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Test", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onStopSound,
                    modifier = Modifier
                        .weight(0.7f)
                        .height(32.dp)
                        .testTag("btn_stop_alarm"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBanner(
    routineState: RoutineState,
    stepStatus: StepStatus,
    isScanning: Boolean,
    isConfirmed: Boolean,
    onCopyGrantCommand: () -> Unit
) {
    val bgColor: Color
    val borderColor: Color
    val iconVector = when {
        routineState is RoutineState.Error -> Icons.Default.Error
        routineState is RoutineState.Success -> Icons.Default.CheckCircle
        routineState is RoutineState.Running -> Icons.Default.Info
        else -> Icons.Default.Info
    }
    val iconColor: Color

    when (routineState) {
        is RoutineState.Success -> {
            bgColor = SuccessGreenBg
            borderColor = SuccessGreen
            iconColor = SuccessGreen
        }
        is RoutineState.Error -> {
            bgColor = ErrorRedBg
            borderColor = ErrorRed
            iconColor = ErrorRed
        }
        is RoutineState.Running -> {
            bgColor = WarningAmberBg
            borderColor = WarningAmber
            iconColor = WarningAmber
        }
        is RoutineState.Idle -> {
            bgColor = RescueSurfaceDark
            borderColor = RescueCardBorder
            iconColor = RescueCyan
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_banner"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (stepStatus.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = "Status Icon",
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = when (routineState) {
                        is RoutineState.Success -> if (isConfirmed) "Scrcpy Ready (Dialog Confirmed!)" else "All Settings OK - Plug in USB Cable"
                        is RoutineState.Error -> if (routineState.isPermissionMissing) "Permission Setup Required" else "Step ${routineState.step} Failed"
                        is RoutineState.Running -> "Configuring Zero-Touch Bridge..."
                        is RoutineState.Idle -> "Standby - Ready to Connect"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (routineState is RoutineState.Error) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = routineState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFD1D1)
                )

                if (routineState.isPermissionMissing) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onCopyGrantCommand,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag("copy_grant_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Command",
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy ADB Grant Command",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (routineState is RoutineState.Success) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isScanning) {
                        "USB Debugging enabled. Scanning for 'Allow USB debugging?' dialog (60s)..."
                    } else if (isConfirmed) {
                        "Auto-allow confirmed! Launch scrcpy from your PC."
                    } else {
                        "Developer options & ADB enabled. Alarm and vibration played."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD1FAE5)
                )
            }
        }
    }
}

@Composable
fun StepsCard(
    stepStatus: StepStatus,
    isAccessibilityConnected: Boolean,
    isScanningForDialog: Boolean,
    isDialogConfirmed: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("steps_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "AUTOMATION STEPS STATUS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )

            Spacer(modifier = Modifier.height(6.dp))

            StepRowItem(
                stepNum = 1,
                title = "Developer Options",
                subText = "Settings.Global.DEVELOPMENT_SETTINGS_ENABLED",
                isDone = stepStatus.step1DevOptions
            )

            StepRowItem(
                stepNum = 2,
                title = "USB Debugging",
                subText = "Settings.Global.ADB_ENABLED",
                isDone = stepStatus.step2AdbEnabled
            )

            StepRowItem(
                stepNum = 3,
                title = "Accessibility Auto-Allow",
                subText = if (isAccessibilityConnected) "Service Connected & Active" else "Writing Settings.Secure...",
                isDone = stepStatus.step3AccessibilityEnabled || isAccessibilityConnected
            )

            StepRowItem(
                stepNum = 4,
                title = "Sensory Feedback",
                subText = "10s Alarm Audio + Vibration (USAGE_ALARM)",
                isDone = stepStatus.step4AllOkAlarm
            )
        }
    }
}

@Composable
fun StepRowItem(
    stepNum: Int,
    title: String,
    subText: String,
    isDone: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (isDone) SuccessGreen else Color(0xFF475569)),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Done",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = "$stepNum",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
                fontSize = 10.5.sp
            )
        }

        Text(
            text = if (isDone) "VERIFIED" else "PENDING",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isDone) SuccessGreen else Color(0xFF94A3B8)
        )
    }
}

@Composable
fun DeviceProfileCard(
    profile: com.example.model.DeviceProfile,
    onRequestBatteryOptimization: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_profile_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ACTIVE DEVICE PROFILE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = RescueCyan
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF334155)
                ) {
                    Text(
                        text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = profile.notes,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Tip: In Developer Options, enable 'Disable adb authorization timeout' so authorization never expires.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                    fontSize = 10.5.sp
                )
            }

            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onRequestBatteryOptimization,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .testTag("btn_battery_unrestricted"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RescueCyan)
                ) {
                    Text(text = "Request Unrestricted Battery (Samsung)", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ActionButtonsRow(
    onRerunRoutine: () -> Unit,
    onSimulateSuccess: () -> Unit,
    onSimulateDialog: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = onRerunRoutine,
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .testTag("action_rerun_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RescueCyan)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Run Routine", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onSimulateSuccess,
            modifier = Modifier
                .weight(1.2f)
                .height(36.dp)
                .testTag("action_simulate_success_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Demo All OK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onSimulateDialog,
            modifier = Modifier
                .weight(1.1f)
                .height(36.dp)
                .testTag("action_simulate_dialog_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningAmber)
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text("Simulate Dialog", fontSize = 10.5.sp)
        }
    }
}

@Composable
fun TerminalLogViewer(
    logs: List<com.example.core.LogEntry>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier.testTag("terminal_log_viewer"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBackground),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE EXECUTION LOG",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B)
                )

                Text(
                    text = "${logs.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { entry ->
                    val color = when (entry.level) {
                        LogLevel.SUCCESS -> SuccessGreen
                        LogLevel.ERROR -> ErrorRed
                        LogLevel.WARN -> WarningAmber
                        LogLevel.INFO -> TerminalText
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            text = "[${entry.timestamp}] ",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "[${entry.level.name}] ",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                        Text(
                            text = entry.message,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = color
                        )
                    }
                }
            }
        }
    }
}
