package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.core.ConnectivityMonitor
import com.example.core.RescueEngine
import com.example.service.RescueAccessibilityService
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ErrorRedBg
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

@Composable
fun PermissionWizardModal(
    onDismiss: () -> Unit,
    onRerunRoutine: () -> Unit
) {
    val context = LocalContext.current
    val isSamsung = remember { Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
    val totalSteps = if (isSamsung) 5 else 4

    var currentStep by remember { mutableIntStateOf(1) }
    var viewMode by remember { mutableStateOf(0) } // 0 = Sequential Wizard, 1 = Full Guide

    // Live permission states
    var isNotificationGranted by remember { mutableStateOf(checkNotificationPermission(context)) }
    var isBatteryOptIgnored by remember { mutableStateOf(checkBatteryOptimization(context)) }
    var isWriteSecureGranted by remember { mutableStateOf(RescueEngine.hasWriteSecureSettingsPermission(context)) }
    var isAccessibilityActive by remember { mutableStateOf(RescueAccessibilityService.isConnected()) }

    fun refreshStates() {
        isNotificationGranted = checkNotificationPermission(context)
        isBatteryOptIgnored = checkBatteryOptimization(context)
        isWriteSecureGranted = RescueEngine.hasWriteSecureSettingsPermission(context)
        isAccessibilityActive = RescueAccessibilityService.isConnected()
        ConnectivityMonitor.refresh(context)
    }

    // Auto-advance logic if current step becomes granted
    LaunchedEffect(isNotificationGranted, isBatteryOptIgnored, isWriteSecureGranted, isAccessibilityActive) {
        if (currentStep == 1 && isNotificationGranted) {
            // Keep on 1 or ready to advance
        }
    }

    // Launcher for POST_NOTIFICATIONS
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isNotificationGranted = granted
        refreshStates()
        if (granted) {
            Toast.makeText(context, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            if (currentStep == 1) currentStep = 2
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .testTag("permission_wizard_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = RescueSlate,
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = RescueCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "SETUP WIZARD",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (viewMode == 0) "Step-by-step sequential permission setup" else "Complete zero-touch rescue guide",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { refreshStates() },
                            modifier = Modifier.size(32.dp).testTag("btn_wizard_refresh_states")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Status",
                                tint = RescueCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp).testTag("btn_close_wizard")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // View Mode Tabs: Sequential Wizard vs Recovery Guide
                TabRow(
                    selectedTabIndex = viewMode,
                    containerColor = RescueSurfaceDark,
                    contentColor = RescueCyan,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[viewMode]),
                            color = RescueCyan
                        )
                    }
                ) {
                    Tab(
                        selected = viewMode == 0,
                        onClick = { viewMode = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sequential Wizard (Step $currentStep of $totalSteps)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                    Tab(
                        selected = viewMode == 1,
                        onClick = { viewMode = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Recovery Guide", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (viewMode == 0) {
                    // Sequential Stepper Header Progress
                    WizardStepperProgress(
                        currentStep = currentStep,
                        totalSteps = totalSteps,
                        step1Done = isNotificationGranted,
                        step2Done = isBatteryOptIgnored,
                        step3Done = isWriteSecureGranted,
                        step4Done = isAccessibilityActive,
                        step5Done = false,
                        isSamsung = isSamsung,
                        onSelectStep = { step -> currentStep = step }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Step Content Container
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (currentStep) {
                            1 -> Step1NotificationView(
                                isGranted = isNotificationGranted,
                                onRequest = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        Toast.makeText(context, "Notifications enabled by default on Android 12 and below.", Toast.LENGTH_SHORT).show()
                                        refreshStates()
                                    }
                                }
                            )
                            2 -> Step2BatteryOptimizationView(
                                isIgnored = isBatteryOptIgnored,
                                onRequest = {
                                    requestBatteryOptimizationExemption(context)
                                    refreshStates()
                                }
                            )
                            3 -> Step3SecureSettingsAdbView(
                                isGranted = isWriteSecureGranted,
                                onCopy = { copyCommandToClipboard(context) },
                                onVerify = {
                                    refreshStates()
                                    if (isWriteSecureGranted) {
                                        Toast.makeText(context, "WRITE_SECURE_SETTINGS is GRANTED!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Not yet granted. Execute the ADB command in your PC terminal.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                            4 -> Step4AccessibilityView(
                                isAccessibilityActive = isAccessibilityActive,
                                isWriteSecureGranted = isWriteSecureGranted,
                                onOpenSettings = { openAccessibilitySettings(context) },
                                onAutoEnable = {
                                    onRerunRoutine()
                                    refreshStates()
                                }
                            )
                            5 -> Step5SamsungAutoBlockerView(
                                onOpenSettings = { openAppSettings(context) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Navigation Controls Bar
                    WizardNavigationFooter(
                        currentStep = currentStep,
                        totalSteps = totalSteps,
                        onPrev = { if (currentStep > 1) currentStep-- },
                        onNext = {
                            refreshStates()
                            if (currentStep < totalSteps) currentStep++
                        },
                        onFinish = {
                            refreshStates()
                            onRerunRoutine()
                            onDismiss()
                        }
                    )
                } else {
                    // Full Help and Recovery Guide
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        RecoveryGuideTab()
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepperProgress(
    currentStep: Int,
    totalSteps: Int,
    step1Done: Boolean,
    step2Done: Boolean,
    step3Done: Boolean,
    step4Done: Boolean,
    step5Done: Boolean,
    isSamsung: Boolean,
    onSelectStep: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("wizard_stepper_progress"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STEP $currentStep OF $totalSteps: " + when (currentStep) {
                        1 -> "Notification Alerts"
                        2 -> "Battery Exemption"
                        3 -> "Secure Settings via ADB"
                        4 -> "Accessibility Auto-Allow"
                        5 -> "Samsung Auto Blocker"
                        else -> "Configuration"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RescueCyan,
                    fontSize = 11.sp
                )

                Text(
                    text = "${((currentStep.toFloat() / totalSteps.toFloat()) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Step bubbles row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepBubble(step = 1, title = "Alerts", isDone = step1Done, isCurrent = currentStep == 1) { onSelectStep(1) }
                StepDivider(isDone = step1Done)
                StepBubble(step = 2, title = "Battery", isDone = step2Done, isCurrent = currentStep == 2) { onSelectStep(2) }
                StepDivider(isDone = step2Done)
                StepBubble(step = 3, title = "ADB Grant", isDone = step3Done, isCurrent = currentStep == 3) { onSelectStep(3) }
                StepDivider(isDone = step3Done)
                StepBubble(step = 4, title = "Access.", isDone = step4Done, isCurrent = currentStep == 4) { onSelectStep(4) }
                if (isSamsung) {
                    StepDivider(isDone = step4Done)
                    StepBubble(step = 5, title = "Samsung", isDone = step5Done, isCurrent = currentStep == 5) { onSelectStep(5) }
                }
            }
        }
    }
}

@Composable
private fun StepBubble(
    step: Int,
    title: String,
    isDone: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val bgColor = when {
        isCurrent -> RescueCyan
        isDone -> SuccessGreen
        else -> Color(0xFF334155)
    }
    val contentColor = when {
        isCurrent -> Color.Black
        isDone -> Color.White
        else -> Color(0xFF94A3B8)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = "$step",
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            fontSize = 9.sp,
            color = if (isCurrent) RescueCyan else Color(0xFF94A3B8),
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun StepDivider(isDone: Boolean) {
    Box(
        modifier = Modifier
            .width(18.dp)
            .height(2.dp)
            .background(if (isDone) SuccessGreen else Color(0xFF475569))
    )
}

// -------------------------------------------------------------
// STEP 1: Notifications View
// -------------------------------------------------------------
@Composable
private fun Step1NotificationView(
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("step_1_notifications_view")
    ) {
        StepCardHeader(
            stepNumber = 1,
            title = "Foreground Notification Permission",
            isDone = isGranted,
            icon = Icons.Default.Notifications
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Why this is required:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Windows Android runs a persistent background recovery service with the screen off. Android 13+ (API 33+) requires notification permission so the watchdog can keep running and listening for USB plug events when your phone display is broken.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(if (isGranted) SuccessGreen else RescueCyan)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Status:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isGranted) SuccessGreenBg else Color(0xFF334155)
                    ) {
                        Text(
                            text = if (isGranted) "GRANTED (READY)" else "ACTION REQUIRED",
                            color = if (isGranted) SuccessGreen else WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("btn_grant_notifications"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGranted) SuccessGreen else RescueCyan
                    )
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (isGranted) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isGranted) "Permission Granted ✓" else "Request Notification Permission",
                        color = if (isGranted) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 2: Battery Optimization View
// -------------------------------------------------------------
@Composable
private fun Step2BatteryOptimizationView(
    isIgnored: Boolean,
    onRequest: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("step_2_battery_view")
    ) {
        StepCardHeader(
            stepNumber = 2,
            title = "Unrestricted Battery Exemption",
            isDone = isIgnored,
            icon = Icons.Default.BatteryAlert
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Why this is required:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "OEMs (especially Samsung One UI and Sony Doze) aggressively kill background processes when the screen turns off. Granting battery optimization exemption ensures the USB monitor doesn't sleep while waiting for your cable connection.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(if (isIgnored) SuccessGreen else RescueCyan)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Status:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isIgnored) SuccessGreenBg else Color(0xFF334155)
                    ) {
                        Text(
                            text = if (isIgnored) "UNRESTRICTED (READY)" else "OPTIMIZED (RESTRICTED)",
                            color = if (isIgnored) SuccessGreen else WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("btn_request_battery_exemption"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isIgnored) SuccessGreen else RescueCyan
                    )
                ) {
                    Icon(
                        imageVector = if (isIgnored) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = if (isIgnored) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isIgnored) "Battery Exemption Active ✓" else "Request 'Allow in Background' Prompt",
                        color = if (isIgnored) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 3: WRITE_SECURE_SETTINGS via ADB View (Centerpiece)
// -------------------------------------------------------------
@Composable
private fun Step3SecureSettingsAdbView(
    isGranted: Boolean,
    onCopy: () -> Unit,
    onVerify: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var selectedShell by remember { mutableIntStateOf(0) } // 0 = Windows CMD / PowerShell, 1 = macOS / Linux
    var hasCopied by remember { mutableStateOf(false) }

    val cmdWindows = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    val cmdPowerShell = ".\\adb.exe shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    val cmdMacLinux = "./adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    val activeCommand = when (selectedShell) {
        0 -> cmdWindows
        1 -> cmdPowerShell
        else -> cmdMacLinux
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("step_3_adb_view")
    ) {
        StepCardHeader(
            stepNumber = 3,
            title = "Secure Settings Permission (via ADB)",
            isDone = isGranted,
            icon = Icons.Default.Security
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Why this is critical:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "WRITE_SECURE_SETTINGS is the secret sauce of zero-touch recovery. It gives the app authorization to auto-toggle Developer Options and USB Debugging without any screen touches. Android requires granting it ONCE via computer terminal.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Live Status Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Permission Status:",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isGranted) SuccessGreenBg else ErrorRedBg
            ) {
                Text(
                    text = if (isGranted) "GRANTED (READY)" else "SETUP REQUIRED",
                    color = if (isGranted) SuccessGreen else ErrorRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Platform Terminal Tab Selector
        TabRow(
            selectedTabIndex = selectedShell,
            containerColor = Color(0xFF1E293B),
            contentColor = RescueCyan,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedShell]),
                    color = RescueCyan
                )
            }
        ) {
            Tab(
                selected = selectedShell == 0,
                onClick = { selectedShell = 0 },
                text = { Text("Windows CMD", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedShell == 1,
                onClick = { selectedShell = 1 },
                text = { Text("PowerShell", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedShell == 2,
                onClick = { selectedShell = 2 },
                text = { Text("macOS / Linux", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Code Box Container with 1-Tap Copy
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = TerminalBackground,
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TERMINAL COMMAND",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.5.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                copyTextToClipboard(context, activeCommand)
                                hasCopied = true
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (hasCopied) Icons.Default.Done else Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = if (hasCopied) SuccessGreen else RescueCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (hasCopied) "Copied!" else "Copy",
                            color = if (hasCopied) SuccessGreen else RescueCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = activeCommand,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3-Step Execution Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "How to run it from your PC in 30 seconds:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
                Spacer(modifier = Modifier.height(4.dp))
                InstructionLine(num = 1, text = "Connect your phone to your PC using a USB cable.")
                InstructionLine(num = 2, text = "Open Command Prompt / Terminal on your PC.")
                InstructionLine(num = 3, text = "Paste the command above and press Enter.")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action Buttons Row: Copy and Verify
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    copyTextToClipboard(context, activeCommand)
                    hasCopied = true
                },
                modifier = Modifier
                    .weight(1.2f)
                    .height(38.dp)
                    .testTag("btn_copy_command_step3"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RescueCyan)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Copy Command", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onVerify,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("btn_verify_command_step3"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) SuccessGreen else WarningAmber
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isGranted) "Verified ✓" else "Verify Now",
                    color = Color.Black,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 4: Accessibility Auto-Allow View
// -------------------------------------------------------------
@Composable
private fun Step4AccessibilityView(
    isAccessibilityActive: Boolean,
    isWriteSecureGranted: Boolean,
    onOpenSettings: () -> Unit,
    onAutoEnable: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("step_4_accessibility_view")
    ) {
        StepCardHeader(
            stepNumber = 4,
            title = "Accessibility Auto-Allow Service",
            isDone = isAccessibilityActive,
            icon = Icons.Default.Accessibility
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Why this is required:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "When connecting to a new PC, Android pops up: 'Allow USB debugging from this computer?'. This accessibility service automatically checks 'Always allow' and taps 'Allow' within 500 ms so you never need to touch the broken screen.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(if (isAccessibilityActive) SuccessGreen else RescueCyan)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Status:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isAccessibilityActive) SuccessGreenBg else Color(0xFF334155)
                    ) {
                        Text(
                            text = if (isAccessibilityActive) "ACTIVE & LISTENING" else "INACTIVE",
                            color = if (isAccessibilityActive) SuccessGreen else WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isWriteSecureGranted) {
                    Text(
                        text = "Good news: Since WRITE_SECURE_SETTINGS is granted, Windows Android can automatically inject and enable this service without going through settings!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD1FAE5),
                        fontSize = 10.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onAutoEnable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .testTag("btn_auto_enable_accessibility"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAccessibilityActive) "Service Active ✓" else "Auto-Enable Accessibility Now",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Text(
                        text = "Note: If you have not yet run the Step 3 ADB command, you can manually toggle this service on in Accessibility Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .testTag("btn_open_accessibility_settings"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RescueCyan)
                    ) {
                        Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Accessibility Settings", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// STEP 5: Samsung Auto Blocker View (Samsung only)
// -------------------------------------------------------------
@Composable
private fun Step5SamsungAutoBlockerView(
    onOpenSettings: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("step_5_samsung_view")
    ) {
        StepCardHeader(
            stepNumber = 5,
            title = "Samsung Auto Blocker & Restricted Settings",
            isDone = false,
            icon = Icons.Default.Warning
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1515)),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(WarningAmber))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "CRITICAL FOR SAMSUNG ONE UI 6 & 7",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1. Turn OFF Auto Blocker:\nSamsung One UI introduced Auto Blocker, which blocks all USB commands and debugging. Go to: Settings > Security and privacy > Auto Blocker and set to OFF.\n\n" +
                           "2. Restricted Settings:\nOn Android 13+, Samsung blocks Accessibility for sideloaded apps. If blocked, open App Info > tap 3 dots (top right) > tap 'Allow restricted settings'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFED7AA),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("btn_open_app_details_samsung"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningAmber)
                ) {
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open App Details Settings", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Shared Sub-components
// -------------------------------------------------------------
@Composable
private fun StepCardHeader(
    stepNumber: Int,
    title: String,
    isDone: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isDone) SuccessGreen else RescueCyan),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDone) Icons.Default.Check else icon,
                contentDescription = null,
                tint = if (isDone) Color.White else Color.Black,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "STEP $stepNumber",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDone) SuccessGreen else RescueCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun InstructionLine(num: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$num. ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = RescueCyan,
            fontSize = 11.sp
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun WizardNavigationFooter(
    currentStep: Int,
    totalSteps: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard_nav_footer"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentStep > 1) {
            OutlinedButton(
                onClick = onPrev,
                modifier = Modifier.height(38.dp).testTag("btn_wizard_prev"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Previous", fontSize = 11.5.sp)
            }
        } else {
            Spacer(modifier = Modifier.width(10.dp))
        }

        if (currentStep < totalSteps) {
            Button(
                onClick = onNext,
                modifier = Modifier.height(38.dp).testTag("btn_wizard_next"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RescueCyan)
            ) {
                Text("Next Step", color = Color.Black, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            Button(
                onClick = onFinish,
                modifier = Modifier.height(38.dp).testTag("btn_wizard_finish"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Finish & Start Routine", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecoveryGuideTab() {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag("recovery_guide_tab_view")
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "How Zero-Touch Recovery Works",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RescueCyan
                )
                Spacer(modifier = Modifier.height(6.dp))
                GuideBullet("1. Plug in USB cable", "Connect your broken phone to your PC via a USB cable.")
                GuideBullet("2. Automatic Activation", "The phone detects the cable or app launch, sets volume to 100%, enables Developer Options & USB Debugging.")
                GuideBullet("3. Sound Confirmation", "A loud 10-second alarm and vibration will sound when ready—no screen needed.")
                GuideBullet("4. Auto-Click Prompt", "When scrcpy connects, the app's accessibility service auto-ticks 'Always allow' and taps 'Allow'.")
                GuideBullet("5. Run PC Watchdog", "Double-click 'scrcpy_watchdog.bat' on your PC to automatically mirror and control the display.")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Device-Specific Instructions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sony Xperia XZ3 (Android 10):",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Uses near-stock AOSP dialogs. Note that 'Disable adb authorization timeout' does not exist on Android 10.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Samsung Galaxy S21 FE (Android 14/15):",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "• Auto Blocker MUST be turned OFF (Settings > Security and privacy > Auto Blocker).\n" +
                           "• Set Battery to 'Unrestricted' in App Settings.\n" +
                           "• Allow 'Restricted settings' if Accessibility is blocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = RescueSurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RescueCardBorder))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Hardware Fallbacks (Dead Touch)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RescueCyan
                )
                Spacer(modifier = Modifier.height(6.dp))
                GuideBullet("USB-OTG Mouse", "Plug in a USB-C OTG mouse adapter to see an on-screen cursor and control the phone directly.")
                GuideBullet("Samsung DeX / HDMI", "Galaxy S21 FE supports DisplayPort over USB-C to mirror to any monitor or TV (Note: Xperia XZ3 does not).")
            }
        }
    }
}

@Composable
private fun GuideBullet(title: String, body: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 11.5.sp
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8),
            fontSize = 10.5.sp
        )
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun checkBatteryOptimization(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

private fun requestBatteryOptimizationExemption(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        } else {
            Toast.makeText(context, "Battery optimizations already ignored!", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun copyCommandToClipboard(context: Context) {
    val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    copyTextToClipboard(context, command)
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("ADB Grant Command", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied ADB command to clipboard!", Toast.LENGTH_SHORT).show()
}

private fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Accessibility Settings: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun openAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open App Details: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
