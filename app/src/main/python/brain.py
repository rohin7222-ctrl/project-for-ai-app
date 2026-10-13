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

SYSTEM = """You are "__NAME__", a friendly voice assistant inside the user's Android phone.
Always answer in __LANG__. Your answer is read aloud: 1-3 short, natural sentences, plain text only - no markdown, no emojis, no lists, no JSON.
You are used only for normal conversation and questions. Phone commands (open apps, calls, search inside apps, volume, torch ...) are handled by the app itself, so never claim to perform them; if the user asks for one you did not understand, ask them to say it again in a simpler way, e.g. "open WhatsApp" or "call Amma"."""


class ApiError(Exception):
    def __init__(self, code, text=""):
        Exception.__init__(self, "HTTP %s: %s" % (code, text))
        self.code, self.text = code, text


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
        raise ApiError(e.code, e.read().decode("utf-8", "ignore")[:300])
    except urllib.error.URLError as e:
        raise ApiError(0, str(e.reason))
    except (TimeoutError, OSError) as e:
        raise ApiError(0, str(e))


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
            "generationConfig": {"temperature": 0.6},
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
                                           "stream": False}, timeout=180)
            return d["message"]["content"]
        headers = {}
    d = _http(base + "/chat/completions",
              {"model": model, "messages": msgs, "temperature": 0.6}, headers, timeout=180)
    return d["choices"][0]["message"]["content"]


# ======================================================================
#  CHAT (API) - used ONLY for normal conversation / questions
# ======================================================================
MSG = {
    "ta-IN": {"busy": "AI சர்வர் இப்போது பிஸியா இருக்கு. கொஞ்சம் கழித்து மீண்டும் சொல்லுங்க.",
              "key": "API key தவறு அல்லது அனுமதி இல்லை. Settings-ல சரிபார்க்கவும்.",
              "net": "இன்டர்நெட் இல்லை. இணைப்பை சரிபார்க்கவும்.",
              "err": "ஏதோ பிரச்சனை. மீண்டும் முயற்சிக்கவும்.",
              "unclear": "புரியவில்லை. கொஞ்சம் எளிமையா சொல்லுங்க, உதாரணம்: WhatsApp open பண்ணு."},
    "en-US": {"busy": "The AI server is busy right now. Please try again in a moment.",
              "key": "The API key is wrong or not allowed. Please check it in settings.",
              "net": "No internet connection. Please check your network.",
              "err": "Something went wrong. Please try again.",
              "unclear": "I did not understand. Please say it simply, like: open WhatsApp."},
    "ja-JP": {"busy": "AIサーバーが混み合っています。少し待ってからもう一度お試しください。",
              "key": "APIキーが正しくないか、許可されていません。設定を確認してください。",
              "net": "インターネットに接続されていません。",
              "err": "問題が発生しました。もう一度お試しください。",
              "unclear": "よく分かりませんでした。例えば「WhatsAppを開いて」のように言ってください。"},
}
_fallback_cache = {}


def _friendly(e, lang):
    m = MSG.get(lang, MSG["en-US"])
    code = getattr(e, "code", None)
    if code in (429, 500, 502, 503, 504):
        return m["busy"]
    if code in (400, 401, 403):
        return m["key"]
    if code == 0:
        return m["net"]
    return m["err"]


def _fallback_models(cfg):
    """Up to 3 other models of the same provider (live list), cheap/fast ones first."""
    key = (cfg["type"], cfg["provider"], cfg.get("url", ""))
    if key not in _fallback_cache:
        try:
            d = json.loads(list_models(cfg["provider"] if cfg["type"] == "api" else "local",
                                       cfg["key"], cfg.get("url", "")))
            ids = [m["id"] for m in d.get("models", [])] if d.get("ok") else []
        except Exception:
            ids = []
        pref = ("flash", "lite", "mini", "instant", "small", "haiku", "8b")
        ids.sort(key=lambda i: (0 if any(p in i.lower() for p in pref) else 1, i))
        _fallback_cache[key] = ids
    return [i for i in _fallback_cache[key] if i != cfg["model"]][:3]


def _complete_resilient(cfg, system, hist):
    """503/429/5xx -> retry same model 3x (1s, 2s), then try 3 other models."""
    last = None
    for model in [cfg["model"]] + _fallback_models_lazy(cfg):
        c = dict(cfg, model=model)
        for attempt in range(3 if model == cfg["model"] else 1):
            try:
                return _complete(c, system, hist)
            except ApiError as e:
                last = e
                if e.code not in (429, 500, 502, 503, 504):
                    raise
                time.sleep(1 + attempt)
    raise last


def _fallback_models_lazy(cfg):
    try:
        return _fallback_models(cfg)
    except Exception:
        return []


def _clean(text):
    text = re.sub(r"[*_`#>]+", "", text or "")
    return text.strip()


def _chat_api(cfg, user_text):
    lang = cfg.get("lang", "en-US")
    system = SYSTEM.replace("__NAME__", cfg.get("name") or "AI") \
                   .replace("__LANG__", LANGS.get(lang, "English"))
    _history.append({"role": "user", "content": user_text})
    del _history[:-12]
    while _history and _history[0]["role"] != "user":
        _history.pop(0)
    try:
        raw = _complete_resilient(cfg, system, _history)
    except Exception as e:
        if _history and _history[-1]["role"] == "user":
            _history.pop()
        return {"ok": True, "speech": _friendly(e, lang), "actions": [], "error": str(e)}
    speech = _clean(raw)
    _history.append({"role": "assistant", "content": speech})
    return {"ok": True, "speech": speech, "actions": []}


# ======================================================================
#  LOCAL COMMAND ENGINE - no internet, no API key, instant
# ======================================================================
APPS = {  # spoken name (any language) -> name searched in the phone's app list
    "whatsapp": "WhatsApp", "whats app": "WhatsApp", "வாட்ஸ்அப்": "WhatsApp", "வாட்ஸ்ஆப்": "WhatsApp",
    "வாட்சப்": "WhatsApp", "ワッツアップ": "WhatsApp", "ウォッツアップ": "WhatsApp",
    "youtube": "YouTube", "you tube": "YouTube", "யூடியூப்": "YouTube", "யூட்யூப்": "YouTube", "ユーチューブ": "YouTube",
    "facebook": "Facebook", "face book": "Facebook", "ஃபேஸ்புக்": "Facebook", "பேஸ்புக்": "Facebook",
    "முகநூல்": "Facebook", "フェイスブック": "Facebook",
    "instagram": "Instagram", "insta": "Instagram", "இன்ஸ்டாகிராம்": "Instagram", "インスタ": "Instagram",
    "telegram": "Telegram", "டெலிகிராம்": "Telegram", "chrome": "Chrome", "குரோம்": "Chrome",
    "camera": "Camera", "கேமரா": "Camera", "カメラ": "Camera", "gallery": "Gallery", "கேலரி": "Gallery",
    "photos": "Photos", "maps": "Maps", "map": "Maps", "மேப்ஸ்": "Maps", "gmail": "Gmail", "ஜிமெயில்": "Gmail",
    "settings": "Settings", "setting": "Settings", "செட்டிங்ஸ்": "Settings", "play store": "Play Store",
    "spotify": "Spotify", "twitter": "X", "snapchat": "Snapchat", "messages": "Messages",
    "phone": "Phone", "contacts": "Contacts", "clock": "Clock", "calculator": "Calculator",
}
ORD = {1: ("first", "1st", "one", "mudhal", "mudhalavadhu", "mudhalaavadhu", "muthal", "முதல்", "முதலாவது", "最初", "1番目"),
       2: ("second", "2nd", "two", "rendavadhu", "irandavadhu", "randavadhu", "இரண்டாவது", "2番目"),
       3: ("third", "3rd", "three", "moonravadhu", "moondravadhu", "munravadhu", "மூன்றாவது", "3番目"),
       4: ("fourth", "4th", "four", "naangavadhu", "nalavadhu", "நான்காவது", "4番目"),
       5: ("fifth", "5th", "five", "ainthavadhu", "anjavadhu", "ஐந்தாவது", "5番目")}
NOUNS = ("chat", "video", "result", "item", "contact", "post", "song", "channel", "conversation", "one",
         "message", "reel", "story", "photo", "link", "சாட்", "வீடியோ", "பாடல்")
FILLER = r"\b(please|plz|pls|pannunga|pannu|panunga|panni|pannidu|seiyunga|seiyu|podunga|podu|kodunga|sollu)\b"
SPLIT = re.compile(r"\s*(?:,|;|\.|\bthen\b|\band\b|\bappuram\b|\bapram\b|\baprom\b|\bpinnadi\b|பிறகு|அப்புறம்|その後|、)\s*", re.I)
PART = r"(?:ah|a|ai|ku|kku|la|le|ல|இல்|ஐ|க்கு|を|に|で)"


