# Windows Android: Zero-Touch Rescue & Connectivity Dashboard

Comprehensive verification and enhancement plan for the zero-touch broken screen recovery application targeting Sony Xperia XZ3 (Android 10) and Samsung Galaxy S21 FE (Android 14/15), featuring a prominent real-time connectivity status dashboard.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following user preferences were confirmed during clarification and will govern the dashboard presentation and feedback behavior:

- **Confirmed Decision 1 (Connectivity Dashboard)**: Implemented as a prominent, high-visibility card at the top of the screen featuring live status badges for USB physical connection, power charging state, ADB daemon status, and Accessibility Service authorization.
- **Confirmed Decision 2 (Sensory Feedback)**: When all preparation steps verify successfully, the app maximizes alarm volume and plays the loud alarm ringtone paired with haptic vibration for exactly 10 seconds, then restores the user's previous volume level.

---

## 1. Overview & Core Concept

- **What It Does**: "Windows Android" enables rapid zero-touch computer tethering and display mirroring via `scrcpy` for smartphones with shattered, flickering, or touch-unresponsive displays. Upon app open (or Google Assistant voice launch *"Windows Android"*), USB connection, power plug-in, or system reboot, the app automatically enables Developer Options, turns on USB debugging, activates its auto-allow accessibility service, and dismisses the computer RSA key prompt.
- **Target Audience & Devices**: Users recovering data or controlling broken phones—specifically tested on Sony Xperia XZ3 (AOSP-like Android 10) and Samsung Galaxy S21 FE (Samsung One UI Android 14/15).
- **Key Value**: Operates completely blind with zero touchscreen interaction required; provides non-visual sensory feedback (loud alarm and distinct vibration patterns) so the user knows exactly when the phone is ready for `scrcpy`.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Launch / Plug-In Flow**:
   - The user opens the app (or plugs in USB / says *"Hey Google, open Windows Android"*).
   - The foreground service immediately spins up, keeping the CPU awake and screen flags on.
   - The 3-step preparation routine executes automatically in under 500 ms.
2. **Dashboard & Status Visualization**:
   - A new **Live Connectivity & ADB Dashboard** card sits prominently at the top, showing real-time USB cable link, power connection state, ADB daemon toggle, and Accessibility engine state.
   - Quick-refresh actions and status indicators dynamically update whenever the cable is inserted or removed.
3. **Sensory Completion**:
   - As soon as all settings are verified, the phone blasts 10 seconds of alarm audio at 100% volume and continuous vibration.
   - When the user connects their PC, the Accessibility Service detects the "Allow USB debugging?" dialog within 500 ms, checks "Always allow from this computer", and clicks "Allow".
4. **Volume Restoration**:
   - When the user exits the app or scrcpy disconnects, the original alarm stream volume (even if previously muted or low) is restored.

### Visual Identity & Theme

