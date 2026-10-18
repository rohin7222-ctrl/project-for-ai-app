# Changelog

All notable changes to the Aero Fenix (`com.nextgen.agent`) project will be documented in this file.

## [v2.4.0] - 2026-10-18

### Added
- **Top Wheel & Down Wheel Voice & Chat Activation**:
  - Voice and chat command support for opening and closing Top Corner Wheel and Down/Bottom Corner Wheel menus.
  - Supports English and Tamil phrasing: `"actice top wheel"`, `"active top wheel"`, `"top wheel"`, `"top wheel open pannu"`, `"active down wheel"`, `"actice down wheel"`, `"down wheel open pannu"`, `"close top wheel"`, `"close down wheel"`.
  - Added localized voice responses in Tamil (`ta-IN`), English (`en-US`), Japanese (`ja-JP`), and Hindi (`hi-IN`).
  - Unit tests covering 75 assertions in `test_brain.py`.
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
