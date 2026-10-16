<p align="center">
  <img src="logo.png" alt="Aero Fenix logo" width="220">
</p>

# Aero Fenix (Kotlin + Java + Python)

## How it works
- **Commands run locally** (no internet, no API key): open any app, call a name/number, search inside any app, open the 1st/2nd/3rd chat or video, click, type, scroll, torch, volume, alarm, timer, Home/Back/screenshot. Several commands in one sentence work: "open WhatsApp, search Amma, open first chat".
- **The API key is used only for normal chat and questions.** If the AI server is busy (503/429) it retries, then tries other models, then shows a friendly message.
- Inside-app control uses the Accessibility service, so it works in any app that exposes its screen to Accessibility.

- Kotlin  : UI (Jetpack Compose), voice loop, security, bridge  -> MainActivity.kt, Voice.kt, Store.kt, Strings.kt, Brain.kt
- Java    : phone control (calls, contacts, SMS, apps, alarm, torch, volume, system buttons) -> DeviceController.java, AgentAccessibilityService.java
- Python  : AI brain (Gemini/Groq/OpenAI/Claude/OpenRouter/Mistral/DeepSeek/Ollama) -> app/src/main/python/brain.py

## Build
1. Install Android Studio (Koala or newer), JDK 17, and Python 3.11 on the PC (Chaquopy needs it at build time).
2. File > Open > NextGenAIAgent folder > let Gradle sync (Gradle 8.9).
3. Run on a REAL phone (Android 11+). Emulator has no good speech engine.
4. First Start tap: allow Mic, Phone, Contacts, SMS permissions.
5. Gear menu > "Enable system control" > turn on Aero Fenix in Accessibility (Home/Back/Recents/lock/screenshot).
6. Say: "Amma ku call pannu", "WhatsApp open pannu", "7 mani ku alarm vei", "torch on pannu".

## APK without a PC (GitHub Actions)
1. Create a new GitHub repo, upload ALL files of this project (keep the .github folder).
2. Actions tab > "Build APK" > Run workflow (it also runs on every push).
3. When green, open the run > Artifacts > NextGenAIAgent-apk > download > unzip > install app-debug.apk.