- **Aesthetic Direction**: High-contrast, dark-mode terminal utility inspired by server dashboards and diagnostic tooling (`RescueSlate` #0F172A, `RescueSurfaceDark` #1E293B, cyan highlights #06B6D4, emerald success #10B981, amber warning #F59E0B, and rose red #EF4444).
- **Typography & Hierarchy**: Monospace logs for terminal execution entries, bold clean sans-serif for status badges and step titles, with micro-labels for system setting keys.
- **Components**: Polished Material 3 Cards with subtle border strokes, animated state indicators, pill badges for active profiles, and tactile touch targets exceeding 48 dp.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Real-Time Dynamic Broadcast Receivers Inside Foreground Service**
  - *Chosen Approach*: Register `android.hardware.usb.action.USB_STATE` and `ACTION_POWER_CONNECTED` dynamically inside `RescueForegroundService` instead of static manifest declarations.
  - *Why*: Modern Android (Android 8+ through 14+) blocks implicit broadcast delivery in the manifest for USB state changes. Registering inside a running foreground service with `RECEIVER_EXPORTED` complies with Android 14 security rules while ensuring reliable plug detection.
- **Decision 2: 3-Layer Dialog Auto-Click Strategy**
  - *Chosen Approach*: Layer 1 searches known view IDs (`android:id/alwaysUse`, `android:id/button1`). Layer 2 falls back to multi-lingual text scanning across 10 languages. Layer 3 calculates physical node coordinates and dispatches synthetic gesture taps via `dispatchGesture()`.
  - *Why*: Guarantees compatibility across different OEM skins (Sony AOSP style vs Samsung One UI dialog modifications) without requiring root or display input.
- **Decision 3: Non-Destructive Alarm Volume Memory**
  - *Chosen Approach*: Capture current stream volume before maxing out, mark state, and restore in `onStop`/`onDestroy` or audio termination.
  - *Why*: Respects user device state—rescuing a phone shouldn't permanently alter the user's alarm clock volume setting.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Hardware & System Events                        │
│   [USB Plug Attached]    [Power Connected]    [Boot Completed]   [App Launch]
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    RescueForegroundService (Sticky)                    │
│   • Partial WakeLock (Keep CPU active with screen off)                 │
│   • Dynamic BroadcastReceiver (USB_STATE & POWER_CONNECTED)           │
│   • Notification & Battery Exemption Manager                           │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │ triggers
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                             RescueEngine                               │
│   Step 1: DEVELOPMENT_SETTINGS_ENABLED (Read -> Write -> Verify)       │
│   Step 2: ADB_ENABLED (Read -> Write -> Verify)                        │
│   Step 3: ENABLED_ACCESSIBILITY_SERVICES (Read -> Inject -> Verify)    │
│   Step 4: HapticAndSoundFeedback (10s Max Alarm + 10s Vibration)       │
└──────────────┬──────────────────────────────────────────┬──────────────┘
               │                                          │
               ▼                                          ▼
┌──────────────────────────────┐          ┌──────────────────────────────┐
│  RescueAccessibilityService  │          │   MainActivity (UI Layer)    │
│  • 60s Dialog Scanning Loop  │          │  • Live Connectivity Card    │
│  • Layer 1: View ID Matching │          │  • Step Progress Badges      │
│  • Layer 2: Multi-Lang Text  │          │  • Alarm & Volume Controller │
│  • Layer 3: Coordinate Tap   │          │  • OEM Profile Info Card     │
│  • Ticks 'Always allow'      │          │  • Real-Time Terminal Logs   │
│  • Clicks 'Allow' Button     │          │  • Action & Simulation Row   │
└──────────────────────────────┘          └──────────────────────────────┘
```

### Data Model & State Mapping

- **Live Connectivity State (`ConnectivityState`)**:
  - `isUsbConnected: Boolean` (derived from USB_STATE and battery manager)
  - `isPowerConnected: Boolean` (AC/USB charging source)
  - `isAdbEnabled: Boolean` (live query of `Settings.Global.ADB_ENABLED`)
  - `isDevOptionsEnabled: Boolean` (`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`)
  - `isAccessibilityActive: Boolean` (StateFlow from `RescueAccessibilityService`)
- **Routine Progress (`StepStatus`)**:
  - Tracks verification state for Steps 1 through 4.
- **Audio & Haptic Feedback (`AlarmSoundManager`)**:
  - Custom URI, system default fallback, preserved original volume level.
- **Log Stream (`RescueLogger`)**:
  - In-memory StateFlow for terminal UI and append-only disk logging.

---

## 5. Planned Verification & Enhancements

1. **Connectivity Dashboard**: Add a high-visibility `ConnectivityDashboardCard` above the status banner showing live USB cable state, charging method, ADB server listening state, and accessibility service readiness.
2. **Settings Synchronization**: Ensure dynamic live updates when the user toggles USB debugging or plugs/unplugs the USB cable.
3. **Multi-device Testing & Compilation**: Run compilation and local Robolectric tests verifying state transitions, profile resolution for Sony vs Samsung, and accessibility node scanning.
