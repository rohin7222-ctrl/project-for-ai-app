"""NextGen AI Agent - the 'brain'. Runs inside the Android app via Chaquopy.
Standard library only. Talks to Gemini, Groq, OpenAI, Claude, OpenRouter,
Mistral, DeepSeek or a local Ollama / OpenAI-compatible server, and turns the
answer into {"speech": "...", "actions": [...]} for the Java DeviceController."""
import json
import re
import time
import urllib.error
import urllib.request

GEMINI = "https://generativelanguage.googleapis.com/v1beta"
ANTHROPIC = "https://api.anthropic.com/v1"
OPENAI_COMPAT = {
    "groq": "https://api.groq.com/openai/v1",
    "openai": "https://api.openai.com/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "mistral": "https://api.mistral.ai/v1",
    "deepseek": "https://api.deepseek.com/v1",
}
SKIP = ("whisper", "tts", "embed", "guard", "moderation", "dall-e", "image",
        "audio", "realtime", "imagen", "veo", "live", "transcribe", "aqa")
LANGS = {"ta-IN": "Tamil", "en-US": "English", "ja-JP": "Japanese"}

_history = []

SYSTEM = """You are "__NAME__", a voice assistant living inside the user's Android phone, with real control over it.
Always speak to the user in __LANG__. Keep "speech" to 1-2 short, natural sentences for text-to-speech: no markdown, no emojis, no lists.
Reply with ONLY one JSON object, nothing else:
{"speech": "...", "actions": [ ... ]}

Available actions: