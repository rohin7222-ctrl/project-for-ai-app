<p align="center">
  <img src="logo.png" alt="Aero Fenix logo" width="220">
</p>

# Aero Fenix (Kotlin + Java + Python)

## Key Features
- **Universal Hands-Free OS & App Controller**: Control 100% of apps hands-free via voice or text.
  - Multi-step chained actions: e.g. *"Zomato open panni biryani thedu"*, *"Instagram open panni reels cruise on"*.
  - **Auto Skip-Ad Engine**: Automatically skips video advertisements in real time.
  - **Auto-Reels Cruise Control**: Automatically scrolls through Instagram Reels & YouTube Shorts hands-free.
  - **Form Auto-Fill & OTP Auto-Grabber**: Automatically fills forms and detects/enters OTPs from incoming notifications.
  - **Voice Precision Grid**: Tap any section of the screen by voice (e.g., *"tap center"*, *"tap top right"*).
  - **Dynamic App Resolution**: Launches and interacts with any installed or newly downloaded application dynamically without hardcoded limits.
- **Futuristic Cyberpunk / Sci-Fi HUD Interface**:
  - Dual Corner Helm Wheels (Top-Right Settings & Bottom-Right Quick Actions) with physical momentum spin.
  - Quantum Arc Reactor hero visualizer with 3 concentric rotating holographic rings and orbiting quantum satellites.
  - Real-time laser sweep radar beam and isometric cyber grid matrix.
  - Obsidian terminal-styled AI code blocks with one-tap copy and speech equalizer waves.
- **24/7 False-Positive-Free Ear-To-Call**:
  - Proximity & Accelerometer Sensor Fusion validates 45°+ upright vertical ear tilt before answering, preventing accidental pocket/table answers.
- **Multi-Wake Word & Biometrics**:
  - Wakes up to *"Aero Fenix"*, *"Hey Fenix"*, *"Fenix"*, *"Jarvis"*, or your custom wake word with 10s voice biometric lock.
- **Advanced Multi-Model AI Brain**:
  - Supports Google Gemini 2.5, Claude 3.7 Sonnet, GPT-4.1, DeepSeek V3/R1, Groq, Mistral, and local Ollama LLMs with sliding-window context compression.

- Kotlin  : UI (Jetpack Compose), voice loop, security, bridge  -> MainActivity.kt, Voice.kt, Store.kt, Strings.kt, Brain.kt, MainScreen.kt, AiAnswerCard.kt
- Java    : phone control (calls, contacts, SMS, apps, alarm, torch, volume, system buttons, accessibility automation) -> DeviceController.java, AgentAccessibilityService.java
- Python  : AI brain & NLP action parser -> app/src/main/python/brain.py

## Build
1. Install Android Studio (Koala or newer), JDK 17, and Python 3.11 on the PC (Chaquopy needs it at build time).
2. File > Open > AeroFenix folder > let Gradle sync (Gradle 8.9).
3. Run on a REAL phone (Android 11+). Emulator has no good speech engine.
4. First Start tap: allow Mic, Phone, Contacts, SMS permissions.
5. Gear menu > "Enable system control" > turn on Aero Fenix in Accessibility (Home/Back/Recents/lock/screenshot).
6. Say: "Amma ku call pannu", "WhatsApp open pannu", "7 mani ku alarm vei", "torch on pannu".

## APK without a PC (GitHub Actions)
1. Push to your GitHub repo (all workflow files in `.github` are ready).
2. Actions tab > "Build APK" runs automatically on push.
3. When green, open the run > Artifacts > AeroFenix-apk > download > unzip > install `app-debug.apk`.
