"""NextGen AI Agent - the 'brain'. Runs inside the Android app via Chaquopy.
Standard library only. Talks to Gemini, Groq, OpenAI, Claude, OpenRouter,
Mistral, DeepSeek or a local Ollama / OpenAI-compatible server, and turns the
answer into {"speech": "...", "actions": [...]} for the Java DeviceController."""
import json
import re
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
{"type":"call","target":"<contact name or phone number>"}
{"type":"sms","target":"<contact name or phone number>","text":"..."}
{"type":"open_app","name":"<app name in English, e.g. WhatsApp>"}
{"type":"web_search","query":"..."}
{"type":"open_url","url":"https://..."}
{"type":"youtube","query":"..."}
{"type":"navigate","query":"<place>"}
{"type":"alarm","hour":7,"minute":30,"label":""}
{"type":"timer","seconds":300}
{"type":"flashlight","on":true}
{"type":"volume","dir":"up|down|mute"}
{"type":"system","name":"home|back|recents|notifications|quick_settings|lock|screenshot"}
{"type":"settings","page":"wifi|bluetooth|display|sound|battery|apps|main"}
{"type":"answer_call"}
{"type":"end_call"}

Rules:
- Use actions only when the user clearly asks for a phone operation. For normal questions answer in speech and use "actions": [].
- You may chain several actions in one reply (e.g. open an app, then something else).
- Never invent contacts or phone numbers. If something is missing, ask in speech and leave actions empty.
- In speech, briefly confirm what you are doing."""


def _http(url, body=None, headers=None, timeout=60):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h,
                                 method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError("HTTP %d: %s" % (e.code, e.read().decode("utf-8", "ignore")[:300]))


def _local_base(url):
    base = (url or "").strip().rstrip("/")
    if base.endswith("/api"):
        base = base[:-4]
    return base


def list_models(provider, key, url=""):
    """Returns JSON: {"ok":true,"models":[{"id":..,"name":..}]} - the live list from the provider."""
    try:
        out = []
        if provider == "gemini":
            d = _http(GEMINI + "/models?pageSize=200", headers={"x-goog-api-key": key})
            for m in d.get("models", []):
                if "generateContent" not in m.get("supportedGenerationMethods", []):
                    continue
                mid = m["name"].split("/", 1)[-1]
                if any(s in mid.lower() for s in SKIP):
                    continue
                out.append({"id": mid, "name": m.get("displayName", mid)})
        elif provider == "anthropic":
            d = _http(ANTHROPIC + "/models?limit=100",
                      headers={"x-api-key": key, "anthropic-version": "2023-06-01"})
            out = [{"id": m["id"], "name": m.get("display_name", m["id"])} for m in d.get("data", [])]
        elif provider in OPENAI_COMPAT:
            d = _http(OPENAI_COMPAT[provider] + "/models", headers={"Authorization": "Bearer " + key})
            out = [{"id": m["id"], "name": m["id"]} for m in d.get("data", [])
                   if not any(s in m["id"].lower() for s in SKIP)]
            out.sort(key=lambda m: m["id"])
        elif provider == "local":
            d = _http(_local_base(url) + "/api/tags")
            out = [{"id": m["name"], "name": m["name"]} for m in d.get("models", [])]
        else:
            raise RuntimeError("Unknown provider: " + provider)
        return json.dumps({"ok": True, "models": out})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def reset():
    del _history[:]


def _complete(cfg, system, hist):
    t, prov, model, key = cfg["type"], cfg["provider"], cfg["model"], cfg["key"]
    if t == "api" and prov == "gemini":
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user" if m["role"] == "user" else "model",
                          "parts": [{"text": m["content"]}]} for m in hist],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.4},
        }
        d = _http(GEMINI + "/models/" + model + ":generateContent", body,
                  {"x-goog-api-key": key})
        parts = d["candidates"][0]["content"]["parts"]
        return "".join(p.get("text", "") for p in parts if not p.get("thought"))
    if t == "api" and prov == "anthropic":
        d = _http(ANTHROPIC + "/messages",
                  {"model": model, "max_tokens": 700, "system": system, "messages": hist},
                  {"x-api-key": key, "anthropic-version": "2023-06-01"})
        return "".join(b.get("text", "") for b in d["content"])
    msgs = [{"role": "system", "content": system}] + hist
    if t == "api":
        base, headers = OPENAI_COMPAT[prov], {"Authorization": "Bearer " + key}
    else:
        base = _local_base(cfg["url"])
        if "/v1" not in base:  # Ollama native API
            d = _http(base + "/api/chat", {"model": model, "messages": msgs,
                                           "stream": False, "format": "json"}, timeout=180)
            return d["message"]["content"]
        headers = {}
    d = _http(base + "/chat/completions",
              {"model": model, "messages": msgs, "temperature": 0.4}, headers, timeout=180)
    return d["choices"][0]["message"]["content"]


def _parse(raw):
    m = re.search(r"\{.*\}", raw, re.S)
    try:
        obj = json.loads(m.group(0)) if m else {}
    except ValueError:
        obj = {}
    speech = obj.get("speech") if isinstance(obj.get("speech"), str) else None
    if speech is None:
        speech = raw.strip() if not obj else ""
    acts = [a for a in obj.get("actions", []) if isinstance(a, dict) and a.get("type")]
    return {"speech": speech, "actions": acts}


def chat(cfg_json, user_text):
    """Returns JSON: {"ok":true,"speech":..,"actions":[..]} or {"ok":false,"error":..}"""
    cfg = json.loads(cfg_json)
    try:
        system = SYSTEM.replace("__NAME__", cfg.get("name") or "AI") \
                       .replace("__LANG__", LANGS.get(cfg.get("lang"), "English"))
        _history.append({"role": "user", "content": user_text})
        del _history[:-12]
        while _history and _history[0]["role"] != "user":
            _history.pop(0)
        raw = _complete(cfg, system, _history)
        out = _parse(raw)
        _history.append({"role": "assistant", "content": raw})
        out["ok"] = True
        return json.dumps(out)
    except Exception as e:
        if _history and _history[-1]["role"] == "user":
            _history.pop()
        return json.dumps({"ok": False, "error": str(e)})