def _app_name(raw):
    t = raw.strip().lower()
    t = re.sub(r"^(?:the|my|a)\s+", "", t)
    t = re.sub(r"\s+(app|application|ஆப்|アプリ)$", "", t).strip()
    for k in sorted(APPS, key=len, reverse=True):
        if t == k or t.startswith(k + " ") or t.endswith(" " + k):
            return APPS[k]
    return raw.strip().title() if t else None


def _digits(t):
    d = re.sub(r"[\s\-()]", "", t)
    return d if re.fullmatch(r"\+?\d{3,15}", d) else None


def _ordinal(t):
    for n, words in ORD.items():
        for w in words:
            if re.search(r"(?<![\w])" + re.escape(w) + r"(?![\w])", t):
                return n
    m = re.search(r"\b(\d{1,2})(?:st|nd|rd|th)\b", t) or re.search(r"\b(?:chat|video|result|item)\s+(\d{1,2})\b", t)
    return int(m.group(1)) if m else None


LEAD = re.compile(r"^(?:(?:hey|hi|hello|ok|okay|bro|da|dei|machi|sir|please|plz|pls|kindly|just|now|"
                  r"can you|could you|would you|will you|can u|could u|can i|i want you to|i want to|i would like to|"
                  r"i need to|i need you to|let's|lets|go ahead and|try to|nee|neenga)\s+)+")
TAIL = re.compile(r"(?:\s+(?:please|plz|pls|now|for me|bro|da|pa|ok|okay|quickly|thanks|thank you))+$")
_assistant = {"name": ""}


def _norm(p):
    p = p.strip().lower()
    nm = _assistant["name"]
    if nm:
        p = re.sub(r"^" + re.escape(nm) + r"[\s,]+|[\s,]+" + re.escape(nm) + r"$", "", p)
    p = re.sub(r"[?!]+", " ", p).strip()
    p = LEAD.sub("", p)
    if nm:
        p = re.sub(r"^" + re.escape(nm) + r"[\s,]+", "", p)
        p = LEAD.sub("", p)
    p = TAIL.sub("", p)
    p = re.sub(r"(\w)-(la|le|ku|kku|ah|a)\b", r"\1 \2", p)      # "whatsapp-la" -> "whatsapp la"
    p = re.sub(FILLER, " ", p)
    return re.sub(r"\s+", " ", p).strip(" ?!")


