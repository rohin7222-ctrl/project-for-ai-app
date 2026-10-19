# Changelog

All notable changes to the Aero Fenix (`com.nextgen.agent`) project will be documented in this file.

## [v2.4.2] - 2026-10-19

### Fixed
- **Universal Cross-Brand OEM Speech Recognition (Oppo, Realme, Redmi/Xiaomi, Vivo, Motorola, Pixel, Nothing)**:
  - **Dynamic Recognition Service Binding (`findBestRecognitionService`)**: Fixed `SpeechRecognizer.createSpeechRecognizer(ctx)` failing with `ERROR_CLIENT` (Error 5) on Oppo (ColorOS), Realme (RealmeUI), Redmi/Xiaomi (MIUI/HyperOS), and Vivo (FuntouchOS/OriginOS) by dynamically querying system `RecognitionService` and binding directly to `com.google.android.googlequicksearchbox`, `com.google.android.tts`, or system default instead of relying on empty `Settings.Secure.VOICE_RECOGNITION_SERVICE`.
  - **Android 11+ Package Visibility (`<queries>`)**: Declared explicit package queries in `AndroidManifest.xml` for `com.google.android.googlequicksearchbox`, `com.google.android.tts`, `com.samsung.android.bixby.agent`, and `com.samsung.SMT` to guarantee speech services are visible across all modern Android versions.
  - **Cloud Speech Fallback (`EXTRA_PREFER_OFFLINE = false`)**: Prevented `ERROR_LANGUAGE_UNAVAILABLE` (Error 13) on Pixel, Motorola, and Nothing devices lacking offline Tamil speech models by allowing cloud speech recognition.
  - **Automatic Language Fallback on Error 12/13**: Handled missing regional language pack errors gracefully by falling back to `en-IN` instead of aborting speech capture.
  - **Stream Volume Mutex Glitch**: Removed destructive `STREAM_SYSTEM` volume manipulation in `muteBeep()` that triggered `SecurityException` (Zen/DND mode) and broke audio recording buffers on ColorOS, RealmeUI, and Pixel.
  - **Android 14 Foreground Service Security**: Added `RECORD_AUDIO` permission check before starting `VoiceForegroundService` to prevent crash on Android 14+ devices.

## [v2.4.1] - 2026-10-19

### Fixed
- **Microphone Audio & Speech Capture Issue**:
  - Fixed SpeechRecognizer rapid destruction and re-creation thrashing loop; now cleanly reuses recognizer instance and prevents Android mic lock contention and zeroed audio buffers.
  - Fixed `isMediaPlaying()` reflection failure which caused false positives and constantly killed active mic sessions every 500ms via `checkMediaState()`.
  - Guaranteed `voice.active` mode is never interrupted or blocked by media playback callbacks when user explicitly taps the microphone.
  - Fixed RecognizerIntent extra compatibility: replaced invalid `"android.speech.extra.AUDIO_SOURCE"` and `"android.speech.extra.DICTATION_MODE"`, changed `EXTRA_ADDITIONAL_LANGUAGES` from `String[]` to `ArrayList<String>` to prevent internal ClassCastExceptions.
  - Relaxed silence and minimum speech thresholds to prevent premature cutoffs on short Tamil and English phrases.
  - Fixed voice biometric verification to avoid discarding speech when raw audio buffers are not emitted by Google SpeechRecognizer.
  - Made mic button toggle check only `RECORD_AUDIO` permission for instant speech input without waiting on optional system permissions.

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
