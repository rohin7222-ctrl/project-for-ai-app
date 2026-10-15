package com.nextgen.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Config(
    val lang: String = "ta-IN",
    val name: String = "",
    val type: String = "api",          // "api" or "local"
    val provider: String = "gemini",
    val key: String = "",
    val model: String = "",
    val url: String = "",
    val female: Boolean = true,
    val dark: Boolean = false,
    val commandingWord: String = "Jarvis",
    val commandingStopWord: String = "Stop",
    val autoWakeEnabled: Boolean = true,
    val voiceMatchEnabled: Boolean = false,
    val voicePitchMean: Float = 0f,
    val voicePitchStd: Float = 0f,
    val voiceEnrolled: Boolean = false,
    val darkCustom: Boolean = false,
    val raiseToAnswer: Boolean = false
)

/** All settings (including the API key) live in AES-256 encrypted preferences backed by the Android Keystore. */
class Store(ctx: Context) {
    private val sp = EncryptedSharedPreferences.create(
        ctx, "agent_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(systemDark: Boolean = false) = Config(
        sp.getString("lang", "ta-IN")!!, sp.getString("name", "")!!, sp.getString("type", "api")!!,
        sp.getString("provider", "gemini")!!, sp.getString("key", "")!!, sp.getString("model", "")!!,
        sp.getString("url", "")!!, sp.getBoolean("female", true),
        if (sp.getBoolean("dark_custom", false)) sp.getBoolean("dark", false) else systemDark,
        sp.getString("commanding_word", "Jarvis")!!,
        sp.getString("commanding_stop_word", "Stop")!!,
        sp.getBoolean("auto_wake", true),
        sp.getBoolean("voice_match", false), sp.getFloat("pitch_mean", 0f),
        sp.getFloat("pitch_std", 0f), sp.getBoolean("voice_enrolled", false),
        sp.getBoolean("dark_custom", false),
        sp.getBoolean("raise_to_answer", false)
    )

    fun save(c: Config) {
        sp.edit().putString("lang", c.lang).putString("name", c.name).putString("type", c.type)
            .putString("provider", c.provider).putString("key", c.key).putString("model", c.model)
            .putString("url", c.url).putBoolean("female", c.female).putBoolean("dark", c.dark)
            .putBoolean("dark_custom", c.darkCustom)
            .putString("commanding_word", c.commandingWord)
            .putString("commanding_stop_word", c.commandingStopWord)
            .putBoolean("auto_wake", c.autoWakeEnabled)
            .putBoolean("voice_match", c.voiceMatchEnabled).putFloat("pitch_mean", c.voicePitchMean)
            .putFloat("pitch_std", c.voicePitchStd).putBoolean("voice_enrolled", c.voiceEnrolled)
            .putBoolean("raise_to_answer", c.raiseToAnswer).apply()
    }

    fun isReady() = sp.getBoolean("ready", false)
    fun setReady(v: Boolean) = sp.edit().putBoolean("ready", v).apply()

    /** Back to the first page: forget name + brain, keep language / theme / voice. */
    fun logout() {
        val c = load()
        save(c.copy(name = "", key = "", model = "", url = ""))
        setReady(false)
    }
}