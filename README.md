# Windows Android: Zero-Touch Wired Recovery for Broken Displays

**Windows Android** (package: `com.aistudio.windowsandroid.uqdvkf`) is a native Android application engineered for phones with damaged, unresponsive, or shattered touchscreens. When opened (via Google Assistant voice command *"Open Windows Android"* or on launch) or when a USB cable / charger is plugged in, it automatically executes the zero-touch preparation routine for wired [scrcpy](https://github.com/Genymobile/scrcpy) screen mirroring.

---

## 🔒 Safe Storage, Direct Boot & Zero-Network Offline Architecture

In **Step 1**, the app's settings and logging system have been hardened for maximum reliability, durability, and privacy:

1. **Direct Boot & Reboot Survival (Pre-Unlock)**:
   - Android devices with File-Based Encryption (FBE) run in **Direct Boot** mode after a reboot until the user enters their PIN/password/pattern.
   - All app settings (alarm configuration, volume memory) and execution logs are stored in **Device-Protected Storage (`createDeviceProtectedStorageContext()`)**.
   - `BootReceiver` is registered with `android:directBootAware="true"` and listens to `android.intent.action.LOCKED_BOOT_COMPLETED` as well as standard boot events.
   - Even if the phone reboots while locked with a broken screen, the app immediately awakens, records logs, and arms the zero-touch USB recovery bridge!
2. **Zero-Network Isolation**:
   - `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` have been completely removed and blocked via `tools:node="remove"`.
   - The app does not run any HTTP or network socket servers. All communication is strictly hardware-based over USB, ADB, Accessibility, Sound, and Haptics.
3. **Backup Protection**:
   - `android:allowBackup="false"` and `android:fullBackupContent="false"` are enforced.
   - Custom `data_extraction_rules.xml` and `backup_rules.xml` strictly exclude all cloud backups and device transfers, keeping your device settings and rescue logs 100% private and local.
4. **PC Log Access**:
   - Logs are mirrored to the app's external files directory whenever accessible, and stored permanently in device-protected storage.
   - You can read the phone's rescue logs directly from your PC terminal via:
     ```bash
     # Recommended when device is unlocked:
     adb pull /sdcard/Android/data/com.aistudio.windowsandroid.uqdvkf/files/scrcpy_rescue.log ./scrcpy_rescue.log

     # Works even before phone is unlocked (Direct Boot mode):
     adb exec-out run-as com.aistudio.windowsandroid.uqdvkf cat files/scrcpy_rescue.log > scrcpy_rescue.log
     ```
   - `scrcpy_watchdog.bat` on PC also pulls the log automatically whenever the phone connects!

---

## 🎯 Features & Core Routine

When launched or when USB/power is attached, the app executes the following sequence:

1. **Developer Options Check**: Reads `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`. Enables it via `putInt` if off and verifies the write.
2. **USB Debugging Check**: Reads `Settings.Global.ADB_ENABLED`. Enables it via `putInt` if off and verifies the write.
3. **Accessibility Service Registration**: Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and `ACCESSIBILITY_ENABLED`. Injects and enables the auto-allow service (`RescueAccessibilityService`) if not active and verifies the write.
4. **All Steps OK Sensory Feedback**: Plays a loud alarm sound and continuous vibration for **10 seconds**, bypassing silent mode via `AudioAttributes.USAGE_ALARM` and `VibrationEffect` (only on PC USB connection; wall chargers are safely ignored).
5. **Step Failure Feedback**: Vibrates **3 short pulses** and displays/logs the specific failure and troubleshooting steps.
6. **Zero-Touch Auto-Allow Accessibility Service**:
   - Monitors for the `"Allow USB debugging?"` system authorization prompt.
   - Executes a **3-Layer click strategy**:
     - **Layer 1 (View IDs)**: `android:id/alwaysUse` (checkbox), `android:id/button1` (Allow button).
     - **Layer 2 (Multi-Language Text)**: Configurable multi-language matching (English, French, German, Spanish, Italian, Portuguese, Russian, Japanese, Korean, Chinese).
     - **Layer 3 (Coordinate Bounds Tap)**: Dispatches synthetic touch taps (`dispatchGesture`) directly at the on-screen center coordinates of the target node.
   - Ticks **"Always allow from this computer"** first, then taps **"Allow"**.
   - Retries every 500 ms for 60 seconds.
   - If not found within 60 seconds, signals **2 long vibrations**.
7. **Auto-Triggers**:
   - App launch (clean launcher activity compatible with Google Assistant: *"Hey Google, open Windows Android"*).
   - USB cable connection (dynamically registered receiver inside the foreground service).
   - Power cable plug-in.
   - Device restart (`LOCKED_BOOT_COMPLETED` & `BOOT_COMPLETED`).
8. **Live Connectivity Dashboard**:
   - Prominent real-time dashboard card at the top of the interface.
   - Displays live USB physical link status, USB Debugging (`ADB_ENABLED`) daemon state, power connection and battery level, and auto-allow Accessibility service readiness.
9. **Interactive Setup Wizard & Recovery Guide**:
   - Tap the **Setup Wizard** button or TopAppBar icon to open the interactive assistant.
   - Includes **"Auto-Request & Configure All Permissions"** which automatically runs through runtime notification grants, battery optimization exemption prompts, and checks `WRITE_SECURE_SETTINGS` with 1-click clipboard copy.
10. **Logging**:
    - Real-time in-app terminal console.
    - Persistent storage log file at `/data/user_de/0/com.aistudio.windowsandroid.uqdvkf/files/scrcpy_rescue.log` (Device-Protected Storage).
    - Mirror file at `/sdcard/Android/data/com.aistudio.windowsandroid.uqdvkf/files/scrcpy_rescue.log`.

---

## 🔑 Required One-Time Permission

Because modifying secure system settings requires system authorization, you must grant `WRITE_SECURE_SETTINGS` **once** via a computer terminal:

```bash
adb shell pm grant com.aistudio.windowsandroid.uqdvkf android.permission.WRITE_SECURE_SETTINGS
```

---

## 🖥️ Windows Watchdog Script (`scrcpy_watchdog.bat`)

A ready-to-run batch watchdog script is included in the project root:

1. Double-click `scrcpy_watchdog.bat` on Windows.
2. It resets the ADB server and continuously polls `adb devices`.
3. When the device is detected as `unauthorized`, it notifies you that the phone's accessibility service is auto-confirming the dialog.
4. When `device` state is achieved, it automatically pulls the phone's rescue log to `scrcpy_rescue.log` and launches `scrcpy`:
   ```bash
   scrcpy -s <SERIAL> --stay-awake --turn-screen-off --power-off-on-close
   ```
5. If the cable is unplugged, it seamlessly resumes the waiting loop.

---

## ⚡ Step 3: Reliable PC Detection, Non-Repeating Alarm & Crash-Proof Volume Restoration

In **Step 3**, connection detection and volume safety are elevated to production hardware reliability:

1. **Trustworthy PC Detection (Wall Chargers vs PC Host Ports)**:
   - Modern laptops and PC motherboards with USB-C / Thunderbolt / USB 3.1 CDP (Charging Downstream Port) provide >1.5A–3A, causing Android's `BatteryManager` to report `BATTERY_PLUGGED_AC`.
   - `ConnectivityMonitor` inspects the kernel USB gadget descriptor state via `android.hardware.usb.action.USB_STATE`:
     - **Configured State (`configured == true`)**: Wall chargers never negotiate USB descriptors or send `SET_CONFIGURATION` requests. Only a real PC/Host machine completes USB enumeration.
     - **ADB State (`adb == true`)**: ADB communication daemon active.
     - **Standard SDP (`BATTERY_PLUGGED_USB`)**: Standard computer USB port.
   - If plugged into a Wall Charger (`BATTERY_PLUGGED_AC`), `configured` and `adb` remain `false`. The alarm is strictly prevented from sounding!
   - If plugged into a PC USB-C fast-charging port, `configured == true` is verified, correctly identifying the PC host and allowing zero-touch recovery.

2. **Non-Repeating Alarm Latch & Restart Immunity**:
   - Uses an edge-triggered connection session latch in `RescueForegroundService`.
   - **No Alarm on App Restart**: On startup, if the phone is already plugged into a PC, it detects the existing connection and suppresses the alarm.
   - **No Endless Repeating**: While the cable remains plugged in, subsequent battery updates or USB gadget broadcasts will NOT repeat the alarm.
   - **Clean Session Reset**: Only after the cable is completely disconnected does the latch reset for the next plug-in event.

3. **Crash-Proof Original Volume Restoration**:
   - Before setting volume to 100%, the user's original volume level is committed to **Device-Protected Storage (`key_saved_original_volume`)** using synchronous `.commit()`.
   - **Fatal Crash Protection**: A global `UncaughtExceptionHandler` and JVM shutdown hook are installed to immediately restore original volume if any thread crashes.
   - **Startup Crash Recovery**: On app or service startup (`init()`), if an orphaned volume override flag is detected from a previous crash or OS process kill, the original volume is instantly restored to the hardware audio manager!

---

## 🚀 Step 4: Background Reliability, Direct Boot, Screen Wake & Killer Defense

In **Step 4**, the background engine has been reinforced for seamless multi-version stability across **Android 10 (Q) through Android 16 (Baklava)**:

1. **Pre-Unlock Direct Boot Auto-Start**:
   - `BootReceiver` is declared with `android:directBootAware="true"` and priority `1000`.
   - Listens for `LOCKED_BOOT_COMPLETED` (before user PIN/pattern entry), `BOOT_COMPLETED` (after unlock), `MY_PACKAGE_REPLACED`, `REBOOT`, and OEM fast boot actions (`QUICKBOOT_POWERON`).
   - Operates fully inside **Device-Protected (DE) Storage**, arming Developer Options, ADB, and the auto-allow service before the phone is ever unlocked!

2. **PC Connection Screen Wake (Android 10–16)**:
   - When plugged into a verified PC host, the app wakes up the device display:
     - **Hardware Screen WakeLock**: Engages `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` and `ON_AFTER_RELEASE` for 15 seconds.
     - **Full-Screen Intent Alert**: Posts a high-priority `USE_FULL_SCREEN_INTENT` notification with `CATEGORY_ALARM`, instructing the OS window manager to immediately illuminate the display over the lockscreen.
     - **Turn Screen On & Show When Locked**: `MainActivity` invokes `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and handles `onNewIntent` to keep the display active and visible over the keyguard.

3. **Battery Saving & Killer Immunity**:
   - **Modern Foreground Service Types**:
     - Android 10–13: Starts with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`.
     - Android 14–16: Starts with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
     - `startForeground()` is called immediately in `onCreate()` to eliminate ForegroundServiceDidNotStartInTimeException timeouts.
   - **START_STICKY & Auto-Resurrection**:
     - Service returns `START_STICKY`.
     - Overrides `onTaskRemoved()`: If swiped away from Recents, the service immediately reschedules an instant resurrection via `AlarmManager.setExactAndAllowWhileIdle()`.
   - **Continuous CPU WakeLock**: Holds `PowerManager.PARTIAL_WAKE_LOCK` to keep watchdog routines alive during Doze Mode.
   - **Battery Optimization Exemption**: Proactively monitors and prompts for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, rendering a live status banner in the dashboard.

---

## 📋 Test Checklist

- [ ] **Step 1 (Dev Options)**: Verify `Settings.Global.getInt(cr, "development_settings_enabled")` returns `1`.
- [ ] **Step 2 (ADB)**: Verify `Settings.Global.getInt(cr, "adb_enabled")` returns `1`.
- [ ] **Step 3 (Accessibility)**: Verify `Settings.Secure.getString(cr, "enabled_accessibility_services")` contains `com.aistudio.windowsandroid.uqdvkf/com.example.service.RescueAccessibilityService` and `accessibility_enabled` is `1`.
- [ ] **Step 4 (Trustworthy PC vs Wall Charger)**: Plug into an AC wall charger or power bank; verify that the app silences the alarm and identifies "AC Wall Charger". Plug into a PC port (including USB-C PD/CDP); verify that the alarm sounds once.
- [ ] **Step 5 (Anti-Repeat & Restart Test)**: Keep the phone connected to the PC and force-restart the app or service; verify that the alarm does NOT play on app restart or repeat endlessly.
- [ ] **Step 6 (Crash-Proof Volume Test)**: Verify that after alarm playback or simulated crash, original alarm volume is restored back to the user's initial level.
- [ ] **Step 7 (Direct Boot Pre-Unlock Test)**: Reboot the phone; verify that `BootReceiver` handles `LOCKED_BOOT_COMPLETED` and arms rescue in device-protected storage before entering user PIN.
- [ ] **Step 8 (PC Screen Wake Test)**: Lock the device with screen off; plug into PC USB and verify that the screen immediately wakes up and illuminates over the lockscreen.
- [ ] **Step 9 (Battery Saver Immunity Test)**: Swipe app from Recents; verify that `onTaskRemoved()` reschedules and resurrects the foreground service without losing USB detection.
- [ ] **PC Log Access Test**: Run `adb pull /sdcard/Android/data/com.aistudio.windowsandroid.uqdvkf/files/scrcpy_rescue.log` or `adb exec-out run-as com.aistudio.windowsandroid.uqdvkf cat files/scrcpy_rescue.log` from PC and verify the log text matches.
