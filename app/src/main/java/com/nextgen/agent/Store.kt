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
    val autoWakeEnabled: Boolean = true,
    val voiceMatchEnabled: Boolean = false,
    val voicePitchMean: Float = 0f,
    val voicePitchStd: Float = 0f,
    val voiceEnrolled: Boolean = false
)

/** All settings (including the API key) live in AES-256 encrypted preferences backed by the Android Keystore. */
class Store(ctx: Context) {
    private val sp = EncryptedSharedPreferences.create(
        ctx, "agent_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load() = Config(
        sp.getString("lang", "ta-IN")!!, sp.getString("name", "")!!, sp.getString("type", "api")!!,
        sp.getString("provider", "gemini")!!, sp.getString("key", "")!!, sp.getString("model", "")!!,
        sp.getString("url", "")!!, sp.getBoolean("female", true), sp.getBoolean("dark", false),
        sp.getString("commanding_word", "Jarvis")!!, sp.getBoolean("auto_wake", true),
        sp.getBoolean("voice_match", false), sp.getFloat("pitch_mean", 0f),
        sp.getFloat("pitch_std", 0f), sp.getBoolean("voice_enrolled", false)
    )

    fun save(c: Config) {
        sp.edit().putString("lang", c.lang).putString("name", c.name).putString("type", c.type)
            .putString("provider", c.provider).putString("key", c.key).putString("model", c.model)
            .putString("url", c.url).putBoolean("female", c.female).putBoolean("dark", c.dark)
            .putString("commanding_word", c.commandingWord).putBoolean("auto_wake", c.autoWakeEnabled)
            .putBoolean("voice_match", c.voiceMatchEnabled).putFloat("pitch_mean", c.voicePitchMean)
            .putFloat("pitch_std", c.voicePitchStd).putBoolean("voice_enrolled", c.voiceEnrolled).apply()
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