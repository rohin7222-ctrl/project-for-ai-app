# NextGen AI Agent (Kotlin + Java + Python)

- Kotlin  : UI (Jetpack Compose), voice loop, security, bridge  -> MainActivity.kt, Voice.kt, Store.kt, Strings.kt, Brain.kt
- Java    : phone control (calls, contacts, SMS, apps, alarm, torch, volume, system buttons) -> DeviceController.java, AgentAccessibilityService.java
- Python  : AI brain (Gemini/Groq/OpenAI/Claude/OpenRouter/Mistral/DeepSeek/Ollama) -> app/src/main/python/brain.py

## Build
1. Install Android Studio (Koala or newer), JDK 17, and Python 3.11 on the PC (Chaquopy needs it at build time).
2. File > Open > NextGenAIAgent folder > let Gradle sync (Gradle 8.9).
3. Run on a REAL phone (Android 11+). Emulator has no good speech engine.
4. First Start tap: allow Mic, Phone, Contacts, SMS permissions.
5. Gear menu > "Enable system control" > turn on NextGen AI Agent in Accessibility (Home/Back/Recents/lock/screenshot).
6. Say: "Amma ku call pannu", "WhatsApp open pannu", "7 mani ku alarm vei", "torch on pannu".