def _parse_one(p, last_app):
    """One clause -> (list_of_actions, kind) or None. last_app = app mentioned earlier in the sentence."""
    t = _norm(p)
    if not t:
        return None

    # --- system buttons (whole-sentence only, so "bring back music" never presses Back)
    sysmap = {"go home": "home", "home": "home", "home screen": "home", "back": "back", "go back": "back",
              "thirumba po": "back", "pinnadi po": "back", "recents": "recents", "recent apps": "recents",
              "screenshot": "screenshot", "take screenshot": "screenshot", "screen shot": "screenshot",
              "lock": "lock", "lock screen": "lock", "lock phone": "lock", "notifications": "notifications",
              "show notifications": "notifications", "notification": "notifications", "quick settings": "quick_settings"}
    if t in sysmap:
        return [{"type": "system", "name": sysmap[t]}], "ok"

    # --- torch / volume
    if re.search(r"\b(torch|flash ?light|tochu|டார்ச்|ライト)\b", t):
        return [{"type": "flashlight", "on": not re.search(r"\b(off|aff|நிறுத்து|消)\b", t)}], "ok"
    if re.fullmatch(r".*\bvolume\b.*|.*\b(mute|silent)\b.*", t):
        d = "mute" if re.search(r"mute|silent", t) else "down" if re.search(r"down|kuraa?i?|less|decrease|low", t) else "up"
        return [{"type": "volume", "dir": d}], "ok"

    # --- calls
    if re.search(r"\b(answer|pick up|attend)\b.*\bcall\b", t):
        return [{"type": "answer_call"}], "ok"
    if re.search(r"\b(end|cut|disconnect|hang ?up)\b.*\bcall\b|\bcall\b.*\b(end|cut)\b", t):
        return [{"type": "end_call"}], "ok"
    m = re.match(r"^(?:call|dial|phone)\s+(?P<t>.+)$", t) or \
        re.match(r"^(?P<t>.+?)\s+" + PART + r"?\s*(?:call|phone|dial|போன்|கால்|電話)(?:\s+(?:vidu|panni vidu|pa|da))?$", t)
    if m and not re.search(r"\b(search|thedu|open|in whatsapp)\b", t):
        tgt = m.group("t").strip()
        tgt = re.sub(r"\s+" + PART + r"$", "", tgt).strip()
        return [{"type": "call", "target": _digits(tgt) or tgt}], "call"

    # --- alarm / timer
    if re.search(r"alarm|அலாரம்|アラーム", t):
        m = re.search(r"(\d{1,2})(?:[:.\s](\d{2}))?\s*(am|pm)?", re.sub(r"alarm|அலாரம்|アラーム", " ", t))
        if m:
            h, mi, ap = int(m.group(1)), int(m.group(2) or 0), m.group(3)
            if ap == "pm" and h < 12: h += 12
            if ap == "am" and h == 12: h = 0
            return [{"type": "alarm", "hour": h % 24, "minute": mi % 60, "label": ""}], "ok"
    m = re.search(r"timer.*?(\d+)\s*(second|sec|minute|min|nimisham|நிமிடம்)|(\d+)\s*(second|sec|minute|min|nimisham|நிமிடம்).*timer", t)
    if m:
        g = m.groups(); n = int(g[0] or g[2]); u = g[1] or g[3]
        return [{"type": "timer", "seconds": n if u.startswith("s") else n * 60}], "ok"

    # --- settings pages
    m = re.search(r"\b(wifi|wi-fi|bluetooth|display|sound|battery)\b.*\bsettings?\b|\bsettings?\b.*\b(wifi|bluetooth|display|sound|battery)\b", t)
    if m:
        pg = next(x for x in m.groups() if x).replace("wi-fi", "wifi")
        return [{"type": "settings", "page": pg}], "ok"

    # --- navigate
    m = re.match(r"^(?:navigate|directions?|route)(?:\s+to)?\s+(?P<q>.+)$", t)
    if m:
        return [{"type": "navigate", "query": m.group("q")}], "ok"

    # --- scroll
    m = re.search(r"\bscroll\b|\bkeela\b|\bmela\b|கீழே|மேலே", t)
    if m and re.search(r"scroll|ஸ்க்ரோல்", t):
        return [{"type": "ui_scroll", "dir": "up" if re.search(r"\bup\b|mela|மேலே", t) else "down"}], "ok"

    # --- open Nth chat / video / result
    n = _ordinal(t)
    if n and (re.search(r"\b(open|click|tap|play|thira|thiranga|select)\b|" + "|".join(NOUNS), t)):
        return [{"type": "ui_open_nth", "n": n}], "nth"

    # --- search inside an app:  "search amma", "amma thedu", "whatsapp la amma thedu", "search amma in whatsapp"
    app = None
    q = None
    m = re.match(r"^(?:search|find|look for)\s+(?:for\s+)?(?P<q>.+?)\s+(?:in|on|at)\s+(?P<a>.+)$", t)
    if m:
        q, app = m.group("q"), m.group("a")
    if q is None:
        m = re.match(r"^(?P<a>.+?)\s+" + r"(?:la|le|ல|இல்|-la)" + r"\s+(?P<q>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:search|thedu|thedunga|theduga|தேடு|தேடுங்க|find|検索)(?=\s|$).*$", t)
        if m:
            q, app = m.group("q"), m.group("a")
    if q is None:
        m = re.match(r"^(?:search|find|look for)\s+(?:for\s+)?(?P<q>.+)$", t) or \
            re.match(r"^(?P<q>.+?)\s*(?:nu|ன்னு|னு|ஐ|ah|a)?\s*(?:search|thedu|thedunga|theduga|தேடு|தேடுங்க|検索)(?=\s|$).*$", t)
        if m:
            q = m.group("q")
    if q:
        q = re.sub(r"\s*(?:nu|ன்னு|னு)$", "", q.strip()).strip()
        appn = _app_name(app) if app else last_app
        qd = _digits(q)
        q = qd or q
        acts = []
        if appn == "YouTube":
            return [{"type": "youtube", "query": q}], "search:" + appn
        if app:
            acts.append({"type": "open_app", "name": appn})
        acts.append({"type": "ui_search", "query": q, "submit": True})
        return acts, "search:" + (appn or "")

    # --- type text / send / click
    m = re.match(r"^(?:type|write|enter)\s+(?P<x>.+)$", t) or re.match(r"^(?P<x>.+?)\s*(?:nu|ன்னு)?\s*(?:type|write)(?=\s|$).*$", t)
    if m:
        return [{"type": "ui_type", "text": m.group("x").strip(), "send": False}], "ok"
    if re.fullmatch(r"(send|send it|anuppu|அனுப்பு|送信)", t):
        return [{"type": "ui_click", "text": "send"}], "ok"
    m = re.match(r"^(?:click|tap|press|select)\s+(?:on\s+)?(?P<x>.+)$", t) or \
        re.match(r"^(?P<x>.+?)\s+" + PART + r"?\s*(?:click|tap|press|thotta|thodu|தொடு|கிளிக்)(?=\s|$).*$", t)
    if m:
        return [{"type": "ui_click", "text": m.group("x").strip()}], "ok"

    # --- open app (last, most generic)
    m = re.match(r"^(?:open|launch|start|run)\s+(?P<a>.+)$", t) or \
        re.match(r"^(?P<a>.+?)\s+" + PART + r"?\s*(?:open|thira|thiranga|thirakka|launch|start|திற|திறங்க|を開|開い)(?=\s|$).*$", t)
    if m:
        name = _app_name(m.group("a"))
        if name:
            return [{"type": "open_app", "name": name}], "app:" + name
    return None


