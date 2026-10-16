"""Aero Fenix - the 'brain'. Runs inside the Android app via Chaquopy.
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
LANGS = {
    "ta-IN": "Tamil",
    "en-US": "English",
    "ja-JP": "Japanese",
    "hi-IN": "Hindi",
    "ko-KR": "Korean"
}

_history = []

SYSTEM = """You are "__NAME__", an ultra-capable personal AI assistant and Universal Android Device Controller.
Current local date and time: __DATETIME__.
Always reply naturally, respectfully and accurately in __LANG__.
Formatting Guidelines:
- Format your response beautifully using standard Markdown.
- Keep explanations structured, insightful, and practical.
- Universal Android Control: You have FULL authority to execute actions on ANY app or setting on this Android phone.
When the user asks you to perform an on-device action or task (open any app, search inside an app, order food/items, click buttons, play media, change settings, send messages, navigate, etc.), you MUST provide an actionable JSON plan inside a fenced json block at the END of your response in this exact format:
```json
{
  "actions": [
    {"type": "open_app", "name": "App Name", "desc": "Opening App"},
    {"type": "ui_search", "query": "search query", "desc": "Searching item"},
    {"type": "ui_open_nth", "n": 1, "desc": "Selecting item 1"},
    {"type": "ui_smart_click", "action": "add to cart", "desc": "Adding to cart"},
    {"type": "ui_type", "text": "message text", "send": true, "desc": "Typing message"},
    {"type": "ui_scroll", "dir": "down", "desc": "Scrolling"},
    {"type": "ui_swipe", "dir": "up", "desc": "Swiping"},
    {"type": "ui_click_tab", "tab": "home/search/orders/cart/profile", "desc": "Opening tab"},
    {"type": "ui_handle_dialog", "action": "allow/ok", "desc": "Handling dialog"},
    {"type": "call", "target": "Contact/Number", "desc": "Calling contact"},
    {"type": "web_search", "query": "query", "desc": "Searching Google"},
    {"type": "youtube_play", "query": "query", "desc": "Playing video"}
  ]
}
```
Include only the actions needed for the requested task in logical sequence. Your spoken response before the JSON block will be read aloud to the user."""


OFFLINE_KEYWORDS = (
    "getaddrinfo", "unreachable", "network is down", "nodename", "no route",
    "not known", "no address associated with hostname", "errno 7", "eai_nodata",
    "name or service not known", "temporary failure in name resolution",
    "failed to resolve", "connection refused", "network unreachable"
)


def _is_offline(err):
    s = str(err).lower()
    return any(w in s for w in OFFLINE_KEYWORDS)


class ApiError(Exception):
    def __init__(self, code, text=""):
        Exception.__init__(self, "HTTP %s: %s" % (code, text))
        self.code, self.text = code, text


def _http(url, body=None, headers=None, timeout=45):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h,
                                 method="POST" if body is not None else "GET")
    last_err = None
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise ApiError(e.code, e.read().decode("utf-8", "ignore")[:300])
        except urllib.error.URLError as e:
            if _is_offline(e.reason):
                raise ApiError(0, "OFFLINE")
            last_err = ApiError(0, str(e.reason))
        except (TimeoutError, OSError) as e:
            if _is_offline(e):
                raise ApiError(0, "OFFLINE")
            last_err = ApiError(0, str(e))
        if attempt == 0:
            time.sleep(0.8)
    if last_err:
        if _is_offline(last_err.text):
            raise ApiError(0, "OFFLINE")
        raise last_err
    raise ApiError(0, "Network timeout")


def _local_base(url):
    base = (url or "").strip().rstrip("/")
    if base.endswith("/api"):
        base = base[:-4]
    return base


DEFAULT_MODELS = {
    "gemini": [
        {"id": "gemini-2.5-flash", "name": "Gemini 2.5 Flash (Recommended)"},
        {"id": "gemini-2.5-pro", "name": "Gemini 2.5 Pro"},
        {"id": "gemini-2.0-flash", "name": "Gemini 2.0 Flash"},
        {"id": "gemini-1.5-pro", "name": "Gemini 1.5 Pro"},
        {"id": "gemini-2.0-flash-lite", "name": "Gemini 2.0 Flash Lite"},
    ],
    "groq": [
        {"id": "llama-3.3-70b-versatile", "name": "Llama 3.3 70B Versatile"},
        {"id": "deepseek-r1-distill-llama-70b", "name": "DeepSeek R1 Distill 70B"},
        {"id": "llama-3.1-8b-instant", "name": "Llama 3.1 8B Instant"},
        {"id": "mixtral-8x7b-32768", "name": "Mixtral 8x7B"},
    ],
    "openai": [
        {"id": "gpt-4.1", "name": "GPT-4.1 (Next-Gen)"},
        {"id": "gpt-4o", "name": "GPT-4o (Omni)"},
        {"id": "gpt-4o-mini", "name": "GPT-4o Mini (Fast)"},
        {"id": "o3-mini", "name": "o3-mini (Reasoning)"},
        {"id": "o1", "name": "o1 (High Intelligence)"},
    ],
    "anthropic": [
        {"id": "claude-3-7-sonnet-20250219", "name": "Claude 3.7 Sonnet (Hybrid Reasoning)"},
        {"id": "claude-3-5-sonnet-20241022", "name": "Claude 3.5 Sonnet"},
        {"id": "claude-3-5-haiku-20241022", "name": "Claude 3.5 Haiku"},
    ],
    "deepseek": [
        {"id": "deepseek-chat", "name": "DeepSeek V3 (Chat)"},
        {"id": "deepseek-reasoner", "name": "DeepSeek R1 (Reasoner)"},
    ],
    "mistral": [
        {"id": "mistral-large-latest", "name": "Mistral Large"},
        {"id": "mistral-small-latest", "name": "Mistral Small"},
        {"id": "codestral-latest", "name": "Codestral"},
    ],
    "openrouter": [
        {"id": "deepseek/deepseek-r1", "name": "DeepSeek R1 (OpenRouter)"},
        {"id": "anthropic/claude-3.7-sonnet", "name": "Claude 3.7 Sonnet"},
        {"id": "meta-llama/llama-3.3-70b-instruct", "name": "Llama 3.3 70B"},
        {"id": "google/gemini-2.0-flash-001", "name": "Gemini 2.0 Flash"},
        {"id": "deepseek/deepseek-chat", "name": "DeepSeek V3"},
    ],
}


def default_models(provider):
    """Returns JSON array of standard default models for provider."""
    return json.dumps(DEFAULT_MODELS.get(provider, []))


def list_models(provider, key, url=""):
    """Returns JSON: {"ok":true,"models":[{"id":..,"name":..}],"fallback":bool,"error":str}
    Returns live list from provider if reachable, or standard models on offline/failure."""
    defaults = DEFAULT_MODELS.get(provider, [])
    if provider != "local" and (not key or len(key.strip()) < 8):
        return json.dumps({"ok": True, "models": defaults, "fallback": True})

    try:
        out = []
        if provider == "gemini":
            d = _http(GEMINI + "/models?pageSize=200", headers={"x-goog-api-key": key.strip()})
            for m in d.get("models", []):
                if "generateContent" not in m.get("supportedGenerationMethods", []):
                    continue
                mid = m["name"].split("/", 1)[-1]
                if any(s in mid.lower() for s in SKIP):
                    continue
                out.append({"id": mid, "name": m.get("displayName", mid)})
        elif provider == "anthropic":
            d = _http(ANTHROPIC + "/models?limit=100",
                      headers={"x-api-key": key.strip(), "anthropic-version": "2023-06-01"})
            out = [{"id": m["id"], "name": m.get("display_name", m["id"])} for m in d.get("data", [])]
        elif provider in OPENAI_COMPAT:
            d = _http(OPENAI_COMPAT[provider] + "/models", headers={"Authorization": "Bearer " + key.strip()})
            out = [{"id": m["id"], "name": m["id"]} for m in d.get("data", [])
                   if not any(s in m["id"].lower() for s in SKIP)]
            out.sort(key=lambda m: m["id"])
        elif provider == "local":
            d = _http(_local_base(url) + "/api/tags")
            out = [{"id": m["name"], "name": m["name"]} for m in d.get("models", [])]
        else:
            raise RuntimeError("Unknown provider: " + provider)
        if out:
            return json.dumps({"ok": True, "models": out, "fallback": False})
        return json.dumps({"ok": True, "models": defaults, "fallback": True})
    except Exception as e:
        err_msg = str(e)
        if _is_offline(err_msg) or "OFFLINE" in err_msg:
            err_code = "OFFLINE"
        elif any(c in err_msg for c in ("400", "401", "403")):
            err_code = "INVALID_KEY"
        else:
            err_code = err_msg
        return json.dumps({
            "ok": True if defaults else False,
            "models": defaults,
            "fallback": True,
            "error": err_code
        })


_cache = {}


def reset():
    del _history[:]
    _cache.clear()


def _complete(cfg, system, hist):
    t, prov, model, key = cfg["type"], cfg["provider"], cfg["model"], cfg["key"]
    if t == "api" and prov == "gemini":
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user" if m["role"] == "user" else "model",
                          "parts": [{"text": m["content"]}]} for m in hist],
            "generationConfig": {"temperature": 0.6, "maxOutputTokens": 2048},
        }
        d = _http(GEMINI + "/models/" + model + ":generateContent", body,
                  {"x-goog-api-key": key})
        parts = d["candidates"][0]["content"]["parts"]
        return "".join(p.get("text", "") for p in parts if not p.get("thought"))
    if t == "api" and prov == "anthropic":
        d = _http(ANTHROPIC + "/messages",
                  {"model": model, "max_tokens": 2048, "system": system, "messages": hist},
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
              {"model": model, "messages": msgs, "temperature": 0.6, "max_tokens": 2048}, headers, timeout=180)
    return d["choices"][0]["message"]["content"]


# ======================================================================
#  CHAT (API) - used ONLY for normal conversation / questions
# ======================================================================
MSG = {
    "ta-IN": {"busy": "AI சர்வர் இப்போது பிஸியா இருக்கு. கொஞ்சம் கழித்து மீண்டும் சொல்லுங்க.",
              "key": "API key தவறு அல்லது அனுமதி இல்லை. Settings-ல சரிபார்க்கவும்.",
              "net": "ஏரோப்ளேன் மோட் (Airplane Mode) ஆன்-ல் உள்ளது அல்லது இணைய இணைப்பு இல்லை. ஆப்லைன் போன் கட்டளைகளை (கால், வைஃபை, டார்ச், அலாரம், ஆப்) என்னால் செய்ய முடியும்.",
              "err": "ஏதோ பிரச்சனை. மீண்டும் முயற்சிக்கவும்.",
              "unclear": "புரியவில்லை. கொஞ்சம் எளிமையா சொல்லுங்க, உதாரணம்: WhatsApp open பண்ணு."},
    "en-US": {"busy": "The AI server is busy right now. Please try again in a moment.",
              "key": "The API key is wrong or not allowed. Please check it in settings.",
              "net": "Airplane Mode is active or internet is disconnected. Offline controls (calls, flashlight, apps, volume, alarms) are fully working.",
              "err": "Something went wrong. Please try again.",
              "unclear": "I did not understand. Please say it simply, like: open WhatsApp."},
    "ja-JP": {"busy": "AIサーバーが混み合っています。少し待ってからもう一度お試しください。",
              "key": "APIキーが正しくないか、許可されていません。設定を確認してください。",
              "net": "機内モードが有効か、オフラインです。通話、ライト、アプリ起動、アラームなどの機能は利用できます。",
              "err": "問題が発生しました。もう一度お試しください。",
              "unclear": "よく分かりませんでした。例えば「WhatsAppを開いて」のように言ってください。"},
    "hi-IN": {"busy": "AI सर्वर अभी व्यस्त है। कृपया थोड़ी देर बाद फिर से प्रयास करें।",
              "key": "API कुंजी अमान्य है या अनुमत नहीं है। कृपया सेटिंग्स में जांचें।",
              "net": "इंटरनेट कनेक्शन नहीं है या हवाई जहाज मोड सक्रिय है। ऑफ़लाइन सुविधाएं (कॉल, टॉर्च, ऐप्स) चालू हैं।",
              "err": "कुछ गलत हो गया। कृपया पुन: प्रयास करें।",
              "unclear": "समझ नहीं आया। कृपया सरलता से कहें, जैसे: WhatsApp खोलो।"},
    "ko-KR": {"busy": "AI 서버가 현재 바쁩니다. 잠시 후 다시 시도해 주세요.",
              "key": "API 키가 잘못되었거나 허용되지 않았습니다. 설정에서 확인하세요.",
              "net": "인터넷 연결이 없거나 비행기 탑승 모드입니다. 오프라인 제어(전화, 손전등, 앱)는 작동합니다.",
              "err": "문제가 발생했습니다. 다시 시도해 주세요.",
              "unclear": "이해하지 못했습니다. 'WhatsApp 열어줘'처럼 간단히 말씀해 주세요."},
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
    """503/429/5xx and network code 0 -> retry same model 3x (1s, 2s), then try 3 other models."""
    last = None
    for model in [cfg["model"]] + _fallback_models_lazy(cfg):
        c = dict(cfg, model=model)
        for attempt in range(3 if model == cfg["model"] else 1):
            try:
                return _complete(c, system, hist)
            except ApiError as e:
                last = e
                # Fail immediately if offline / in airplane mode without wasting user time
                if e.text == "OFFLINE" or _is_offline(e.text):
                    raise
                if e.code not in (0, 429, 500, 502, 503, 504):
                    raise
                time.sleep(1 + attempt)
    raise last


def _fallback_models_lazy(cfg):
    try:
        return _fallback_models(cfg)
    except Exception:
        return []


def _spoken_summary(raw, lang):
    if not raw:
        return ""
    code_sub = "(கோட் திரையில் கொடுக்கப்பட்டுள்ளது)" if lang == "ta-IN" else ("(コードは画面に表示されています)" if lang == "ja-JP" else "(Code shown on screen)")
    text = re.sub(r"```[\w]*\n[\s\S]*?```", code_sub, raw)
    text = re.sub(r"[*#`_>~]+", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) > 280:
        m = re.match(r"^(.{80,260}?[.?!])\s+", text)
        if m:
            text = m.group(1).strip()
        else:
            text = text[:250].rstrip() + "..."
    return text


def _clean(text):
    text = re.sub(r"[*_`#>]+", "", text or "")
    return text.strip()


def _quick_local_answer(text, cfg):
    """Answers common instant queries (greetings, identity, time, date, math) locally to save API tokens."""
    t = text.strip().lower()
    t_clean = re.sub(r"[?!.,]+", "", t).strip()
    lang = cfg.get("lang", "en-US")
    name = cfg.get("name") or "Aero Fenix"

    # 1. Identity / Name
    if re.search(r"^(?:who are you|what is your name|your name|who made you|un per enna|unga peru enna|நீ யார்|உன் பெயர் என்ன|名前は何ですか|あなたは誰ですか|आप कौन हैं|तुम्हारा नाम क्या है|누구세요|이름이 뭐야)$", t_clean):
        if lang == "ta-IN":
            return "என் பெயர் %s. நான் உங்கள் தனிப்பட்ட AI உதவியாளர்." % name
        elif lang == "ja-JP":
            return "私は%sです。あなたのパーソナルAIアシスタントです。" % name
        elif lang == "hi-IN":
            return "मेरा नाम %s है। मैं आपका निजी AI सहायक हूँ।" % name
        elif lang == "ko-KR":
            return "제 이름은 %s입니다. 개인 AI 비서입니다." % name
        return "I am %s, your personal AI assistant." % name

    # 2. Greetings
    if re.search(r"^(?:hi|hello|hey|vanakkam|வணக்கம்|வணக்கங்க|good morning|good afternoon|good evening|こんにちは|おはよう|नमस्ते|प्रणाम|안녕하세요|안녕)$", t_clean):
        if lang == "ta-IN":
            return "வணக்கம்! நான் உங்களுக்கு எப்படி உதவ வேண்டும்?"
        elif lang == "ja-JP":
            return "こんにちは！何かお手伝いできることはありますか？"
        elif lang == "hi-IN":
            return "नमस्ते! मैं आपकी किस प्रकार सहायता कर सकता हूँ?"
        elif lang == "ko-KR":
            return "안녕하세요! 무엇을 도와드릴까요?"
        return "Hello! How can I help you today?"

    # 3. How are you
    if re.search(r"^(?:how are you|how are u|epdi iruka|eppadi irukkeenga|nalla irukiya|எப்படி இருக்கிறீர்கள்|எப்படி இருக்கீங்க|元気ですか|調子はどうですか|आप कैसे हैं|हाल चाल कैसा है|잘 지내|어떻게 지내)$", t_clean):
        if lang == "ta-IN":
            return "நான் நலமாக இருக்கிறேன்! உங்களுக்கு உதவ தயாராக உள்ளேன்."
        elif lang == "ja-JP":
            return "元気です！いつでもお手伝いできます。"
        elif lang == "hi-IN":
            return "मैं बहुत अच्छा हूँ! आपकी सेवा के लिए तैयार हूँ।"
        elif lang == "ko-KR":
            return "잘 지내고 있습니다! 도울 준비가 되어 있어요."
        return "I'm doing great, ready to assist you!"

    # 4. Time
    if re.search(r"^(?:what is the time|time please|what time is it|now time|current time|neram enna|time enna|மணி என்ன|இப்போது நேரம் என்ன|何時ですか|今の時間は|समय क्या हुआ|कितने बजे हैं|지금 몇 시야|지금 시간)$", t_clean):
        now_time = time.strftime("%I:%M %p")
        if lang == "ta-IN":
            return "இப்போது நேரம்: %s" % now_time
        elif lang == "ja-JP":
            return "現在の時刻は %s です。" % now_time
        elif lang == "hi-IN":
            return "अभी का समय है: %s" % now_time
        elif lang == "ko-KR":
            return "현재 시각은 %s입니다." % now_time
        return "The current time is %s." % now_time

    # 5. Date
    if re.search(r"^(?:what is the date|today date|what date is it|what is today's date|date enna|inniku date enna|தேதி என்ன|இன்றைய தேதி என்ன|今日の日付は|何日ですか|आज की तारीख क्या है|आज क्या तारीख है|오늘 며칠이야|오늘 날짜)$", t_clean):
        now_date = time.strftime("%A, %d %B %Y")
        if lang == "ta-IN":
            return "இன்றைய தேதி: %s" % now_date
        elif lang == "ja-JP":
            return "今日の日付は %s です。" % now_date
        elif lang == "hi-IN":
            return "आज की तारीख है: %s" % now_date
        elif lang == "ko-KR":
            return "오늘 날짜는 %s입니다." % now_date
        return "Today's date is %s." % now_date

    # 6. Coin Toss & Dice
    if re.search(r"^(?:flip a coin|coin toss|toss a coin|toss pannu|head or tail|roll a dice|roll dice|dice podu)$", t_clean):
        import random
        if "dice" in t_clean:
            d_val = random.randint(1, 6)
            if lang == "ta-IN": return "தாயக்கட்டை எண்: %d" % d_val
            elif lang == "hi-IN": return "पासे का अंक: %d" % d_val
            elif lang == "ja-JP": return "サイコロの目は %d です。" % d_val
            return "Dice rolled: %d." % d_val
        else:
            outcome = random.choice(["Heads", "Tails"])
            if lang == "ta-IN": return "டாஸ் முடிவு: %s" % ("ஹெட் (தலை)" if outcome == "Heads" else "டெயில் (பூ)")
            elif lang == "hi-IN": return "सिक्का उछालने का परिणाम: %s" % ("हेड" if outcome == "Heads" else "टेल")
            elif lang == "ja-JP": return "コインの結果: %s" % ("表" if outcome == "Heads" else "裏")
            return "Coin toss result: %s." % outcome

    # 7. Unit Conversion: km to miles, celsius to fahrenheit, kg to lbs
    m_conv = re.match(r"^(\d+(?:\.\d+)?)\s*(km|miles?|celsius|fahrenheit|kg|pounds?|lbs?)\s*(?:to|in|into|ஆக|இல்)\s*(km|miles?|celsius|fahrenheit|kg|pounds?|lbs?)$", t_clean)
    if m_conv:
        v = float(m_conv.group(1))
        u1, u2 = m_conv.group(2).lower(), m_conv.group(3).lower()
        ans = None
        if u1.startswith("km") and u2.startswith("mile"): ans = "%.2f miles" % (v * 0.621371)
        elif u1.startswith("mile") and u2.startswith("km"): ans = "%.2f km" % (v * 1.60934)
        elif "celsius" in u1 and "fahrenheit" in u2: ans = "%.1f °F" % ((v * 9/5) + 32)
        elif "fahrenheit" in u1 and "celsius" in u2: ans = "%.1f °C" % ((v - 32) * 5/9)
        elif u1.startswith("kg") and (u2.startswith("pound") or u2.startswith("lb")): ans = "%.2f lbs" % (v * 2.20462)
        elif (u1.startswith("pound") or u1.startswith("lb")) and u2.startswith("kg"): ans = "%.2f kg" % (v / 2.20462)
        if ans:
            if lang == "ta-IN": return "மாற்று மதிப்பு: %s" % ans
            elif lang == "hi-IN": return "परिवर्तित मान: %s" % ans
            return "Converted value: %s." % ans

    # 8. Basic Math: e.g. "5 + 10", "calculate 25 * 4", "100 / 5", "50 - 20"
    m_math = re.match(r"^(?:calculate|what is|கணக்கிடு|மதிப்பு|हिसाब करो)?\s*(\d+(?:\.\d+)?)\s*([\+\-\*\/xX÷])\s*(\d+(?:\.\d+)?)\s*(?:enna|என்ன|equal|is)?$", t_clean)
    if m_math:
        n1 = float(m_math.group(1))
        op = m_math.group(2).lower()
        n2 = float(m_math.group(3))
        res = None
        if op == "+": res = n1 + n2
        elif op == "-": res = n1 - n2
        elif op in ("*", "x"): res = n1 * n2
        elif op in ("/", "÷"):
            if n2 != 0: res = n1 / n2
            else:
                return "Zero-ஆல் வகுக்க முடியாது." if lang == "ta-IN" else "Cannot divide by zero."
        if res is not None:
            res_str = ("%d" % int(res)) if res.is_integer() else ("%.2f" % res)
            if lang == "ta-IN":
                return "விடை: %s" % res_str
            elif lang == "ja-JP":
                return "計算結果は %s です。" % res_str
            elif lang == "hi-IN":
                return "उत्तर है: %s" % res_str
            return "The result is %s." % res_str

    return None


def _chat_api(cfg, user_text):
    lang = cfg.get("lang", "en-US")
    cache_key = (cfg.get("model", ""), lang, user_text.strip().lower())
    if cache_key in _cache:
        c_entry = _cache[cache_key]
        if isinstance(c_entry, dict):
            return {"ok": True, "speech": c_entry.get("speech", ""), "content": c_entry.get("content", ""), "actions": []}
        return {"ok": True, "speech": c_entry, "content": c_entry, "actions": []}

    now_str = time.strftime("%A, %d %B %Y, %I:%M %p")
    system = SYSTEM.replace("__NAME__", cfg.get("name") or "AI") \
                   .replace("__LANG__", LANGS.get(lang, "English")) \
                   .replace("__DATETIME__", now_str)
    _history.append({"role": "user", "content": user_text})
    
    # Token Optimization: Adaptive sliding window memory
    if len(_history) > 8:
        # Keep first exchange context + last 6 turns
        del _history[1:-6]
    while _history and _history[0]["role"] != "user":
        _history.pop(0)
    try:
        raw = _complete_resilient(cfg, system, _history)
    except Exception as e:
        if _history and _history[-1]["role"] == "user":
            _history.pop()
        return {"ok": True, "speech": _friendly(e, lang), "content": _friendly(e, lang), "actions": [], "error": str(e)}
    
    raw_content = (raw or "").strip()
    actions = []

    # 1. Universal Agentic JSON Action Extraction
    cleaned_content = raw_content
    m_json = re.search(r"```(?:json)?\s*(\{[\s\S]*?\})\s*```", raw_content, re.I)
    if m_json:
        try:
            parsed = json.loads(m_json.group(1))
            if isinstance(parsed, dict) and "actions" in parsed and isinstance(parsed["actions"], list):
                actions.extend(parsed["actions"])
                # Remove the raw JSON block so the chat message and speech only contain natural text
                cleaned_content = re.sub(r"```(?:json)?\s*\{[\s\S]*?\}\s*```", "", raw_content).strip()
        except Exception:
            pass

    speech = _spoken_summary(cleaned_content, lang)
    _history.append({"role": "assistant", "content": cleaned_content})
    if len(_cache) > 100:
        _cache.clear()
    _cache[cache_key] = {"speech": speech, "content": cleaned_content}

    # 2. Safety Net: Extract actions if the model response acknowledged a call or app launch without JSON
    if not actions:
        m_call = re.search(r"(?:place(?: that)? call to|calling|dialing|dial|call)\s+(?P<t>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)(?:\s+(?:for you|now|right away|right now|directly|\.|$))", raw_content, re.I)
        if not m_call:
            m_call = re.search(r"\b(?:call|dial|phone)\s+(?:to\s+)?(?P<t>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)(?:\s+(?:please|now|pa|da|$))", user_text, re.I)
        if m_call:
            tgt = m_call.group("t").strip()
            tgt = re.sub(r"\s+(?:for|now|right|please|pa|da)$", "", tgt, flags=re.I).strip()
            if tgt and len(tgt) < 30 and tgt.lower() not in ("you", "them", "someone", "me", "it", "him", "her"):
                actions.append({"type": "call", "target": tgt, "desc": f"Call {tgt}"})

    return {"ok": True, "speech": speech, "content": cleaned_content, "actions": actions}


# ======================================================================
#  LOCAL COMMAND ENGINE - no internet, no API key, instant
# ======================================================================
APPS = {  # spoken name (any language) -> name searched in the phone's app list
    "whatsapp": "WhatsApp", "whats app": "WhatsApp", "வாட்ஸ்அப்": "WhatsApp", "வாட்ஸ்ஆப்": "WhatsApp",
    "வாட்சப்": "WhatsApp", "ワッツアップ": "WhatsApp", "ウォッツアップ": "WhatsApp",
    "youtube": "YouTube", "you tube": "YouTube", "யூடியூப்": "YouTube", "யூட்யூப்": "YouTube", "ユーチューブ": "YouTube",
    "google": "Google", "கூகுள்": "Google", "கூகிள்": "Google", "グーグル": "Google",
    "facebook": "Facebook", "face book": "Facebook", "ஃபேஸ்புக்": "Facebook", "பேஸ்புக்": "Facebook",
    "முகநூல்": "Facebook", "フェイスブック": "Facebook",
    "instagram": "Instagram", "insta": "Instagram", "இன்ஸ்டாகிராம்": "Instagram", "இன்ஸ்டா": "Instagram", "インスタ": "Instagram",
    "telegram": "Telegram", "டெலிகிராம்": "Telegram", "chrome": "Chrome", "குரோம்": "Chrome",
    "camera": "Camera", "கேமரா": "Camera", "カメラ": "Camera", "gallery": "Gallery", "கேலரி": "Gallery",
    "photos": "Photos", "போட்டோஸ்": "Photos", "maps": "Maps", "map": "Maps", "மேப்ஸ்": "Maps", "gmail": "Gmail", "ஜிமெயில்": "Gmail",
    "settings": "Settings", "setting": "Settings", "செட்டிங்ஸ்": "Settings", "play store": "Play Store", "playstore": "Play Store",
    "ப்ளே ஸ்டோர்": "Play Store", "ப்ளேஸ்டோர்": "Play Store",
    "spotify": "Spotify", "ஸ்பாட்டிஃபை": "Spotify", "twitter": "X", "x": "X", "ட்விட்டர்": "X", "snapchat": "Snapchat",
    "messages": "Messages", "message": "Messages", "sms": "Messages", "மெசேஜ்": "Messages", "எஸ்எம்எஸ்": "Messages",
    "phone": "Phone", "dialer": "Phone", "டயலர்": "Phone", "contacts": "Contacts", "தொடர்புகள்": "Contacts",
    "clock": "Clock", "கடிகாரம்": "Clock", "calculator": "Calculator", "கால்குலேட்டர்": "Calculator",
    "notes": "Notes", "keep": "Notes", "நோட்ஸ்": "Notes", "files": "Files", "file manager": "Files", "ஃபைல்ஸ்": "Files",
    "netflix": "Netflix", "நெட்ஃபிளிக்ஸ்": "Netflix", "amazon": "Amazon", "அமேசான்": "Amazon",
    "flipkart": "Flipkart", "பிளிப்கார்ட்": "Flipkart", "zomato": "Zomato", "ஜொமாட்டோ": "Zomato",
    "swiggy": "Swiggy", "ஸ்விக்கி": "Swiggy", "paytm": "Paytm", "பேடிஎம்": "Paytm",
    "gpay": "Google Pay", "google pay": "Google Pay", "கூகுள் பே": "Google Pay", "phonepe": "PhonePe", "போன்பே": "PhonePe",
    "uber": "Uber", "ஊபர்": "Uber", "ola": "Ola", "ஓலா": "Ola",
    "hotspot": "Hotspot", "ஹாட்ஸ்பாட்": "Hotspot",
    "jiosaavn": "JioSaavn", "saavn": "JioSaavn", "ஜியோசாவன்": "JioSaavn",
    "wynk": "Wynk", "விங்க்": "Wynk", "gaana": "Gaana", "கானா": "Gaana",
    "youtube music": "YouTube Music", "yt music": "YouTube Music", "யூடியூப் மியூசிக்": "YouTube Music",
    "prime video": "Prime Video", "பிரைம் வீடியோ": "Prime Video",
    "hotstar": "Hotstar", "disney hotstar": "Hotstar", "ஹாட்ஸ்டார்": "Hotstar",
    "sonyliv": "SonyLIV", "zee5": "Zee5",
    "jiocinema": "JioCinema", "jio cinema": "JioCinema", "ஜியோ சினிமா": "JioCinema",
    "meesho": "Meesho", "மீஷோ": "Meesho",
    "myntra": "Myntra", "மிந்த்ரா": "Myntra",
    "ajio": "Ajio", "அஜியோ": "Ajio",
    "zepto": "Zepto", "செப்டோ": "Zepto",
    "blinkit": "Blinkit", "பிளிங்கிட்": "Blinkit",
    "bigbasket": "BigBasket", "பிக்பாஸ்கெட்": "BigBasket",
    "rapido": "Rapido", "ரேபிடோ": "Rapido",
    "cred": "CRED", "கிரெட்": "CRED",
}
ORD = {1: ("first", "1st", "one", "mudhal", "mudhalavadhu", "mudhalavathu", "mudhalaavadhu", "muthal", "muthalavathu", "முதல்", "முதலாவது", "முதலாம்", "1வது", "ஒன்று", "最初", "1番目"),
       2: ("second", "2nd", "two", "rendavathu", "irandavathu", "rendavadhu", "irandavadhu", "randavadhu", "rendam", "irandam", "இரண்டாவது", "இரண்டாம்", "2வது", "ரெண்டு", "இரண்டு", "2番目"),
       3: ("third", "3rd", "three", "moonravathu", "moondravathu", "munravathu", "moonravadhu", "moondravadhu", "munravadhu", "மூன்றாவது", "மூன்றாம்", "3வது", "மூன்று", "3番目"),
       4: ("fourth", "4th", "four", "naangavathu", "nalavathu", "naangavadhu", "nalavadhu", "நான்காவது", "நான்காம்", "4வது", "நான்கு", "4番目"),
       5: ("fifth", "5th", "five", "ainthavathu", "anjavathu", "ainthavadhu", "anjavadhu", "ஐந்தாவது", "ஐந்தாம்", "5வது", "ஐந்து", "5番目")}
NOUNS = ("chat", "video", "result", "item", "contact", "post", "song", "channel", "conversation", "one",
         "message", "reel", "story", "photo", "link", "web", "website", "site", "page",
         "சாட்", "வீடியோ", "பாடல்", "பதிவு", "தொடர்பு", "வலைதளம்", "இணைப்பு")
FILLER = r"\b(please|plz|pls|pannanum|pananum|pannunga|pannu|panunga|panni|pannidu|seiyunga|seiyu|podunga|podu|kodunga|sollu|pa|da)\b"
SPLIT = re.compile(r"\s*(?:,|;|\.|\bthen\b|\band\b|\bappuram\b|\bapram\b|\baprom\b|\bpinnadi\b|பிறகு|அப்புறம்|பின்னர்|その後|、)\s*", re.I)
PART = r"(?:ah|a|ai|ku|kku|la|le|ல|இல்|ஐ|க்கு|க்|கு|வ|வை|ஐயும்|வும்|を|に|で)"


UI_ELEMENT_WORDS = {
    "status", "my status", "story", "stories", "chats", "chat", "calls", "call", "updates", "channels",
    "community", "communities", "reels", "shorts", "profile", "my profile", "account", "my account",
    "settings", "search", "camera", "cart", "my cart", "orders", "my orders", "menu", "notifications",
    "messages", "message", "inbox", "sent", "drafts", "feed", "posts", "post", "saved", "scanner",
    "qr code", "first chat", "new chat", "group", "new group", "starred", "info", "details",
    "audio", "video", "photo", "document", "file", "media", "tab", "page",
    # Tamil
    "ஸ்டேட்டஸ்", "மை ஸ்டேட்டஸ்", "சாட்", "சாட்ஸ்", "அப்டேட்ஸ்", "கால்", "கால்ஸ்", "ப்ரொஃபைல்",
    "ரீல்ஸ்", "ஸ்டோரி", "போஸ்ட்", "கேமரா", "மெனு", "நோட்டிபிகேஷன்", "கார்ட்", "செய்தி"
}


def _app_name(raw):
    t = raw.strip().lower()
    t_clean = re.sub(r"^(?:the|my|a)\s+", "", t)
    t_clean = re.sub(r"\s+(app|application|ஆப்|செயலி|アプリ)$", "", t_clean).strip()
    for k in sorted(APPS, key=len, reverse=True):
        if t_clean == k or t_clean.startswith(k + " ") or t_clean.endswith(" " + k):
            return APPS[k]
    # Check if this raw target is explicitly requested with the word 'app'
    if re.search(r"\b(app|application|ஆப்|செயலி|アプリ)\b", t):
        return t_clean.title() if t_clean else None
    # If it is an in-app UI element or status, do NOT treat it as an app name
    if t in UI_ELEMENT_WORDS or t_clean in UI_ELEMENT_WORDS or "status" in t or "ஸ்டேட்டஸ்" in t:
        return None
    return raw.strip().title() if t else None


def _digits(t):
    d = re.sub(r"[\s\-()]", "", t)
    return d if re.fullmatch(r"\+?\d{3,15}", d) else None


def _ordinal(t):
    for n, words in ORD.items():
        for w in words:
            if re.search(r"(?:^|\s|\b)" + re.escape(w) + r"(?:\s|\b|$|[,\.!?])", t):
                return n
    m = re.search(r"(\d{1,2})\s*(?:st|nd|rd|th|வது|vathu|vadhu)?\s*(?:chat|video|result|item|post|one|பாடல்|சாட்|வீடியோ)", t) or \
        re.search(r"(?:chat|video|result|item|open|play|போடு|திற)\s+(?:no\.?\s*)?(\d{1,2})\b", t) or \
        re.search(r"\b(\d{1,2})(?:st|nd|rd|th)\b", t) or \
        re.search(r"(?:^|\s)(\d{1,2})\s*(?:or|\/)\s*(\d{1,2})(?:st|nd|rd|th)?", t)
    if m:
        for g in m.groups():
            if g and g.isdigit():
                return int(g)
    m_single = re.search(r"\b(?:open|play|thira|podu|select)\s+(\d{1,2})\b", t)
    if m_single:
        return int(m_single.group(1))
    return None


LEAD = re.compile(r"^(?:(?:hey|hi|hello|ok|okay|bro|da|dei|machi|sir|please|plz|pls|kindly|just|now|"
                  r"can you|could you|would you|will you|can u|could u|can i|i want you to|i want to|i would like to|"
                  r"i need to|i need you to|let's|lets|go ahead and|try to|nee|neenga)\s+)+")
TAIL = re.compile(r"(?:\s+(?:please|plz|pls|now|for me|bro|da|pa|ok|okay|quickly|thanks|thank you))+$")
_assistant = {"name": "", "stop_word": "stop"}


def _norm(p):
    p = p.strip().lower()
    nm = _assistant.get("name", "")
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
    """One clause -> (list_of_actions, kind) or None."""
    t = _norm(p)
    if not t:
        return None

    # --- system buttons
    sys_home = ("go home", "home", "home screen", "ஹோம் போ", "ஹோம்", "முகப்புக்கு போ", "முகப்பு", "ホーム")
    if t in sys_home or re.match(r"^(?:go\s+to\s+)?home(?:\s+screen)?$", t):
        return [{"type": "system", "name": "home", "desc": "Go Home"}], "ok"

    sys_back = ("back", "go back", "thirumba po", "pinnadi po", "பின்னாடி போ", "பின்னே போ", "பேக் போ", "戻る")
    if t in sys_back or re.match(r"^(?:go\s+)?back$", t):
        return [{"type": "system", "name": "back", "desc": "Go Back"}], "ok"

    sys_rec = ("recents", "recent apps", "ரெசென்ட்ஸ்", "ரீசென்ட்ஸ்", "சமீபத்திய செயலிகள்")
    if t in sys_rec or "recents" in t or "recent apps" in t:
        return [{"type": "system", "name": "recents", "desc": "Recent Apps"}], "ok"

    if any(k in t for k in ("screenshot", "screen shot", "ஸ்கிரீன்ஷாட்", "ஸ்கிரீன் ஷாட்", "ஸ்கிரீன்சாட்", "スクリーンショット")):
        return [{"type": "system", "name": "screenshot", "desc": "Take Screenshot"}], "screenshot"

    if any(k in t for k in ("lock phone", "lock screen", "lock", "லாக் பண்ணு", "போன் லாக்", "திரை பூட்டு")):
        return [{"type": "system", "name": "lock", "desc": "Lock Screen"}], "ok"

    if any(k in t for k in ("notifications", "notification", "நோட்டிபிகேஷன்", "அறிவிப்புகள்")):
        return [{"type": "system", "name": "notifications", "desc": "Notifications"}], "ok"

    if "quick settings" in t:
        return [{"type": "system", "name": "quick_settings", "desc": "Quick Settings"}], "ok"

    # --- assistant stop command: custom stop word + universal stop words
    custom_stop = (_assistant.get("stop_word") or "stop").strip().lower()
    stop_pat = r"^(?:hey\s+|ok\s+|please\s+)?(?:" + re.escape(custom_stop) + r")(?:\s+(?:please|pannu|panu|podu|vai|seiy|pa|da))?$"
    universal_stops = (
        "stop", "stop it", "shut up", "be quiet", "cancel", "pause", "enough", "halt",
        "நிறுத்து", "நிறுத்துங்க", "போதும்", "நில்", "ஸ்டாப்", "நிறுத்துப்பா", "நிறுத்துடா",
        "போதும் நிறுத்து", "பேசாத", "அமைதியா இரு", "வாயை மூடு", "niruthu", "pothum",
        "ストップ", "止めて", "やめて", "静かに"
    )
    if t == custom_stop or re.match(stop_pat, t, re.IGNORECASE) or any(t == s or t.startswith(s + " ") or t.endswith(" " + s) for s in universal_stops):
        return [{"type": "stop_assistant", "desc": "Stop Assistant"}], "stop"

    # --- torch / flashlight
    torch_words = ("torch", "flashlight", "flash light", "tochu", "டார்ச்", "விளக்கு", "லைட்", "ライト")
    if any(w in t for w in torch_words):
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "அணை", "ஆஃப்", "ஆப்", "மூடு", "消"))
        return [{"type": "flashlight", "on": not is_off, "desc": "Torch Off" if is_off else "Torch On"}], "torch_off" if is_off else "torch_on"

    # --- Wi-Fi
    wifi_words = ("wifi", "wi-fi", "வைஃபை", "வைபை", "ワイファイ")
    if any(w in t for w in wifi_words):
        if "settings" in t or "செட்டிங்ஸ்" in t or "அமைப்புகள்" in t:
            return [{"type": "settings", "page": "wifi", "desc": "Wi-Fi Settings"}], "ok"
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "அணை", "ஆஃப்", "ஆப்", "மூடு", "disable"))
        return [{"type": "wifi", "on": not is_off, "desc": "Wi-Fi Off" if is_off else "Wi-Fi On"}], "wifi_off" if is_off else "wifi_on"

    # --- Bluetooth
    bt_words = ("bluetooth", "blue tooth", "ப்ளூடூத்", "ブルートゥース")
    if any(w in t for w in bt_words):
        if "settings" in t or "செட்டிங்ஸ்" in t or "அமைப்புகள்" in t:
            return [{"type": "settings", "page": "bluetooth", "desc": "Bluetooth Settings"}], "ok"
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "அணை", "ஆஃப்", "ஆப்", "மூடு", "disable"))
        return [{"type": "bluetooth", "on": not is_off, "desc": "Bluetooth Off" if is_off else "Bluetooth On"}], "bt_off" if is_off else "bt_on"

    # --- Mobile Data
    if any(w in t for w in ("mobile data", "cellular data", "மொபைல் டேட்டா", "டேட்டா ஆன்", "டேட்டா ஆஃப்")):
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "அணை", "ஆஃப்", "ஆப்", "மூடு", "disable"))
        return [{"type": "mobile_data", "on": not is_off, "desc": "Mobile Data Off" if is_off else "Mobile Data On"}], "data_off" if is_off else "data_on"
    if any(w in t for w in ("hotspot", "ஹாட்ஸ்பாட்")):
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "அணை", "ஆஃப்", "ஆப்", "மூடு", "disable"))
        return [{"type": "hotspot", "on": not is_off, "desc": "Hotspot Off" if is_off else "Hotspot On"}], "hotspot_off" if is_off else "hotspot_on"
    if any(w in t for w in ("airplane mode", "flight mode", "ஏரோப்ளேன் மோட்")):
        return [{"type": "settings", "page": "airplane", "desc": "Airplane Mode"}], "ok"

    # --- Brightness & Display
    if any(w in t for w in ("brightness", "பிரகாசம்", "வெளிச்சம்")):
        is_down = any(w in t for w in ("down", "decrease", "reduce", "குறை", "low", "கம்மி"))
        return [{"type": "brightness", "dir": "down" if is_down else "up", "desc": "Brightness"}], "ok"

    # --- Screen Auto-Rotate
    if any(w in t for w in ("auto rotate", "screen rotation", "ரொட்டேஷன்", "சுழற்சி")):
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "ஆஃப்", "ஆப்", "மூடு", "disable"))
        return [{"type": "rotation", "on": not is_off, "desc": "Rotation Off" if is_off else "Rotation On"}], "ok"

    # --- Do Not Disturb (DND)
    if any(w in t for w in ("do not disturb", "dnd", "அமைதி பயன்முறை", "சைலன்ட் மோட்")):
        is_off = any(w in t for w in ("off", "aff", "நிறுத்து", "ஆஃப்", "ஆப்", "மூடு", "disable"))
        return [{"type": "dnd", "on": not is_off, "desc": "DND"}], "ok"

    # --- Location / GPS
    if any(w in t for w in ("location", "gps", "இருப்பிடம்", "லொகேஷன்")):
        return [{"type": "location", "desc": "Location"}], "ok"

    # --- Read Notifications
    if any(w in t for w in ("read notification", "read notifications", "read my messages", "read incoming",
                            "வந்த செய்தியை படி", "வந்த செய்திகளை படி", "நோட்டிபிகேஷன் படி", "புதிய மெசேஜ் படி", "செய்தி படி")):
        return [{"type": "read_notifications", "desc": "Read Notifications"}], "read_notifications"

    # --- Read SMS
    if any(w in t for w in ("read sms", "read my sms", "read text messages", "எஸ்எம்எஸ் படி", "வந்த எஸ்எம்எஸ் படி")):
        return [{"type": "read_sms", "desc": "Read SMS"}], "read_sms"

    # --- Camera Photo
    if any(w in t for w in ("take photo", "take picture", "take a photo", "take a picture", "click photo",
                            "படம் எடு", "போட்டோ எடு", "புகைப்படம் எடு", "கேமராவில் படம்")):
        return [{"type": "take_photo", "desc": "Take Photo"}], "take_photo"

    # --- Screen Awareness / Reading
    if any(w in t for w in ("read screen", "read the screen", "what is on screen", "what's on screen", "screen la enna irukku", "திரையை படி", "ஸ்கிரீனை படி", "திரையில் என்ன உள்ளது")):
        return [{"type": "ui_read_screen", "desc": "Read Screen"}], "read_screen"

    # --- Battery Status & Percentage
    if any(w in t for w in ("battery status", "what is my battery", "battery percentage", "battery level", "battery percent", "how much battery",
                            "battery eppadi", "battery evvalavu", "பேட்டரி எவ்வளவு", "பேட்டரி நிலை", "பேட்டரி பர்சன்ட்", "பேட்டரி பர்சென்டேஜ்",
                            "बैटरी कितनी है", "बैटरी स्टेटस", "배터리 잔량", "バッテリー残量")):
        return [{"type": "battery_status", "desc": "Check Battery"}], "battery"

    # --- Auto Skip-Ad Engine
    if any(w in t for w in ("skip ad", "skip the ad", "skip advertisement", "skip pannu", "ad skip", "skip video ad",
                            "விளம்பரத்தை தவிர்", "விளம்பரம் நீக்கு", "விளம்பரம் தவிர்", "விளம்பரம் வேண்டாம்",
                            "विज्ञापन छोड़ो", "विज्ञापन हटाएं", "広告をスキップ", "広告スキップ", "광고 건너뛰기")):
        return [{"type": "ui_skip_ad", "desc": "Skip Ad"}], "skip_ad"

    # --- Auto-Reels Cruise Control Mode
    if any(w in t for w in ("auto scroll reels", "reels cruise", "auto reels", "cruise mode", "தானாக ரீல்ஸ் மாத்து", "தானாக ரீல்ஸ் ஓடு", "रील ऑटो स्क्रॉल", "릴스 자동 재생")):
        is_stop = any(w in t for w in ("stop", "off", "நிறுத்து", "ஆஃப்", "rok", "band"))
        return [{"type": "ui_reels_cruise", "on": not is_stop, "desc": "Reels Cruise Off" if is_stop else "Reels Cruise On"}], "cruise_off" if is_stop else "cruise_on"

    # --- Universal Form Auto-Fill & OTP Grabber
    if any(w in t for w in ("fill form", "fill my details", "fill details", "fill address", "enter my address", "படிவத்தை நிரப்பு", "விவரங்களை நிரப்பு", "फॉर्म भरो", "양식 작성")):
        return [{"type": "ui_fill_form", "desc": "Fill Form"}], "fill_form"
    if any(w in t for w in ("otp", "ஓடிபி", "read otp", "enter otp", "fill otp", "type otp", "put otp", "tell otp", "show otp", "get otp", "otp sollu", "otp enna", "what is otp",
                            "ஓடிபி போடு", "ஓடிபி நிரப்பு", "ஓடிபி சொல்லு", "ஓடிபி என்ன", "ओटीपी भरो", "ओटीपी डालो", "ओटीपी बताओ", "OTP 입력")):
        return [{"type": "ui_read_otp", "desc": "Enter OTP"}], "fill_otp"

    # --- Voice Grid & Precision Coordinate Gestures
    m_grid = re.match(r"^(?:tap|click|touch|தொடு|கிளிக்)\s+(?P<pos>top\s+left|top\s+right|top\s+center|center|middle|bottom\s+left|bottom\s+right|bottom\s+center|\d{1,2})$", t)
    if m_grid:
        pos_id = m_grid.group("pos").replace(" ", "_")
        return [{"type": "ui_grid_tap", "pos": pos_id, "desc": f"Tap {pos_id}"}], "grid_tap:" + pos_id

    # --- Multi-Step Hands-Free Chain: "<app> open panni <query> thedu"
    m_open_search = re.match(r"^(?P<a>.+?)\s*(?:ah|a|ஐ)?\s*(?:open|thira|launch)\s+(?:(?:panni|pannitu|செய்து|செய்துவிட்டு|அப்புறம்|பிறகு)\s+)?(?P<q>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:search|thedu|தேடு)(?:\s+(?:pannu|podu))?$", t)
    if m_open_search:
        a_raw = m_open_search.group("a").strip()
        q_raw = m_open_search.group("q").strip()
        app_res = _app_name(a_raw) or a_raw.title()
        return [
            {"type": "open_app", "name": app_res, "desc": f"Open {app_res}"},
            {"type": "ui_search", "query": q_raw, "submit": True, "desc": f"Search '{q_raw}'"}
        ], "search:" + app_res

    # --- Universal App Ordering / Cart / Item Action: "<app> la <item> add / order / cart / vaangu"
    m_app_cart = re.match(r"^(?P<a>.+?)\s*(?:la|le|ல|இல்|-la)\s+(?P<item>.+?)\s*(?:nu|ன்னு|னு|ah|a|ஐ)?\s*(?P<act>add|order|cart|vaangu|வாங்கு|சேர்)(?:\s+(?:pannu|podu|vidu|seiy))?$", t) or \
                 re.match(r"^(?:order|add)\s+(?P<item>.+?)\s+(?:in|on)\s+(?P<a>.+)$", t)
    if m_app_cart:
        a_raw = m_app_cart.group("a").strip()
        i_raw = m_app_cart.group("item").strip()
        app_res = _app_name(a_raw) or a_raw.title()
        return [
            {"type": "open_app", "name": app_res, "desc": f"Open {app_res}"},
            {"type": "ui_search", "query": i_raw, "submit": True, "desc": f"Search '{i_raw}'"},
            {"type": "ui_open_nth", "n": 1, "desc": "Select Item"},
            {"type": "ui_smart_click", "action": "add to cart", "desc": "Add to Cart"}
        ], "cart"

    # --- Uninstall App
    m_uninst = re.match(r"^(?:uninstall|remove\s+app|delete\s+app|நீக்கு|அன்இன்ஸ்டால்)\s+(?P<a>.+)$", t) or \
               re.match(r"^(?P<a>.+?)\s*" + PART + r"?\s*(?:uninstall|அன்இன்ஸ்டால்|நீக்கு|அழி)(?:\s+(?:pannu|seiyu|vidu))?$", t)
    if m_uninst:
        u_name = m_uninst.group("a").strip()
        u_app = _app_name(u_name) or u_name.title()
        return [{"type": "uninstall_app", "name": u_app, "desc": f"Uninstall {u_app}"}], "uninstall:" + u_app

    # --- Reels, Shorts, and Vertical Feeds (Instagram, YouTube Shorts, TikTok)
    if any(w in t for w in ("next reel", "next reels", "adutha reel", "அடுத்த ரீல்ஸ்", "next shorts", "next video in reels", "reels maathu")):
        return [{"type": "ui_swipe_reels", "next": True, "desc": "Next Reel"}], "reels_next"
    if any(w in t for w in ("previous reel", "prev reel", "munthaiya reel", "முந்தைய ரீல்ஸ்", "prev shorts", "முந்தைய வீடியோ")):
        return [{"type": "ui_swipe_reels", "next": False, "desc": "Previous Reel"}], "reels_prev"

    # --- Universal Directional Swipes (Stories, Carousels, Tabs, Feeds)
    if any(w in t for w in ("swipe left", "left swipe", "left la swipe", "இடதுபுறம் ஸ்வைப்", "next story", "story maathu", "அடுத்த ஸ்டோரி", "left swipe pannu")):
        return [{"type": "ui_swipe", "dir": "left", "desc": "Swipe Left"}], "swipe_left"
    if any(w in t for w in ("swipe right", "right swipe", "right la swipe", "வலதுபுறம் ஸ்வைப்", "prev story", "previous story", "முந்தைய ஸ்டோரி", "right swipe pannu")):
        return [{"type": "ui_swipe", "dir": "right", "desc": "Swipe Right"}], "swipe_right"
    if any(w in t for w in ("swipe up", "mela swipe", "மேலே ஸ்வைப்", "swipe up pannu")):
        return [{"type": "ui_swipe", "dir": "up", "desc": "Swipe Up"}], "swipe_up"
    if any(w in t for w in ("swipe down", "keela swipe", "கீழே ஸ்வைப்", "swipe down pannu")):
        return [{"type": "ui_swipe", "dir": "down", "desc": "Swipe Down"}], "swipe_down"
    if any(w in t for w in ("open reels", "reels open", "reels போடு", "reels பாரு", "ரீல்ஸ் போடு", "ரீல்ஸ் பாரு")) or t in ("reels", "reel", "ரீல்ஸ்"):
        return [{"type": "instagram_reels", "desc": "Instagram Reels"}], "ok"
    if any(w in t for w in ("instagram dm", "insta dm", "instagram message", "insta chat", "இன்ஸ்டா மெசேஜ்", "இன்ஸ்டா சாட்")):
        return [{"type": "instagram_dm", "desc": "Instagram Messages"}], "ok"

    # --- Cross-App Message Sending with Text: "whatsapp la appa ku hello nu message anuppu"
    m_send_msg = re.match(r"^(?:send\s+(?:message|msg)\s+to\s+)?(?P<app>whatsapp|instagram|insta|telegram)\s+(?:la|le|ல|இல்|-la)?\s*(?P<tgt>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)\s*(?:ku|kku|க்கு|வுக்கு|ukku|vukku)\s+(?P<msg>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:send|anuppu|message\s*anuppu|செய்தி\s*அனுப்பு)(?:\s+(?:pannu|vidu|seiy|pa|da))?$", t) or \
                 re.match(r"^send\s+(?:a\s+)?(?:message|msg)\s+to\s+(?P<tgt>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)\s+(?:in|on)\s+(?P<app>whatsapp|instagram|insta|telegram)(?:\s*(?:saying|as|:)\s*|\s+)(?P<msg>.+)$", t) or \
                 re.match(r"^(?P<tgt>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)\s*(?:ku|kku|க்கு|வுக்கு)\s+(?P<msg>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:message\s*anuppu|செய்தி\s*அனுப்பு|send\s*pannu)(?:\s+(?:in|on)\s+(?P<app>whatsapp|telegram))?$", t)
    if m_send_msg:
        a_name = _app_name(m_send_msg.group("app") or "WhatsApp")
        t_name = m_send_msg.group("tgt").strip()
        m_body = m_send_msg.group("msg").strip()
        if a_name and t_name and m_body:
            return [{"type": "chat", "app": a_name, "target": t_name, "text": m_body, "desc": f"Send to {t_name}: {m_body}"}], "send_to:" + t_name

    # --- Direct Chat opening (WhatsApp, Instagram, Telegram)
    m_chat = re.match(r"^(?:open\s+)?(?:chat\s+(?:with\s+)?(?P<t1>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)\s+(?:in|on)\s+(?P<app1>whatsapp|instagram|insta|telegram))", t) or \
             re.match(r"^(?:open\s+)?(?P<app2>whatsapp|instagram|insta|telegram)\s+(?:la|le|ல|இல்|-la)?\s*(?:chat\s+(?:with\s+)?)?(?P<t2>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)\s*(?:chat|சாட்)?\s*(?:open|thira|திற|போடு|vai)?$", t)
    if m_chat:
        a_name = m_chat.group("app1") or m_chat.group("app2")
        t_name = m_chat.group("t1") or m_chat.group("t2")
        if a_name and t_name:
            t_name = t_name.strip()
            if t_name not in ("open", "thira", "app", "chat", "la"):
                return [{"type": "chat", "app": a_name, "target": t_name, "desc": f"Chat with {t_name}"}], "chat"

    # --- Music across Spotify, JioSaavn, Wynk, Gaana, YouTube Music
    m_music = re.match(r"^(?:play|போடு|பாடல் போடு|பாட்டு போடு)\s+(?P<q>.+?)\s+(?:in|on)\s+(?P<a>spotify|jiosaavn|wynk|gaana|youtube\s*music|yt\s*music)$", t) or \
              re.match(r"^(?P<a>spotify|jiosaavn|wynk|gaana|youtube\s*music|yt\s*music)\s+(?:la|le|ல|இல்)?\s*(?P<q>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:play|podu|போடு|பாடல் போடு|பாட்டு போடு)$", t)
    if m_music:
        app_m = m_music.group("a").strip()
        q_m = m_music.group("q").strip()
        if q_m:
            return [{"type": "music_play", "app": app_m, "query": q_m, "desc": f"Play on {app_m}: {q_m}"}], "music:" + q_m

    # --- YouTube direct video play
    m_yt_play = re.match(r"^(?:play|போடு|பாடல் போடு|வீடியோ போடு)\s+(?P<q1>.+?)\s+(?:in|on)\s+youtube$", t) or \
                re.match(r"^(?:youtube|யூடியூப்)\s+(?:la|le|ல|இல்)?\s*(?P<q2>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:play|podu|போடு|பாடல் போடு|video open pannu|வீடியோ திற|வீடியோ போடு)$", t)
    if m_yt_play:
        q_yt = (m_yt_play.group("q1") or m_yt_play.group("q2") or "").strip()
        if q_yt:
            return [{"type": "youtube_play", "query": q_yt, "desc": f"Play on YouTube: {q_yt}"}], "yt:" + q_yt

    # --- volume
    if any(w in t for w in ("volume", "வால்யூம்", "சத்தம்", "ஒலி", "mute", "silent", "மியூட்", "சைலன்ட்")):
        if any(w in t for w in ("mute", "silent", "மியூட்", "சைலன்ட்")):
            d = "mute"
        elif any(w in t for w in ("down", "kura", "kuraa", "kurai", "less", "decrease", "low", "குறை", "இறக்கு")):
            d = "down"
        else:
            d = "up"
        return [{"type": "volume", "dir": d, "desc": f"Volume {d}"}], "ok"

    # --- media playback controls
    if any(w in t for w in ("next song", "next track", "next paattu", "அடுத்த பாட்டு", "அடுத்த பாடல்")):
        return [{"type": "media", "cmd": "next", "desc": "Next Song"}], "media"
    if any(w in t for w in ("previous song", "prev song", "prev track", "முந்தைய பாட்டு", "முந்தைய பாடல்")):
        return [{"type": "media", "cmd": "prev", "desc": "Previous Song"}], "media"
    if any(w in t for w in ("pause music", "stop music", "pause song", "பாட்டை நிறுத்து", "இசையை நிறுத்து")):
        return [{"type": "media", "cmd": "pause", "desc": "Pause Music"}], "media"
    if any(w in t for w in ("play music", "resume music", "play song", "பாட்டை போடு", "பாடல் போடு", "இசையை இயக்கு")):
        return [{"type": "media", "cmd": "play", "desc": "Play Music"}], "media"

    # --- calls
    if re.search(r"\b(answer|pick up|attend)\b.*\bcall\b", t) or any(w in t for w in ("அழைப்பை எடு", "போன் எடு")):
        return [{"type": "answer_call", "desc": "Answer Call"}], "ok"
    if re.search(r"\b(end|cut|disconnect|hang ?up)\b.*\bcall\b|\bcall\b.*\b(end|cut)\b", t) or any(w in t for w in ("அழைப்பை முடி", "போன் வை", "கால் கட்")):
        return [{"type": "end_call", "desc": "End Call"}], "ok"

    # Direct call patterns: "call appa", "call to dad", "can you call mom", "dial appa"
    m_call = re.match(r"^(?:call|dial|phone|make a call to|place a call to|ring)\s+(?:to\s+)?(?:my\s+)?(?P<t>.+?)(?:\s+(?:please|plz|now|pa|da))?$", t)
    if not m_call:
        # Suffix/Dative call patterns: "appa ku call pannu", "appa vukku call", "அப்பாவுக்கு கால் பண்ணு", "appa call"
        m_call = re.match(r"^(?:my\s+)?(?P<t>.+?)(?:\s+(?:ku|kku|vukku|ukku|ah|la|nu)|-(?:ku|kku|ukku|vukku|ah)|வுக்கு|க்கு|க்குக்|ஐ)?\s*(?:call|phone|dial|போன்|கால்|அழைப்பு|கூப்பிடு)(?:\s+(?:pannu|panni vidu|podu|vai|vidu|seiy|செய்|பண்ணு|போடு))?$", t)
    if not m_call:
        # Informal: "<name> call", e.g. "appa call", "dad call", "amma call"
        m_call = re.match(r"^(?P<t>[a-zA-Z0-9\u0B80-\u0BFF\s]+?)\s+(?:call|phone|dial|போன்|கால்)(?:\s+(?:pannu|podu|vai|vidu|seiy))?$", t)

    if m_call and not any(w in t for w in ("search", "thedu", "open", "in whatsapp", "தேடு")):
        tgt = m_call.group("t").strip()
        tgt = re.sub(r"^(?:to|my)\s+", "", tgt).strip()
        tgt = re.sub(r"\s+(?:to|pannu|panunga|podu|vidu|seiyu|sollu)$", "", tgt).strip()
        tgt = re.sub(r"\s+" + PART + r"$", "", tgt).strip()
        if tgt and tgt not in ("search", "open", "thedu"):
            num = _digits(tgt) or tgt
            return [{"type": "call", "target": num, "desc": f"Call {num}"}], "call"

    # --- alarm / timer
    if any(w in t for w in ("alarm", "அலாரம்", "アラーム")):
        m = re.search(r"(\d{1,2})(?:[:.\s](\d{2}))?\s*(am|pm|மணி|மணியளவில்)?", t)
        if m:
            h, mi = int(m.group(1)), int(m.group(2) or 0)
            ap = (m.group(3) or "").lower()
            if "pm" in ap and h < 12: h += 12
            if "am" in ap and h == 12: h = 0
            return [{"type": "alarm", "hour": h % 24, "minute": mi % 60, "label": "", "desc": f"Alarm {h%24}:{mi:02d}"}], "ok"
    m = re.search(r"timer.*?(\d+)\s*(second|sec|minute|min|nimisham|நிமிடம்)|(\d+)\s*(second|sec|minute|min|nimisham|நிமிடம்).*timer", t)
    if m:
        g = m.groups(); n = int(g[0] or g[2]); u = g[1] or g[3]
        secs = n if u.startswith("s") else n * 60
        return [{"type": "timer", "seconds": secs, "desc": f"Timer {n}m"}], "ok"

    # --- copy to clipboard
    m = re.match(r"^(?:copy|காப்பி பண்ணு|நகலெடு)\s+(?P<x>.+)$", t)
    if m:
        txt = m.group("x").strip()
        return [{"type": "clipboard", "text": txt, "desc": f"Copy '{txt[:20]}'"}], "clipboard"

    # --- direct URL / website (e.g. "open google.com", "open youtube.com", "open amazon.in", "cricbuzz.com open pannu")
    m_url = re.search(r"\b(?P<u>[a-zA-Z0-9\-]+\.(?:com|in|org|net|ai|co|io|gov|edu|info)(?:/[^\s]*)?)\b", t)
    if m_url and (any(w in t for w in ("open", "go to", "browse", "view", "thira", "thiranga", "திற", "site", "website")) or t.startswith(m_url.group("u")) or t.endswith(m_url.group("u"))):
        u = m_url.group("u").strip()
        if not u.startswith("http"):
            u = "https://" + u
        return [{"type": "open_url", "url": u, "desc": f"Open {u}"}], "ok"

    # --- settings pages
    m = re.search(r"\b(wifi|wi-fi|bluetooth|display|sound|battery)\b.*\bsettings?\b|\bsettings?\b.*\b(wifi|bluetooth|display|sound|battery)\b", t)
    if m:
        pg = next(x for x in m.groups() if x).replace("wi-fi", "wifi")
        return [{"type": "settings", "page": pg, "desc": f"Settings: {pg}"}], "ok"

    # --- navigate
    m = re.match(r"^(?:navigate|directions?|route)(?:\s+to)?\s+(?P<q>.+)$", t) or \
        re.match(r"^(?P<q>.+?)\s*(?:வழி காட்டு|ரூட் காட்டு)$", t)
    if m:
        return [{"type": "navigate", "query": m.group("q").strip(), "desc": f"Navigate to {m.group('q').strip()}"}], "ok"

    # --- scroll
    if any(w in t for w in ("scroll", "ஸ்க்ரோல்", "keela", "mela", "கீழே", "மேலே")):
        is_up = any(w in t for w in ("up", "mela", "மேலே"))
        return [{"type": "ui_scroll", "dir": "up" if is_up else "down", "desc": "Scroll Up" if is_up else "Scroll Down"}], "ok"

    # --- open Nth item (supports "whatsapp la open first chat", "open first chat in instagram", etc.)
    app_target = None
    t_core = t
    m_app_ord = re.match(r"^(?P<a>[a-zA-Z\u0B80-\u0BFF\s]+?)\s+(?:la|le|ல|இல்|-la)\s+(?P<rest>.+)$", t)
    if m_app_ord:
        cand = _app_name(m_app_ord.group("a"))
        if cand:
            app_target = cand
            t_core = m_app_ord.group("rest").strip()
    else:
        m_app_in = re.search(r"\b(?:in|on|inside)\s+(?P<a>[a-zA-Z\u0B80-\u0BFF\s]+)$", t)
        if m_app_in:
            cand = _app_name(m_app_in.group("a"))
            if cand:
                app_target = cand
                t_core = re.sub(r"\b(?:in|on|inside)\s+" + re.escape(m_app_in.group("a")) + r"$", "", t).strip()

    # --- open last received file in chat (WhatsApp, Telegram, etc.)
    # "whatsapp la chat la last ah vantha files ah open pannanum", "open last file in whatsapp", "last file ah open pannu"
    m_last_file = re.search(r"\b(?:last|latest|kadasi|kadaisi|கடைசி|கடைசியாக)\b.*?\b(?:file|files|document|doc|pdf|media|photo|video|கோப்பு|கோப்பை|ஃபைல்|பைல்)\b", t) or \
                  re.search(r"\b(?:file|files|document|doc|pdf|media|கோப்பு|ஃபைல்)\b.*?\b(?:last|latest|kadasi|kadaisi|கடைசி)\b", t)
    if m_last_file and (any(w in t for w in ("open", "thira", "thiranga", "click", "திற", "பாரு", "பார்", "pannanum", "pananum", "பண்ணனும்")) or any(w in t for w in ("vantha", "வந்த", "chat", "சாட்"))):
        acts = []
        if app_target and app_target in ("WhatsApp", "Telegram"):
            acts.append({"type": "open_app", "name": app_target, "desc": f"Open {app_target}"})
        acts.append({"type": "ui_open_last_file", "desc": "Open Last File"})
        return acts, "last_file"

    n = _ordinal(t_core)
    if n and (any(w in t_core for w in ("open", "click", "tap", "play", "thira", "thiranga", "select", "திற", "தொடு", "போடு", "itha", "ithai", "இதை")) or any(w in t_core for w in NOUNS) or re.search(r"\b\d+(?:st|nd|rd|th)?\s*(?:video|chat|item|post|song|one|web)\b", t_core)):
        acts = []
        if app_target:
            acts.append({"type": "open_app", "name": app_target, "desc": f"Open {app_target}"})
        acts.append({"type": "ui_open_nth", "n": n, "desc": f"Open Item #{n}"})
        return acts, "nth"

    # --- direct Google Search (e.g. "google search python", "google la search python", "search python on google", "google la search")
    m_g = re.match(r"^(?:google|கூகுள்|கூகிள்)\s+(?:la|le|ல|இல்)?\s*(?:search|thedu|தேடு)(?:\s+(?P<q>.+))?$", t) or \
          re.match(r"^(?:search|thedu|தேடு)\s+(?:in\s+|on\s+)?(?:google|கூகுள்|கூகிள்)(?:\s+(?:for\s+)?(?P<q>.+))?$", t) or \
          re.match(r"^(?:search|thedu|தேடு)\s+(?P<q>.+?)\s+(?:in|on)\s+(?:google|கூகுள்|கூகிள்)$", t)
    if m_g:
        q = m_g.group("q")
        if q:
            q = re.sub(r"\s*(?:nu|ன்னு|னு)$", "", q.strip()).strip()
        if q:
            return [{"type": "web_search", "query": q, "desc": f"Google Search: '{q}'"}], "google:" + q
        else:
            return [{"type": "open_url", "url": "https://www.google.com", "desc": "Open Google"}], "google_page"

    # --- search inside an app / search
    app, q = None, None
    m = re.match(r"^(?:search|find|look for)\s+(?:for\s+)?(?P<q>.+?)\s+(?:in|on|at)\s+(?P<a>.+)$", t)
    if m:
        q, app = m.group("q"), m.group("a")
    if q is None:
        m = re.match(r"^(?P<a>.+?)\s+" + r"(?:la|le|ல|இல்|-la)" + r"\s+(?P<q>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:search|thedu|thedunga|theduga|தேடு|தேடுங்க|find|検索)(?=\s|$).*$", t)
        if m:
            q, app = m.group("q"), m.group("a")
    if q is None:
        m = re.match(r"^(?:search|find|look for)\s+(?:for\s+)?(?P<q>.+)$", t) or \
            re.match(r"^(?P<q>.+?)\s*(?:nu|ன்னு|னு|ஐ|ah|a)?\s*(?:search|thedu|thedunga|theduga|தேடு|தேடுங்க|find|検索)(?=\s|$).*$", t)
        if m:
            q = m.group("q")
    if q:
        q = re.sub(r"\s*(?:nu|ன்னு|னு)$", "", q.strip()).strip()
        appn = _app_name(app) if app else last_app
        qd = _digits(q)
        q = qd or q
        acts = []
        if appn == "YouTube" or "youtube" in (app or "").lower() or "யூடியூப்" in t:
            return [{"type": "youtube", "query": q, "desc": f"YouTube: '{q}'"}], "search:" + (appn or "YouTube")
        if appn == "Google" or "google" in (app or "").lower() or "கூகுள்" in (app or "").lower() or "கூகிள்" in (app or "").lower():
            return [{"type": "web_search", "query": q, "desc": f"Google Search: '{q}'"}], "google:" + q
        if app:
            acts.append({"type": "open_app", "name": appn, "desc": f"Open {appn}"})
        acts.append({"type": "ui_search", "query": q, "submit": True, "desc": f"Search '{q}'"})
        return acts, "search:" + (appn or "")

    # --- cancel / erase typed message
    cancel_words = ("cancel", "erase", "clear", "clear text", "delete message", "delete text",
                    "ரத்து", "அழி", "வேண்டாம்", "நீக்கு", "cancel pannu", "cancel panu",
                    "ali", "azhi", "erase pannu", "clear pannu", "erase msg", "cancel msg",
                    "clear msg", "delete msg", "செய்தியை அழி", "டைப் பண்ணதை அழி", "அழித்து விடு", "キャンセル", "消去")
    if t in cancel_words or re.match(r"^(?:cancel|erase|clear|delete\s+(?:message|text|msg)|ரத்து|அழி)(?:\s+(?:it|pannu|panu|seiyu|seiy|vidu|pa|da))?$", t):
        return [{"type": "ui_clear_text", "desc": "Erase Message"}], "erased"

    # --- send message (direct, no popup)
    send_words = ("send", "send it", "anuppu", "அனுப்பு", "அனுப்பி விடு", "send pannu", "anuppidu", "anupunga", "சென்ட்", "சென்ட் பண்ணு", "送信")
    if t in send_words or re.match(r"^(?:send|anuppu|அனுப்பு|அனுப்பி\s*விடு|சென்ட்)(?:\s+(?:it|pannu|panu|vidu|seiyu|pa|da))?$", t):
        return [{"type": "ui_smart_click", "action": "send", "desc": "Send Message"}], "sent"

    # --- Universal Smart Buttons: Like, Share, Subscribe, Comment, Cart, Buy Now, Close
    if any(w in t for w in ("like pannu", "like பண்ணு", "like this video", "like this reel", "like video", "like reel", "like post", "thumbs up", "விருப்பம்", "பிடிச்சிருக்கு", "いいね")) or t in ("like", "லைக்"):
        return [{"type": "ui_smart_click", "action": "like", "desc": "Like"}], "like"

    if any(w in t for w in ("share pannu", "ஷேர் பண்ணு", "share this", "share video", "share reel", "share post", "பகிர்", "பகிர்க")) or t in ("share", "ஷேர்"):
        return [{"type": "ui_smart_click", "action": "share", "desc": "Share"}], "share"

    if any(w in t for w in ("subscribe pannu", "சப்ஸ்கிரைப் பண்ணு", "follow pannu", "ஃபாலோ பண்ணு", "பின்தொடர்", "சேர்")) or t in ("subscribe", "follow", "சப்ஸ்கிரைப்", "ஃபாலோ"):
        return [{"type": "ui_smart_click", "action": "subscribe", "desc": "Subscribe/Follow"}], "subscribe"

    if any(w in t for w in ("comment pannu", "கமெண்ட் பண்ணு", "comment podu", "கருத்து போடு")) or t in ("comment", "கமெண்ட்", "கருத்து"):
        return [{"type": "ui_smart_click", "action": "comment", "desc": "Comment"}], "comment"

    if any(w in t for w in ("add to cart", "cart la podu", "கார்ட்ல போடு", "கூடையில் சேர்", "cart podu")) or t in ("cart", "கார்ட்"):
        return [{"type": "ui_smart_click", "action": "cart", "desc": "Add to Cart"}], "cart"

    if any(w in t for w in ("buy now", "இப்போதே வாங்கு", "buy pannu")):
        return [{"type": "ui_smart_click", "action": "buy_now", "desc": "Buy Now"}], "ok"

    if any(w in t for w in ("close pannu", "மூடு", "close it", "close window", "வெளியேறு")) or t in ("close", "மூடு"):
        return [{"type": "ui_smart_click", "action": "close", "desc": "Close"}], "ok"

    # --- Type text & Auto-Send
    m_type_send = re.match(r"^(?:type|write|தட்டச்சு செய்|டைப் பண்ணு)\s+(?P<x>.+?)\s+(?:and\s+send|send\s+pannu|அனுப்பு|சென்ட்\s+பண்ணு)$", t)
    if m_type_send:
        txt = m_type_send.group("x").strip()
        return [{"type": "ui_type_composer", "text": txt, "send": True, "desc": f"Type & Send '{txt}'"}], "type:" + txt

    # --- Type text into Composer (without pressing send button)
    m = re.match(r"^(?:type|write|enter|தட்டச்சு செய்|டைப் பண்ணு|டைப் செய்)\s+(?P<x>.+)$", t) or \
        re.match(r"^(?P<x>.+?)\s*(?:nu|ன்னு|னு)?\s*(?:type|write|தட்டச்சு)(?=\s|$).*$", t)
    if m:
        txt = m.group("x").strip()
        txt = re.sub(r"^(?:message|msg|செய்தி)\s+", "", txt).strip()
        return [{"type": "ui_type_composer", "text": txt, "send": False, "desc": f"Type '{txt}'"}], "type:" + txt

    # --- Open specific item/contact/card by text query
    m_item = re.match(r"^(?:open|select|click)\s+(?P<q>.+?)\s+(?:chat|contact|card|item|song|video|சாட்)$", t) or \
             re.match(r"^(?P<q>.+?)\s*(?:chat|contact|சாட்)\s*(?:open|thira|திற|போடு)$", t)
    if m_item:
        q_item = m_item.group("q").strip()
        if q_item and q_item not in ("the", "first", "second", "last", "any"):
            return [{"type": "ui_open_item", "query": q_item, "desc": f"Open '{q_item}'"}], "ok"

    # --- App Menu / 3-Dots / Overflow / Drawer
    if any(w in t for w in ("open menu", "menu open", "three dots", "3 dots", "overflow menu", "more options", "மெனு திற", "மெனுவை திற", "மெனு போடு", "drawer open")) or t in ("menu", "மெனு"):
        return [{"type": "ui_menu", "desc": "Open Menu"}], "menu"

    # --- Dialogs, Popups & Permissions (Allow, Deny, Confirm, Cancel, Dismiss)
    if any(w in t for w in ("allow permission", "allow it", "click allow", "அனுமதி கொடு", "அனுமதிக்கு")) or t in ("allow", "அனுமதி"):
        return [{"type": "ui_dialog", "action": "allow", "desc": "Allow"}], "dialog_allow"
    if any(w in t for w in ("deny permission", "don't allow", "dont allow", "click deny", "மறுக்கவும்", "அனுமதிக்காதே")) or t in ("deny", "மறு"):
        return [{"type": "ui_dialog", "action": "deny", "desc": "Deny"}], "dialog_deny"
    if any(w in t for w in ("confirm dialog", "confirm pannu", "உறுதிப்படுத்து")) or t in ("confirm", "உறுதி செய்"):
        return [{"type": "ui_dialog", "action": "confirm", "desc": "Confirm"}], "ok"
    if any(w in t for w in ("dismiss popup", "dismiss dialog", "cancel popup", "close popup", "விலக்கு")):
        return [{"type": "ui_dialog", "action": "cancel", "desc": "Dismiss Popup"}], "ok"

    # --- In-App Status, Updates & UI Item Clicking
    # e.g. "open my status", "open status", "my status ah open pannu", "status open pannu", "open updates", "open chats"
    m_status = re.match(r"^(?:open|view|show|see|பாரு|பார்|திற|போடு)\s+(?P<st>(?:my\s+)?status|updates?|stories|story|channels?|chats?|calls?|reels?|profile|notifications?|ஸ்டேட்டஸ்|மை\s*ஸ்டேட்டஸ்)$", t) or \
               re.match(r"^(?P<st>(?:my\s+)?status|updates?|stories|story|channels?|chats?|calls?|reels?|profile|notifications?|ஸ்டேட்டஸ்|மை\s*ஸ்டேட்டஸ்)\s*" + PART + r"?\s*(?:open|view|show|பாரு|பார்|திற|போடு)$", t)
    if m_status:
        st_query = m_status.group("st").strip()
        cap_query = "My status" if ("my status" in st_query.lower() or "மை" in st_query) else st_query.title()
        return [{"type": "ui_smart_click", "query": cap_query, "desc": f"Click '{cap_query}'"}], "ok"

    # --- App Navigation Tabs (Status, Updates, Profile, Home, Search, Reels, Orders, Cart, Library, Notifications, Chats, Calls)
    m_tab = re.match(r"^(?:open|go\s+to|switch\s+to|select)\s+(?P<tab>home|status|updates?|channels?|community|chats?|calls?|profile|search|explore|reels|shorts|notifications?|activity|orders?|cart|library|settings?)\s+tab$", t) or \
            re.match(r"^(?P<tab>home|status|updates?|channels?|community|chats?|calls?|profile|search|explore|reels|shorts|notifications?|activity|orders?|cart|library|settings?)\s+tab(?:\s+(?:open|thira|select|போடு))?$", t) or \
            re.match(r"^(?:go\s+to|open)\s+(?P<tab>profile|status|updates?|my\s+orders?|my\s+cart|my\s+library)\s+(?:page|screen)$", t) or \
            re.match(r"^(?P<tab>profile|status|updates?|orders?|cart|library|சாட்|ஸ்டேட்டஸ்)\s*(?:page|screen)\s*(?:ku|kku|க்கு|ல|இல்)?\s*(?:po|போ|open|thira|திற)$", t) or \
            re.match(r"^(?P<tab>status|updates?|சாட்|ஸ்டேட்டஸ்)\s*(?:ku|kku|க்கு)\s*(?:po|போ)$", t)
    if m_tab:
        tb = m_tab.group("tab").strip()
        return [{"type": "ui_click_tab", "tab": tb, "desc": f"Tab: {tb}"}], "tab:" + tb

    # --- Universal Switch & CheckBox Toggles inside any app
    m_toggle = re.match(r"^(?:turn\s+on|enable|activate|ஆன் பண்ணு)\s+(?P<feat>.+?)(?:\s+(?:switch|toggle|setting))?$", t) or \
               re.match(r"^(?P<feat>.+?)\s*(?:ah|a|ஐ)?\s*(?:on|enable|ஆன்)(?:\s+(?:switch|toggle|setting))?$", t)
    if m_toggle:
        feat = m_toggle.group("feat").strip()
        feat = re.sub(r"^(?:the|my)\s+", "", feat)
        if feat and not any(w in feat for w in ("torch", "flashlight", "wifi", "bluetooth", "hotspot", "data", "screen", "app")):
            return [{"type": "ui_toggle", "target": feat, "on": True, "desc": f"Turn on {feat}"}], "toggle_on:" + feat

    m_toggle_off = re.match(r"^(?:turn\s+off|disable|deactivate|ஆஃப் பண்ணு)\s+(?P<feat>.+?)(?:\s+(?:switch|toggle|setting))?$", t) or \
                   re.match(r"^(?P<feat>.+?)\s*(?:ah|a|ஐ)?\s*(?:off|disable|ஆஃப்)(?:\s+(?:switch|toggle|setting))?$", t)
    if m_toggle_off:
        feat = m_toggle_off.group("feat").strip()
        feat = re.sub(r"^(?:the|my)\s+", "", feat)
        if feat and not any(w in feat for w in ("torch", "flashlight", "wifi", "bluetooth", "hotspot", "data", "screen", "app")):
            return [{"type": "ui_toggle", "target": feat, "on": False, "desc": f"Turn off {feat}"}], "toggle_off:" + feat

    # --- Universal App Feature Execution (Checkout, Apply Coupon, Filter, Sort, Track Order, etc.)
    feat_triggers = (
        "apply coupon", "coupon apply", "apply promo", "கூப்பன் பயன்படுத்து",
        "checkout", "proceed to checkout", "place order", "ஆர்டர் செய்",
        "track order", "order track", "ஆர்டரை டிராக் பண்ணு",
        "filter", "apply filter", "வடிகட்டு",
        "sort by price", "sort", "வரிசைப்படுத்து",
        "download video", "download", "பதிவிறக்கு",
        "save post", "save video", "சேமி",
        "add address", "முகவரி சேர்",
        "view details", "details பாரு"
    )
    for ft in feat_triggers:
        if ft in t:
            return [{"type": "ui_feature", "feature": ft, "desc": f"Feature: {ft}"}], "feature:" + ft

    m_feat = re.match(r"^(?:execute|run|open\s+feature|use\s+feature)\s+(?P<f>.+)$", t) or \
             re.match(r"^(?P<f>.+?)\s*(?:feature|option)\s*(?:ah|a|ஐ)?\s*(?:open|run|use|போடு|திற)$", t)
    if m_feat:
        f_name = m_feat.group("f").strip()
        if f_name:
            return [{"type": "ui_feature", "feature": f_name, "desc": f"Feature: {f_name}"}], "feature:" + f_name

    m = re.match(r"^(?:click|tap|press|select)\s+(?:on\s+)?(?P<x>.+)$", t) or \
        re.match(r"^(?P<x>.+?)\s*" + PART + r"?\s*(?:click|tap|press|thotta|thodu|தொடு|கிளிக்|タップ|押).*$", t)
    if m:
        return [{"type": "ui_smart_click", "query": m.group("x").strip(), "desc": f"Click '{m.group('x').strip()}'"}], "ok"

    # --- open app (last, most generic) or open on-screen element
    m = re.match(r"^(?:open|launch|start|run)\s+(?P<a>.+)$", t) or \
        re.match(r"^(?P<a>.+?)\s*" + PART + r"?\s*(?:open|thira|thiranga|thirakka|launch|start|திற|திறங்க|திறக்க|を開|開い|開け).*$", t)
    if m:
        target_raw = m.group("a").strip()
        target_lower = target_raw.lower()

        # If it is clearly an in-app UI element or status, click it on screen
        if target_lower in UI_ELEMENT_WORDS or any(w in target_lower for w in ("status", "ஸ்டேட்டஸ்", "tab", "profile", "reels", "chat")):
            cap_q = "My status" if ("my status" in target_lower or "மை" in target_lower) else target_raw
            return [{"type": "ui_smart_click", "query": cap_q, "desc": f"Click '{cap_q}'"}], "ok"

        # If inside an app already and the target is not a known app in APPS
        if last_app and not any(k in target_lower for k in APPS):
            return [{"type": "ui_smart_click", "query": target_raw, "desc": f"Click '{target_raw}'"}], "ok"

        name = _app_name(target_raw)
        if name:
            return [{"type": "open_app", "name": name, "desc": f"Open {name}"}], "app:" + name
        else:
            return [{"type": "ui_smart_click", "query": target_raw, "desc": f"Click '{target_raw}'"}], "ok"
    return None


SAY = {  # spoken confirmation per language
    "ta-IN": {"app": "%s திறக்கிறேன்.", "search": "%s தேடுகிறேன்.", "call": "%s-க்கு இப்போது அழைக்கிறேன்.",
              "nth": "%d-வது ஐ திறக்கிறேன்.", "ok": "சரி.", "yt": "YouTube-ல %s திறக்கிறேன்.",
              "google": "Google-ல %s தேடுகிறேன்.", "google_page": "Google-ஐ திறக்கிறேன்.",
              "last_file": "கடைசியாக வந்த கோப்பைத் திறக்கிறேன்.",
              "torch_on": "டார்ச் ஆன் செய்துள்ளேன்.", "torch_off": "டார்ச் ஆஃப் செய்துள்ளேன்.",
              "wifi_on": "வைஃபை ஆன் செய்கிறேன்.", "wifi_off": "வைஃபை ஆஃப் செய்கிறேன்.",
              "bt_on": "ப்ளூடூத் ஆன் செய்கிறேன்.", "bt_off": "ப்ளூடூத் ஆஃப் செய்கிறேன்.",
              "hotspot_on": "ஹாட்ஸ்பாட் திறக்கப்பட்டுள்ளது.", "hotspot_off": "ஹாட்ஸ்பாட் ஆஃப் செய்யப்பட்டுள்ளது.",
              "chat": "சாட் திறக்கிறேன்.",
              "screenshot": "ஸ்கிரீன்ஷாட் எடுக்கப்பட்டது.", "media": "இசை மாற்றப்பட்டது.", "clipboard": "நகலெடுக்கப்பட்டது.",
              "stop": "சரி, நிறுத்துகிறேன்.",
              "type": "'%s' என தட்டச்சு செய்துள்ளேன். அனுப்ப 'send' அல்லது அழிக்க 'cancel' என சொல்லுங்கள்.",
              "erased": "செய்தி அழிக்கப்பட்டது.",
              "sent": "செய்தி அனுப்பப்பட்டது.",
              "reels_next": "அடுத்த ரீல்ஸ்.", "reels_prev": "முந்தைய ரீல்ஸ்.",
              "like": "விருப்பம் (Liked).", "share": "பகிர்வு திறக்கப்பட்டது.",
              "subscribe": "பின்தொடரப்பட்டது (Subscribed).", "comment": "கமெண்ட் பகுதி திறக்கப்பட்டது.",
              "cart": "கூடையில் சேர்க்கப்பட்டது.", "read_screen": "திரையில் உள்ளவற்றை பார்க்கிறேன்.",
              "send_to": "%s-க்கு செய்தி அனுப்பப்படுகிறது.", "music": "%s பாடல் இயக்கப்படுகிறது.",
              "swipe_left": "இடதுபுறம் ஸ்வைப் செய்கிறேன்.", "swipe_right": "வலதுபுறம் ஸ்வைப் செய்கிறேன்.",
              "swipe_up": "மேலே ஸ்வைப் செய்கிறேன்.", "swipe_down": "கீழே ஸ்வைப் செய்கிறேன்.",
              "menu": "மெனு திறக்கப்படுகிறது.", "dialog_allow": "அனுமதிக்கப்பட்டது.", "dialog_deny": "நிராகரிக்கப்பட்டது.",
              "tab": "%s பகுதிக்கு செல்கிறேன்.", "toggle_on": "%s ஆன் செய்யப்பட்டது.", "toggle_off": "%s ஆஃப் செய்யப்பட்டது.",
              "feature": "%s செய்யப்படுகிறது.", "read_notifications": "வந்த செய்திகளைப் படிக்கிறேன்.",
              "read_sms": "சமீபத்திய எஸ்எம்எஸ் படிக்கிறேன்.", "take_photo": "படம் எடுக்கிறேன்.",
              "data_on": "மொபைல் டேட்டா ஆன் செய்கிறேன்.", "data_off": "மொபைல் டேட்டா ஆஃப் செய்கிறேன்.",
              "skip_ad": "விளம்பரம் தவிர்க்கப்பட்டது.", "cruise_on": "தானாக ரீல்ஸ் மாறும் முறை ஆன்.",
              "cruise_off": "தானாக ரீல்ஸ் மாறும் முறை ஆஃப்.", "fill_form": "படிவம் தானாக நிரப்பப்படுகிறது.",
              "fill_otp": "ஓடிபி கண்டறியப்பட்டு நிரப்பப்படுகிறது.", "battery": "பேட்டரி அளவை சரிபார்க்கிறேன்.",
              "uninstall": "%s-ஐ நீக்க அன்இன்ஸ்டால் திரையை திறக்கிறேன்.", "grid_tap": "%s-ல் கிளிக் செய்கிறேன்."},
    "en-US": {"app": "Opening %s.", "search": "Searching %s.", "call": "Calling %s right now.",
              "nth": "Opening number %d.", "ok": "Done.", "yt": "Playing %s on YouTube.",
              "google": "Searching %s on Google.", "google_page": "Opening Google.",
              "last_file": "Opening the last received file.",
              "torch_on": "Flashlight turned on.", "torch_off": "Flashlight turned off.",
              "wifi_on": "Turning on Wi-Fi.", "wifi_off": "Turning off Wi-Fi.",
              "bt_on": "Turning on Bluetooth.", "bt_off": "Turning off Bluetooth.",
              "hotspot_on": "Hotspot opened.", "hotspot_off": "Hotspot turned off.",
              "chat": "Opening chat.",
              "screenshot": "Screenshot captured.", "media": "Media updated.", "clipboard": "Copied to clipboard.",
              "stop": "Stopping assistant.",
              "type": "Typed '%s'. Say 'send' to send, or 'cancel' to erase.",
              "erased": "Message erased.",
              "sent": "Message sent.",
              "reels_next": "Next reel.", "reels_prev": "Previous reel.",
              "like": "Liked.", "share": "Share opened.",
              "subscribe": "Subscribed / Followed.", "comment": "Comments opened.",
              "cart": "Added to cart.", "read_screen": "Reading screen.",
              "send_to": "Sending message to %s.", "music": "Playing %s.",
              "swipe_left": "Swiping left.", "swipe_right": "Swiping right.",
              "swipe_up": "Swiping up.", "swipe_down": "Swiping down.",
              "menu": "Menu opened.", "dialog_allow": "Allowed.", "dialog_deny": "Denied.",
              "tab": "Opening %s tab.", "toggle_on": "Turned on %s.", "toggle_off": "Turned off %s.",
              "feature": "Executing %s.", "read_notifications": "Reading notifications.",
              "read_sms": "Reading recent SMS.", "take_photo": "Taking photo.",
              "data_on": "Turning on mobile data.", "data_off": "Turning off mobile data.",
              "skip_ad": "Skipping advertisement.", "cruise_on": "Reels auto-cruise enabled.",
              "cruise_off": "Reels auto-cruise disabled.", "fill_form": "Auto-filling form details.",
              "fill_otp": "Reading and entering OTP.", "battery": "Checking battery level.",
              "uninstall": "Opening uninstaller for %s.", "grid_tap": "Tapping %s."},
    "ja-JP": {"app": "%sを開きます。", "search": "%sを検索します。", "call": "%sに電話をかけています。",
              "nth": "%d番目を開きます。", "ok": "はい。", "yt": "YouTubeで%sを再生します。",
              "google": "Googleで%sを検索します。", "google_page": "Googleを開きます。",
              "last_file": "最後に受信したファイルを開きます。",
              "torch_on": "ライトをつけました。", "torch_off": "ライトを消しました。",
              "wifi_on": "Wi-Fiをオンにします。", "wifi_off": "Wi-Fiをオフにします。",
              "bt_on": "Bluetoothをオンにします。", "bt_off": "Bluetoothをオフにします。",
              "hotspot_on": "テザリングを開きました。", "hotspot_off": "テザリングをオフにしました。",
              "chat": "チャットを開きます。",
              "screenshot": "スクリーンショットを撮影しました。", "media": "メディアを更新しました。", "clipboard": "コピーしました。",
              "stop": "わかりました。停止します。",
              "type": "'%s'と入力しました。送信するには'send'、消去するには'cancel'と言ってください。",
              "erased": "メッセージを消去しました。",
              "sent": "メッセージを送信しました.",
              "reels_next": "次のリールです。", "reels_prev": "前のリールです。",
              "like": "いいねしました。", "share": "共有を開きました。",
              "subscribe": "登録しました。", "comment": "コメントを開きました。",
              "cart": "カートに追加しました。", "read_screen": "画面を確認しています。",
              "send_to": "%sにメッセージを送信しています。", "music": "%sを再生します。",
              "swipe_left": "左にスワイプします。", "swipe_right": "右にスワイプします。",
              "swipe_up": "上にスワイプします。", "swipe_down": "下にスワイプします。",
              "menu": "メニューを開きました。", "dialog_allow": "許可しました。", "dialog_deny": "拒否しました。",
              "tab": "%sタブを開きます。", "toggle_on": "%sをオンにしました。", "toggle_off": "%sをオフにしました。",
              "feature": "%sを実行します。", "read_notifications": "通知を読み上げます。",
              "read_sms": "SMSを読み上げます。", "take_photo": "写真を撮影します。",
              "data_on": "モバイルデータをオンにします。", "data_off": "モバイルデータをオフにします。",
              "skip_ad": "広告をスキップしました。", "cruise_on": "リール自動スクロールを開始しました。",
              "cruise_off": "リール自動スクロールを停止しました。", "fill_form": "フォームを自動入力しています。",
              "fill_otp": "OTPを入力しています。", "battery": "バッテリー残量を確認します。",
              "uninstall": "%sのアンインストールを開きます。", "grid_tap": "%sをタップしました。"},
    "hi-IN": {"app": "%s खोल रहा हूँ।", "search": "%s खोज रहा हूँ।", "call": "%s को कॉल कर रहा हूँ।",
              "nth": "%d नंबर खोल रहा हूँ।", "ok": "ठीक है।", "yt": "YouTube पर %s चला रहा हूँ।",
              "google": "Google पर %s खोज रहा हूँ।", "google_page": "Google खोल रहा हूँ।",
              "last_file": "अंतिम प्राप्त फ़ाइल खोल रहा हूँ।",
              "torch_on": "टॉर्च चालू कर दी है।", "torch_off": "टॉर्च बंद कर दी है।",
              "wifi_on": "वाई-फ़ाई चालू कर रहा हूँ।", "wifi_off": "वाई-फ़ाई बंद कर रहा हूँ।",
              "bt_on": "ब्लूटूथ चालू कर रहा हूँ।", "bt_off": "ब्लूटूथ बंद कर रहा हूँ।",
              "hotspot_on": "हॉटस्पॉट चालू किया गया।", "hotspot_off": "हॉटस्पॉट बंद किया गया।",
              "chat": "चैट खोल रहा हूँ।", "screenshot": "स्क्रीनशॉट लिया गया।", "media": "मीडिया बदला गया।", "clipboard": "कॉपी किया गया।",
              "stop": "सहायक को रोक रहा हूँ।", "type": "'%s' टाइप किया है।", "erased": "संदेश मिटाया गया।", "sent": "संदेश भेजा गया।",
              "reels_next": "अगली रील।", "reels_prev": "पिछली रील।", "like": "पसंद किया गया।", "share": "शेयर खोला गया।",
              "subscribe": "सब्सक्राइब किया गया।", "comment": "टिप्पणियाँ खोली गईं।", "cart": "कार्ट में जोड़ा गया।", "read_screen": "स्क्रीन पढ़ रहा हूँ।",
              "send_to": "%s को संदेश भेज रहा हूँ।", "music": "%s बजा रहा हूँ।", "swipe_left": "बाएं स्वाइप कर रहा हूँ।",
              "swipe_right": "दाएं स्वाइप कर रहा हूँ।", "swipe_up": "ऊपर स्वाइप कर रहा हूँ।", "swipe_down": "नीचे स्वाइप कर रहा हूँ।",
              "menu": "मेनू खोला गया।", "dialog_allow": "अनुमति दी गई।", "dialog_deny": "अस्वीकार किया गया।", "tab": "%s टैब खोल रहा हूँ।",
              "toggle_on": "%s चालू किया गया।", "toggle_off": "%s बंद किया गया।", "feature": "%s निष्पादित कर रहा हूँ।",
              "read_notifications": "सूचनाएं पढ़ रहा हूँ।", "read_sms": "एसएमएस पढ़ रहा हूँ।", "take_photo": "फोटो खींच रहा हूँ।",
              "data_on": "मोबाइल डेटा चालू कर रहा हूँ।", "data_off": "मोबाइल डेटा बंद कर रहा हूँ।", "skip_ad": "विज्ञापन छोड़ रहा हूँ।",
              "cruise_on": "रील ऑटो-क्रूज़ चालू।", "cruise_off": "रील ऑटो-क्रूज़ बंद।", "fill_form": "विवरण भर रहा हूँ।",
              "fill_otp": "ओटीपी भर रहा हूँ।", "battery": "बैटरी की स्थिति देख रहा हूँ।", "uninstall": "%s को हटाने के लिए अनइंस्टालर खोल रहा हूँ।",
              "grid_tap": "%s पर टैप किया।"},
    "ko-KR": {"app": "%s을(를) 엽니다.", "search": "%s을(를) 검색합니다.", "call": "%s에게 전화합니다.",
              "nth": "%d번 항목을 엽니다.", "ok": "완료했습니다.", "yt": "YouTube에서 %s을(를) 재생합니다.",
              "google": "Google에서 %s을(를) 검색합니다.", "google_page": "Google을 엽니다.",
              "last_file": "최근 수신된 파일을 엽니다.",
              "torch_on": "손전등을 켰습니다.", "torch_off": "손전등을 껐습니다.",
              "wifi_on": "Wi-Fi를 켭니다.", "wifi_off": "Wi-Fi를 끕니다.",
              "bt_on": "블루투스를 켭니다.", "bt_off": "블루투스를 끕니다.",
              "hotspot_on": "핫스팟을 켰습니다.", "hotspot_off": "핫스팟을 껐습니다.",
              "chat": "채팅을 엽니다.", "screenshot": "화면을 캡처했습니다.", "media": "미디어를 변경했습니다.", "clipboard": "클립보드에 복사했습니다.",
              "stop": "비서를 종료합니다.", "type": "'%s'을(를) 입력했습니다.", "erased": "메시지를 지웠습니다.", "sent": "메시지를 전송했습니다.",
              "reels_next": "다음 릴스입니다.", "reels_prev": "이전 릴스입니다.", "like": "좋아요를 눌렀습니다.", "share": "공유를 열었습니다.",
              "subscribe": "구독했습니다.", "comment": "댓글창을 열었습니다.", "cart": "장바구니에 담았습니다.", "read_screen": "화면을 읽고 있습니다.",
              "send_to": "%s에게 메시지를 보냅니다.", "music": "%s을(를) 재생합니다.", "swipe_left": "왼쪽으로 넘깁니다.",
              "swipe_right": "오른쪽으로 넘깁니다.", "swipe_up": "위로 올립니다.", "swipe_down": "아래로 내립니다.",
              "menu": "메뉴를 열었습니다.", "dialog_allow": "허용했습니다.", "dialog_deny": "거부했습니다.", "tab": "%s 탭을 엽니다.",
              "toggle_on": "%s을(를) 켰습니다.", "toggle_off": "%s을(를) 껐습니다.", "feature": "%s을(를) 실행합니다.",
              "read_notifications": "알림을 읽습니다.", "read_sms": "문자 메시지를 읽습니다.", "take_photo": "사진을 촬영합니다.",
              "data_on": "모바일 데이터를 켭니다.", "data_off": "모바일 데이터를 끕니다.", "skip_ad": "광고를 건너뜁니다.",
              "cruise_on": "릴스 자동 재생 시작.", "cruise_off": "릴스 자동 재생 중지.", "fill_form": "양식을 자동 입력합니다.",
              "fill_otp": "OTP를 입력합니다.", "battery": "배터리 잔량을 확인합니다.", "uninstall": "%s 삭제 화면을 엽니다.",
              "grid_tap": "%s을(를) 탭했습니다."},
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
        actions.extend(acts)
        s = SAY.get(lang, SAY["en-US"])
        if kind.startswith("app:"): said.append(s["app"] % kind[4:])
        elif kind == "call": said.append(s["call"] % acts[0]["target"])
        elif kind == "nth": said.append(s["nth"] % acts[-1]["n"])
        elif kind.startswith("yt:"): said.append(s.get("yt", "Opening %s on YouTube.") % kind[3:])
        elif kind.startswith("google:"): said.append(s.get("google", "Searching %s on Google.") % kind[7:])
        elif kind == "google_page": said.append(s.get("google_page", "Opening Google."))
        elif kind == "last_file": said.append(s.get("last_file", "Opening the last received file."))
        elif kind.startswith("search:") and acts[0]["type"] == "youtube": said.append(s["yt"] % acts[0]["query"])
        elif kind.startswith("search:"): said.append(s["search"] % acts[-1]["query"])
        elif kind.startswith("type:"): said.append(s.get("type", "Typed '%s'.") % kind[5:])
        elif kind == "erased": said.append(s.get("erased", "Message erased."))
        elif kind == "sent": said.append(s.get("sent", "Message sent."))
        elif kind == "torch_on": said.append(s["torch_on"])
        elif kind == "torch_off": said.append(s["torch_off"])
        elif kind == "wifi_on": said.append(s["wifi_on"])
        elif kind == "wifi_off": said.append(s["wifi_off"])
        elif kind == "bt_on": said.append(s["bt_on"])
        elif kind == "bt_off": said.append(s["bt_off"])
        elif kind == "hotspot_on": said.append(s.get("hotspot_on", "Hotspot opened."))
        elif kind == "hotspot_off": said.append(s.get("hotspot_off", "Hotspot turned off."))
        elif kind == "chat": said.append(s.get("chat", "Opening chat."))
        elif kind == "screenshot": said.append(s["screenshot"])
        elif kind == "media": said.append(s["media"])
        elif kind == "clipboard": said.append(s["clipboard"])
        elif kind == "reels_next": said.append(s.get("reels_next", "Next reel."))
        elif kind == "reels_prev": said.append(s.get("reels_prev", "Previous reel."))
        elif kind == "like": said.append(s.get("like", "Liked."))
        elif kind == "share": said.append(s.get("share", "Share opened."))
        elif kind == "subscribe": said.append(s.get("subscribe", "Subscribed."))
        elif kind == "comment": said.append(s.get("comment", "Comments opened."))
        elif kind == "cart": said.append(s.get("cart", "Added to cart."))
        elif kind == "read_screen": said.append(s.get("read_screen", "Reading screen."))
        elif kind.startswith("send_to:"): said.append(s.get("send_to", "Sending message to %s.") % kind[8:])
        elif kind.startswith("music:"): said.append(s.get("music", "Playing %s.") % kind[6:])
        elif kind == "stop": said.append(s.get("stop", "OK"))
        elif kind == "swipe_left": said.append(s.get("swipe_left", "Swiping left."))
        elif kind == "swipe_right": said.append(s.get("swipe_right", "Swiping right."))
        elif kind == "swipe_up": said.append(s.get("swipe_up", "Swiping up."))
        elif kind == "swipe_down": said.append(s.get("swipe_down", "Swiping down."))
        elif kind == "menu": said.append(s.get("menu", "Menu opened."))
        elif kind == "dialog_allow": said.append(s.get("dialog_allow", "Allowed."))
        elif kind == "dialog_deny": said.append(s.get("dialog_deny", "Denied."))
        elif kind.startswith("tab:"): said.append(s.get("tab", "Opening %s tab.") % kind[4:])
        elif kind.startswith("toggle_on:"): said.append(s.get("toggle_on", "Turned on %s.") % kind[10:])
        elif kind.startswith("toggle_off:"): said.append(s.get("toggle_off", "Turned off %s.") % kind[11:])
        elif kind.startswith("feature:"): said.append(s.get("feature", "Executing %s.") % kind[8:])
        elif kind == "read_notifications": said.append(s.get("read_notifications", "Reading notifications."))
        elif kind == "read_sms": said.append(s.get("read_sms", "Reading SMS."))
        elif kind == "take_photo": said.append(s.get("take_photo", "Taking photo."))
        elif kind == "data_on": said.append(s.get("data_on", "Turning on data."))
        elif kind == "data_off": said.append(s.get("data_off", "Turning off data."))
        elif kind == "skip_ad": said.append(s.get("skip_ad", "Skipping ad."))
        elif kind == "cruise_on": said.append(s.get("cruise_on", "Reels auto-cruise enabled."))
        elif kind == "cruise_off": said.append(s.get("cruise_off", "Reels auto-cruise disabled."))
        elif kind == "fill_form": said.append(s.get("fill_form", "Auto-filling form."))
        elif kind == "fill_otp": said.append(s.get("fill_otp", "Reading and entering OTP."))
        elif kind == "battery": said.append(s.get("battery", "Checking battery level."))
        elif kind.startswith("uninstall:"): said.append(s.get("uninstall", "Opening uninstaller for %s.") % kind[10:])
        elif kind.startswith("grid_tap:"): said.append(s.get("grid_tap", "Tapping %s.") % kind[9:])
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
    _assistant["stop_word"] = (cfg.get("stop_word") or "stop").strip().lower()
    try:
        cmd = local_command(user_text, lang)
        if cmd:
            cmd["ok"] = True
            return json.dumps(cmd)
        quick = _quick_local_answer(user_text, cfg)
        if quick:
            return json.dumps({"ok": True, "speech": quick, "actions": []})
        return json.dumps(_chat_api(cfg, user_text))
    except Exception as e:
        return json.dumps({"ok": True, "speech": _friendly(e, lang), "actions": [], "error": str(e)})
