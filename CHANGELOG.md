# Changelog

All notable changes to the Aero Fenix (`com.nextgen.agent`) project will be documented in this file.

## [v2.4.0] - 2026-10-18

### Added
- **Rohin Creator Biography**:
  - Full creator introduction for "who is your creator/creater/maker/developer/who made you" queries in both English and Tamil.
  - Dynamic age calculation using birth date April 21, 2008 and current local date/time.
  - Accurate biography including Thoothukudi Government Hospital birthplace, Kovilpatti upbringing, A.V. Primary School (1st to 5th), Nadar Higher Secondary School (6th to 12th), and Unnamalai Institute of Technology (B.E. CSE, 2029 passout).
- **Tamil & English Exclusivity (Removed Japanese)**:
  - Removed `ja-JP` and extra languages across settings, voice recognizer, spoken TTS dictionaries, and brain parser; focused strictly on Tamil (`ta-IN`) and English (`en-US`).
- **Female Voice Exclusivity**:
  - Locked voice synthesis strictly to high-quality female TTS voice; removed male voice selector from settings.
- **App Lock Voice Command Blocker**:
  - Guarded `MainActivity.handleUser()` to reject incoming voice and chat commands when the app is locked, directing user directly to biometric/PIN unlock.
- **Message Send Confirmation**:
  - Added message composition and chat sending actions (`chat`, `ui_type`) into `isSensitive()` security prompt flow.
- **Top Wheel & Down Wheel Voice & Chat Activation**:
  - Voice and chat command support for opening and closing Top Corner Wheel and Down/Bottom Corner Wheel menus.
  - Supports English and Tamil phrasing: `"actice top wheel"`, `"active top wheel"`, `"top wheel"`, `"top wheel open pannu"`, `"active down wheel"`, `"actice down wheel"`, `"down wheel open pannu"`, `"close top wheel"`, `"close down wheel"`.
  - Added localized voice responses in Tamil (`ta-IN`) and English (`en-US`).
  - Unit tests covering 80+ assertions in `test_brain.py`.
- **Smart UI Agent Loop**:
  - `AgentAccessibilityService.getCompactScreenSnapshot()` with password masking and structural hierarchy.
  - `Brain.decideUiAction()` integration with LLM providers for single-step UI interaction.
  - Confirmation prompt for sensitive actions (`DeviceController.isSensitive`).
- **PIN App Lock**:
  - `AppLockDialog.kt` with SHA-256 salted PIN protection.
  - 5-attempt rate-limiting and 30-second exponential cooldown lockout.
  - Fail-closed voice match security when locked.
- **Setup Checklist Dialog**:
  - `SetupChecklistDialog.kt` presenting 9 live permission and accessibility service status indicators.
  - Clear steps for Android 13+ restricted settings (Three dots -> Allow restricted settings).
- **Verified Hardware Controls**:
  - Truthful state validation on hardware toggles (`WifiManager`, `BluetoothAdapter`, `Flashlight`, `Volume`, `Brightness`).
  - Safe error handling without crashing when hardware operations are denied or unsupported.

### Fixed
- **CI Build Failure Fixes**:
  - Fixed duplicate/unresolved icons in Jetpack Compose (`CheckCircle`, `Lock`, `AdminPanelSettings`, `Tune`, `Build`, `Star`, `List`).
  - Corrected overlay permission check from `canDrawOverApps` to `Settings.canDrawOverlays(Context)`.
  - Added `@JvmStatic` and `@JvmOverloads` for clean Kotlin-to-Java interop on `MouseButtonManager`.
  - Fixed return type handling of screen snapshots (`JSONObject.toString()`).
- **Safety Command Isolation**:
  - Isolated `"code dark shadow"` safety trigger to only match exact whole-utterance commands.
