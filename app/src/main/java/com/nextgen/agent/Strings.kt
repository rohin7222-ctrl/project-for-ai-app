package com.nextgen.agent

/** UI text in Tamil / English / Japanese. */
object S {
    private val m: Map<String, List<String>> = mapOf(
        "continue" to listOf("தொடரவும்", "Continue", "次へ"),
        "name_title" to listOf("உங்கள் AI-க்கு பெயர் வையுங்கள்", "Name your AI", "AIに名前を付けよう"),
        "name_sub" to listOf("உங்களுக்குப் பிடித்த பெயரைக் கொடுங்கள்", "Pick a name you like", "好きな名前を入力してください"),
        "name_hint" to listOf("எ.கா: Jarvis", "e.g. Jarvis", "例: Jarvis"),
        "brain_title" to listOf("மூளையைத் தேர்வு செய்", "Choose the brain", "頭脳を選択"),
        "brain_sub" to listOf("எவ்வாறு சிந்திக்க வேண்டும்?", "How should it think?", "どのように考えますか？"),
        "cloud" to listOf("Cloud API", "Cloud API", "クラウドAPI"),
        "local" to listOf("Local LLM", "Local LLM", "ローカルLLM"),
        "provider" to listOf("வழங்குநர்", "Provider", "プロバイダー"),
        "api_key" to listOf("API Key-ஐ இங்கே ஒட்டவும்", "Paste your API key", "APIキーを貼り付け"),
        "fetch" to listOf("மாடல்களைப் பெறு", "Fetch models", "モデルを取得"),
        "models" to listOf("கிடைக்கும் மாடல்கள்", "Available models", "利用可能なモデル"),
        "no_models" to listOf("API Key கொடுத்தால் மாடல்கள் தோன்றும்", "Enter a key to see models", "キーを入力するとモデルが表示されます"),
        "ok" to listOf("சரி", "OK", "OK"),
        "local_url" to listOf("Local URL (எ.கா: http://192.168.1.5:11434)", "Local URL (e.g. http://192.168.1.5:11434)", "ローカルURL (例: http://192.168.1.5:11434)"),
        "model_name" to listOf("மாடல் பெயர் (எ.கா: llama3)", "Model name (e.g. llama3)", "モデル名 (例: llama3)"),
        "start" to listOf("தொடங்கு", "Start", "スタート"),
        "stop" to listOf("நிறுத்து", "Stop", "停止"),
        "listening" to listOf("கேட்கிறேன்…", "Listening…", "聞いています…"),
        "thinking" to listOf("யோசிக்கிறேன்…", "Thinking…", "考え中…"),
        "hello" to listOf("வணக்கம், நான் %s", "Hi, I'm %s", "こんにちは、%sです"),
        "voice" to listOf("குரலை மாற்று", "Change voice", "音声を変更"),
        "female" to listOf("பெண் குரல்", "Female voice", "女性の声"),
        "male" to listOf("ஆண் குரல்", "Male voice", "男性の声"),
        "app_lang" to listOf("செயலி மொழி", "App language", "アプリの言語"),
        "logout" to listOf("வெளியேறு", "Logout", "ログアウト"),
        "sys_control" to listOf("System control இயக்கு", "Enable system control", "システム制御を有効化"),
        "confirm_title" to listOf("உறுதி செய்யவும்", "Please confirm", "確認してください"),
        "allow" to listOf("அனுமதி", "Allow", "許可"),
        "deny" to listOf("மறு", "Deny", "拒否"),
        "type_here" to listOf("இங்கே தட்டச்சு செய்யவும்", "Type a message", "メッセージを入力"),
        "error" to listOf("மன்னிக்கவும், ஏதோ பிழை ஏற்பட்டது", "Sorry, something went wrong", "エラーが発生しました"),
        "need_a11y" to listOf("Accessibility-ல் System control-ஐ இயக்கவும்", "Please enable system control in Accessibility settings", "ユーザー補助でシステム制御を有効にしてください"),
        "c_call" to listOf("அழைக்க: %s", "Call %s", "%s に電話"),
        "c_sms" to listOf("SMS → %s: %s", "SMS → %s: %s", "SMS → %s: %s"),
        "c_end" to listOf("அழைப்பை முடி", "End the call", "通話を終了"),
        "unlock" to listOf("திற", "Unlock", "ロック解除")
    )

    fun t(lang: String, k: String): String {
        val i = when (lang) { "ta-IN" -> 0; "ja-JP" -> 2; else -> 1 }
        return m[k]?.get(i) ?: k
    }
}