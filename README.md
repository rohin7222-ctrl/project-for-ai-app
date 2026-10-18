<p align="center">
  <img src="logo.png" alt="Aero Fenix logo" width="220">
</p>

# Aero Fenix (Universal Phone Controller & Agent)

Aero Fenix is an ultra-capable, universal voice & touch Android operating system controller and autonomous assistant. Built using Jetpack Compose, high-performance Java device automation, and on-device Python (via Chaquopy).

## 🚀 Key Features

### 1. Universal Hands-Free Device & App Control
- **100% On-Device Local Command Parser**: Phone commands run locally with 0 API calls or external latency in `brain.py`.
- **Smart UI Mode**: When a screen requires universal UI navigation, takes a compact accessibility snapshot and executes structured UI steps (`click`, `type`, `scroll`, `back`, `done`).
- **Autonomous 3-Ring Missed Call**: Place a missed call to any contact or phone number; automatically cuts the call exactly after 3 rings.
- **Auto Skip-Ad Engine**: Automatically skips video advertisements across supported video players.
- **Auto-Reels Cruise Control**: Hands-free continuous vertical scrolling for Reels & Shorts.
- **Form Auto-Fill & OTP Auto-Grabber**: Detects OTPs from notifications and auto-enters verification codes.

### 2. Precision Virtual Mouse & Trackpad HUD
- **Dedicated Trackpad HUD**: Complete precision touch surface with customizable scale, alpha, and haptic feedback.
- **4-Way Directional D-Pad**: Instant Up, Down, Left, and Right smooth scrolling.
- **AI Magnetic Snapping**: Pointer dynamically pulls towards interactive screen elements for effortless clicking.

### 3. Fortified Security & Privacy
- **Cleartext HTTP Blocked**: Strict TLS enforcement (`usesCleartextTraffic="false"`).
- **App Lock (PIN / Biometric)**: SHA-256 hashed PIN challenge with 5-attempt rate-limiting and 30-second lockout.
- **Sensitive Action Guard**: Prompts user confirmation before executing financial transactions, file deletions, or unauthorized calls.
- **Strict Voice Match**: Fail-closed voice biometric matching.
- **Safe Self-Uninstall**: Protected safety command triggered only on whole-utterance match.

### 4. Setup Readiness & Accessibility Checklist
- **System Setup Checklist**: Live status indicators for Accessibility, Overlay, Notification Listener, Battery Optimization, Mic, Contacts, Phone, SMS, and Bluetooth.
- **Android 13+ Restricted Settings Guide**: Visual walkthrough for granting accessibility on newer Android OS versions.

### 5. Multi-Wake Word & Resilient Audio Engine
- **Wake Words**: Responds to *"Aero Fenix"*, *"Hey Fenix"*, *"Fenix"*, *"Jarvis"*, or your custom wake phrase.
- **Audio Protection**: Automatic audio volume restoration ensures notification sounds and beeps are never left muted.

---

## 🛠️ Architecture & Tech Stack

- **Kotlin (Jetpack Compose UI)**: `MainActivity.kt`, `MainScreen.kt`, `SettingsDialog.kt`, `SetupChecklistDialog.kt`, `AppLockDialog.kt`, `Voice.kt`, `Brain.kt`, `Store.kt`.
- **Java (System Automation & Accessibility)**: `DeviceController.java`, `AgentAccessibilityService.java`, `MissedCallManager.kt`, `EarToCallManager.kt`.
- **Python via Chaquopy**: `brain.py` (NLP parser, offline intent matching, LLM cloud connector for chat).

---

## 🧪 Testing & Verification

1. **Python NLP Parser Tests**:
   ```bash
   python app/src/main/python/test_brain.py
   ```
   *Runs 64 automated command sentence assertions across English, Tanglish, and Tamil.*

2. **Gradle Build Verification**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **GitHub Actions CI**:
   - Push to `main` branch automatically triggers `.github/workflows/build.yml` to produce `app-debug.apk`.
