# Windows Android: Zero-Touch Wired Recovery for Broken Displays

**Windows Android** is a native Android application engineered for phones with damaged, unresponsive, or shattered touchscreens. When opened (via Google Assistant voice command *"Open Windows Android"* or on launch) or when a USB cable / charger is plugged in, it automatically executes the zero-touch preparation routine for wired [scrcpy](https://github.com/Genymobile/scrcpy) screen mirroring.

---

## 🎯 Features & Core Routine

When launched or when USB/power is attached, the app executes the following sequence:

1. **Developer Options Check**: Reads `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`. Enables it via `putInt` if off and verifies the write.
2. **USB Debugging Check**: Reads `Settings.Global.ADB_ENABLED`. Enables it via `putInt` if off and verifies the write.
3. **Accessibility Service Registration**: Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and `ACCESSIBILITY_ENABLED`. Injects and enables the auto-allow service (`RescueAccessibilityService`) if not active and verifies the write.
4. **All Steps OK Sensory Feedback**: Plays a loud alarm sound and continuous vibration for **10 seconds**, bypassing silent mode via `AudioAttributes.USAGE_ALARM` and `VibrationEffect`.
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
   - Device restart (`BOOT_COMPLETED`).
8. **Live Connectivity Dashboard**:
   - Prominent real-time dashboard card at the top of the interface.
   - Displays live USB physical link status, USB Debugging (`ADB_ENABLED`) daemon state, power connection and battery level, and auto-allow Accessibility service readiness.
   - Live ContentObserver detects instantaneous changes to secure system settings.
9. **Interactive Setup Wizard & Recovery Guide**:
   - Tap the **Setup Wizard** button or TopAppBar icon to open the interactive assistant.
   - Includes **"Auto-Request & Configure All Permissions"** which automatically runs through runtime notification grants, battery optimization exemption prompts, and checks `WRITE_SECURE_SETTINGS` with 1-click clipboard copy.
   - Built-in **Recovery Guide** tab with zero-touch scrcpy instructions, Samsung Auto Blocker guidance, Sony Xperia notes, and hardware mouse/DeX fallbacks.
10. **Logging**:
   - Real-time in-app terminal console.
   - Persistent storage log file at `/data/user/0/com.aistudio.scrcpyprep.rzqx/files/scrcpy_rescue.log`.

---

## 🔑 Required One-Time Permission

Because modifying secure system settings requires system authorization, you must grant `WRITE_SECURE_SETTINGS` **once** via a computer terminal:

```bash
adb shell pm grant com.aistudio.scrcpyprep.rzqx android.permission.WRITE_SECURE_SETTINGS
```

---

## 📱 Device Setup Profiles

### 1. Sony Xperia XZ3 (Android 10, API 29)

The Xperia XZ3 runs a near-stock Android 10 interface with standard AOSP system dialogs.

- **Grant Command**:
  ```bash
  adb shell pm grant com.aistudio.scrcpyprep.rzqx android.permission.WRITE_SECURE_SETTINGS
  ```
- **Dialog IDs**: Standard AOSP IDs (`android:id/alwaysUse`, `android:id/button1`, package `com.android.systemui`).
- **Android 10 Architecture Note**:
  The setting *"Disable adb authorization timeout"* was introduced in Android 11 (API 30). Because the Xperia XZ3 runs Android 10 (API 29), that setting does not exist in its Developer Options and is automatically suppressed by the app.
- **Hardware Note**: The Xperia XZ3 USB Type-C port does not support DisplayPort (DP) Alt Mode / HDMI output. Wired `scrcpy` over USB 3.1 is the most reliable method to recover data and control the device.

---

### 2. Samsung Galaxy S21 FE (Android 14 / 15, One UI 6 & 7)

Samsung One UI enforces additional security layers that must be addressed during initial setup:

- **Grant Command**:
  ```bash
  adb shell pm grant com.aistudio.scrcpyprep.rzqx android.permission.WRITE_SECURE_SETTINGS
  ```
- **Auto Blocker (CRITICAL)**:
  - Starting with One UI 6.0 (Android 14), Samsung includes **Auto Blocker**, which prevents USB commands and unauthorized debugging.
  - If enabled, secure settings writes or USB commands will be rejected.
  - **Resolution**: Open **Settings** > **Security and privacy** > **Auto Blocker** and set it to **OFF**.
- **Restricted Settings (Accessibility)**:
  - Android 13+ restricts accessibility services for sideloaded apps.
  - If the accessibility service is blocked: Go to **Settings** > **Apps** > **Scrcpy Rescue** > tap the **3 dots** (top right) > **Allow restricted settings**.
- **Battery Optimization**:
  - Samsung aggressively freezes background services.
  - Set battery management to **Unrestricted**: **Settings** > **Apps** > **Scrcpy Rescue** > **Battery** > **Unrestricted** (or use the in-app shortcut button).
- **Adb Authorization Timeout**:
  - On Samsung devices running API 30+, go to **Settings** > **Developer options** > enable **Disable adb authorization timeout**. This ensures your PC remains permanently authorized.

---

## 🛠️ How to Build the APK

The application uses modern Android Gradle Plugin with Kotlin DSL.

1. Ensure JDK 17 (or JDK 11+) and Android SDK (Platform 35, Build Tools 35.0.0) are installed.
2. Build the debug APK:
   ```bash
   gradle assembleDebug
   ```
   The generated APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`
3. Install onto the device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Grant the required secure settings permission:
   ```bash
   adb shell pm grant com.aistudio.scrcpyprep.rzqx android.permission.WRITE_SECURE_SETTINGS
   ```

---

## 🖥️ Windows Watchdog Script (`scrcpy_watchdog.bat`)

A ready-to-run batch watchdog script is included in the project root:

1. Double-click `scrcpy_watchdog.bat` on Windows.
2. It resets the ADB server and continuously polls `adb devices`.
3. When the device is detected as `unauthorized`, it notifies you that the phone's accessibility service is auto-confirming the dialog.
4. When `device` state is achieved, it automatically opens `scrcpy` with optimized broken display flags:
   ```bash
   scrcpy -s <SERIAL> --stay-awake --turn-screen-off --power-off-on-close
   ```
5. If the cable is unplugged, it seamlessly resumes the waiting loop.

---

## 📋 Test Checklist

- [ ] **Step 1 (Dev Options)**: Verify `Settings.Global.getInt(cr, "development_settings_enabled")` returns `1`.
- [ ] **Step 2 (ADB)**: Verify `Settings.Global.getInt(cr, "adb_enabled")` returns `1`.
- [ ] **Step 3 (Accessibility)**: Verify `Settings.Secure.getString(cr, "enabled_accessibility_services")` contains `com.aistudio.scrcpyprep.rzqx/com.example.service.RescueAccessibilityService` and `accessibility_enabled` is `1`.
- [ ] **Live Connectivity Dashboard**: Verify that the top dashboard card updates in real-time when USB is plugged in, when ADB is turned on/off, and when power is attached.
- [ ] **Step 4 (Success Feedback)**: Verify a 10-second loud alarm plays and vibration is active even when the phone volume profile is set to Silent or Vibrate.
- [ ] **Step 5 (Error Feedback)**: Revoke `WRITE_SECURE_SETTINGS` (`adb shell pm revoke com.aistudio.scrcpyprep.rzqx android.permission.WRITE_SECURE_SETTINGS`) and verify that 3 short vibrations occur and the error card appears.
- [ ] **Step 6 (Timeout Feedback)**: Disconnect the PC during the 60-second dialog scan and verify 2 long vibrations fire after timeout.
- [ ] **Auto-Allow Dialog**: Connect to a new PC with ADB server running; confirm "Always allow from this computer" is checked and "Allow" is clicked within 1-2 seconds.
- [ ] **Google Assistant Voice Trigger**: Lock the device or go to home screen, say *"Hey Google, open Windows Android"*; verify the routine executes without touching the screen.
- [ ] **USB Plug Trigger**: Plug in the USB cable with screen off; verify the foreground service detects the connection and runs the routine.

---

## 💡 Known OEM Limitations & Hardware Fallbacks

1. **USB-OTG Mouse Fallback**:
   - If the app is not yet installed or permissions are not yet configured on a phone with dead touch, connect a USB-C to USB-A OTG adapter and plug in a standard USB wired or wireless mouse.
   - An on-screen mouse pointer will appear, allowing you to unlock the phone, enable USB debugging, or grant permissions manually.
2. **Samsung DeX & DisplayPort Alt Mode**:
   - Samsung Galaxy S21 FE supports DisplayPort over USB-C. Connecting a USB-C HDMI multi-port hub allows mirroring to an external monitor or TV while connecting a mouse and keyboard.
   - *Note*: Sony Xperia XZ3 does **not** support HDMI over USB-C, making wired `scrcpy` through this app the primary display extraction tool.
3. **Wireless Debugging (Android 11+)**:
   - If the USB port is damaged or loose, but the phone was previously paired with a PC on the same Wi-Fi network, `adb connect <IP>:<PORT>` can establish a connection as a fallback.