SAY = {  # spoken confirmation per language
    "ta-IN": {"app": "%s திறக்கிறேன்.", "search": "%s தேடுகிறேன்.", "call": "%s க்கு கால் செய்யவா? உறுதிப்படுத்துங்கள்.",
              "nth": "%d-வது ஐ திறக்கிறேன்.", "ok": "சரி.", "yt": "YouTube-ல %s தேடுகிறேன்."},
    "en-US": {"app": "Opening %s.", "search": "Searching %s.", "call": "Calling %s. Please confirm.",
              "nth": "Opening number %d.", "ok": "Okay.", "yt": "Searching %s on YouTube."},
    "ja-JP": {"app": "%sを開きます。", "search": "%sを検索します。", "call": "%sに電話します。確認してください。",
              "nth": "%d番目を開きます。", "ok": "はい。", "yt": "YouTubeで%sを検索します。"},
}


def local_command(text, lang="en-US"):
    """Returns {"speech","actions"} if EVERY clause is a phone command, else None (-> normal chat)."""
    clauses = [c for c in SPLIT.split(text.strip()) if c and c.strip()]
    if not clauses:
        return None
    actions, said, last_app = [], [], None
    for c in clauses:
        r = _parse_one(c, last_app)
        if r is None:
            return None
        acts, kind = r
        for a in acts:
            if a["type"] == "open_app":
                last_app = a["name"]
        # a bare "search X" right after "open <app>" must search INSIDE that app (already open)
        actions.extend(acts)
        s = SAY.get(lang, SAY["en-US"])
        if kind.startswith("app:"): said.append(s["app"] % kind[4:])
        elif kind == "call": said.append(s["call"] % acts[0]["target"])
        elif kind == "nth": said.append(s["nth"] % acts[0]["n"])
        elif kind.startswith("search:") and acts[0]["type"] == "youtube": said.append(s["yt"] % acts[0]["query"])
        elif kind.startswith("search:"): said.append(s["search"] % acts[-1]["query"])
        else: said.append(s["ok"])
    return {"speech": " ".join(said), "actions": actions}


# ======================================================================
#  ENTRY POINT used by Brain.kt
# ======================================================================
def chat(cfg_json, user_text):
    """Returns JSON: {"ok":true,"speech":..,"actions":[..]}. Commands run locally; only chat uses the API."""
    cfg = json.loads(cfg_json)
    lang = cfg.get("lang", "en-US")
    _assistant["name"] = (cfg.get("name") or "").strip().lower()
    try:
        cmd = local_command(user_text, lang)
        if cmd:
            cmd["ok"] = True
            return json.dumps(cmd)
        return json.dumps(_chat_api(cfg, user_text))
    except Exception as e:
        return json.dumps({"ok": True, "speech": _friendly(e, lang), "actions": [], "error": str(e)})
