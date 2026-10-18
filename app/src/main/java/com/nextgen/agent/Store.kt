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
    val raiseToAnswer: Boolean = true,
    val mouseBallEnabled: Boolean = false,
    val mouseBallSize: Int = 52,
    val mouseSpeed: Float = 1.0f,
    val mousePadMode: String = "trackpad",   // Dedicated precision trackpad mode
    val mousePadScale: Float = 1.0f,         // 0.70f to 1.50f scale
    val mousePadAlpha: Float = 0.92f,        // 0.50f to 1.0f transparency
    val mousePointerSize: Float = 1.0f,      // 0.8f, 1.0f, 1.3f
    val mousePointerColor: String = "white", // "white", "cyan", "green", "black"
    val mouseHaptics: Boolean = true
)

/** All settings (including the API key) live in AES-256 encrypted preferences backed by the Android Keystore. */
class Store(val ctx: Context) {
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
        sp.getBoolean("raise_to_answer", true),
        sp.getBoolean("mouse_ball_enabled", false),
        sp.getInt("mouse_ball_size", 52),
        sp.getFloat("mouse_speed", 1.0f),
        sp.getString("mouse_pad_mode", "trackpad")!!,
        sp.getFloat("mouse_pad_scale", 1.0f),
        sp.getFloat("mouse_pad_alpha", 0.92f),
        sp.getFloat("mouse_pointer_size", 1.0f),
        sp.getString("mouse_pointer_color", "white")!!,
        sp.getBoolean("mouse_haptics", true)
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
            .putBoolean("raise_to_answer", c.raiseToAnswer)
            .putBoolean("mouse_ball_enabled", c.mouseBallEnabled)
            .putInt("mouse_ball_size", c.mouseBallSize)
            .putFloat("mouse_speed", c.mouseSpeed)
            .putString("mouse_pad_mode", c.mousePadMode)
            .putFloat("mouse_pad_scale", c.mousePadScale)
            .putFloat("mouse_pad_alpha", c.mousePadAlpha)
            .putFloat("mouse_pointer_size", c.mousePointerSize)
            .putString("mouse_pointer_color", c.mousePointerColor)
            .putBoolean("mouse_haptics", c.mouseHaptics).apply()
    }

    fun isReady() = sp.getBoolean("ready", false)
    fun setReady(v: Boolean) = sp.edit().putBoolean("ready", v).apply()

    fun setMouseBallEnabled(enabled: Boolean) {
        val c = load()
        save(c.copy(mouseBallEnabled = enabled))
    }

    /** Back to the first page: forget name + brain, keep language / theme / voice. */
    fun logout() {
        val c = load()
        save(c.copy(name = "", key = "", model = "", url = ""))
        setReady(false)
        try {
            EarToCallManager.getInstance(ctx).onLogout()
        } catch (ignored: Exception) {}
    }

    /** Emergency protocol: wipe all secure keys, tokens, and credentials. */
    fun emergencyWipeAndLogout() {
        try {
            sp.edit().clear().apply()
        } catch (ignored: Exception) {}
        try {
            EarToCallManager.getInstance(ctx).onLogout()
        } catch (ignored: Exception) {}
    }

    companion object {
        @JvmField
        @Volatile
        var latestOtp: String = ""

        @JvmField
        @Volatile
        var userName: String = ""

        @JvmField
        @Volatile
        var userPhone: String = ""

        @JvmField
        @Volatile
        var userEmail: String = ""

        @JvmField
        @Volatile
        var userAddress: String = ""

        @JvmField
        @Volatile
        var failedBiometricAttempts: Int = 0

        @JvmStatic
        fun resetBiometrics() {
            failedBiometricAttempts = 0
        }
    }
}