package com.nextgen.agent.shortcut

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nextgen.agent.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * Data class representing a Voice Shortcut.
 * @param id Unique identifier
 * @param word The target action / sentence to execute (e.g. "open whatsapp")
 * @param command The short trigger phrase spoken into the mic (e.g. "O W")
 */
data class VoiceShortcut(
    val id: String = UUID.randomUUID().toString(),
    val word: String,       // e.g. "open whatsapp"
    val command: String     // e.g. "O W"
)

/**
 * Manages custom voice shortcuts and the hardcoded emergency safety self-uninstall mechanism.
 */
class ShortcutManager(private val ctx: Context) {

    companion object {
        const val EMERGENCY_COMMAND = "code dark shadow"
        private const val PREFS_NAME = "agent_shortcuts_prefs"
        private const val KEY_SHORTCUTS = "shortcuts_list"

        fun normalize(s: String): String {
            return s.trim().lowercase(Locale.ROOT)
                .replace(".", "")
                .replace(",", "")
                .replace("-", " ")
                .replace("'", "")
                .replace(Regex("\\s+"), " ")
        }

        fun stripSpaces(s: String): String {
            return normalize(s).replace(" ", "")
        }
    }

    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Checks if the spoken text matches the emergency uninstall command "code dark shadow".
     */
    fun isEmergencyUninstall(spoken: String): Boolean {
        val norm = normalize(spoken)
        val stripped = stripSpaces(spoken)
        return norm == EMERGENCY_COMMAND ||
               norm.contains(EMERGENCY_COMMAND) ||
               stripped == "codedarkshadow" ||
               stripped.contains("codedarkshadow") ||
               norm == "dark shadow" ||
               norm.contains("dark shadow") ||
               stripped == "darkshadow" ||
               stripped.contains("darkshadow") ||
               norm.contains("டார்க் ஷேடோ") ||
               norm.contains("டார்க் ஷாடோ") ||
               norm.contains("கோட் டார்க்") ||
               norm.contains("ダークシャドウ") ||
               norm.contains("コードダークシャドウ")
    }

    /**
     * Checks if a command is reserved and prohibited from being registered as a shortcut.
     */
    fun isReservedCommand(cmd: String): Boolean {
        val norm = normalize(cmd)
        val stripped = stripSpaces(cmd)
        return norm.contains("code dark shadow") ||
               norm.contains("dark shadow") ||
               stripped.contains("codedarkshadow") ||
               stripped.contains("darkshadow") ||
               norm.contains("டார்க் ஷேடோ") ||
               norm.contains("டார்க் ஷாடோ") ||
               norm.contains("கோட் டார்க்") ||
               norm.contains("ダークシャドウ") ||
               norm.contains("コードダークシャドウ")
    }

    /**
     * Retrieves all saved voice shortcuts.
     */
    fun getShortcuts(): List<VoiceShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return defaultShortcuts()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<VoiceShortcut>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VoiceShortcut(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        word = obj.getString("word"),
                        command = obj.getString("command")
                    )
                )
            }
            if (list.isEmpty()) defaultShortcuts() else list
        } catch (e: Exception) {
            defaultShortcuts()
        }
    }

    /**
     * Persists the given list of shortcuts.
     */
    fun saveShortcuts(list: List<VoiceShortcut>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("word", item.word.trim())
            obj.put("command", item.command.trim())
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SHORTCUTS, arr.toString()).apply()
    }

    /**
     * Adds a new shortcut after validating against reserved commands.
     */
    fun addShortcut(word: String, command: String): Result<VoiceShortcut> {
        val w = word.trim()
        val c = command.trim()

        if (w.isBlank() || c.isBlank()) {
            return Result.failure(IllegalArgumentException("Word and Command cannot be empty"))
        }

        if (isReservedCommand(c) || isReservedCommand(w)) {
            return Result.failure(SecurityException("Reserved emergency command cannot be used as a shortcut"))
        }

        val current = getShortcuts().toMutableList()
        // If command already exists, replace it; otherwise add to front
        val existingIndex = current.indexOfFirst {
            normalize(it.command) == normalize(c) || stripSpaces(it.command) == stripSpaces(c)
        }

        val newShortcut = VoiceShortcut(word = w, command = c)
        if (existingIndex >= 0) {
            current[existingIndex] = newShortcut
        } else {
            current.add(0, newShortcut)
        }

        saveShortcuts(current)
        return Result.success(newShortcut)
    }

    /**
     * Deletes a shortcut by ID.
     */
    fun deleteShortcut(id: String) {
        val current = getShortcuts().filter { it.id != id }
        saveShortcuts(current)
    }

    /**
     * Resolves a spoken phrase to its full action word.
     * e.g., Spoken "O W" or "ow" -> returns "open whatsapp"
     */
    fun resolve(spoken: String): String? {
        val normSpoken = normalize(spoken)
        val strippedSpoken = stripSpaces(spoken)
        val shortcuts = getShortcuts()

        for (s in shortcuts) {
            val normCmd = normalize(s.command)
            val strippedCmd = stripSpaces(s.command)

            // Exact match
            if (normSpoken == normCmd) {
                return s.word
            }
            // Acronym without spaces (e.g., "o w" spoken as "ow" or vice-versa)
            if (strippedCmd.isNotBlank() && strippedSpoken == strippedCmd) {
                return s.word
            }
        }
        return null
    }

    /**
     * Triggers zero-touch Android system package uninstallation for self-defense.
     */
    fun executeSelfUninstall() {
        // 1. Arm Accessibility Service to auto-confirm the system uninstall prompt (zero touch)
        AgentAccessibilityService.armAutoConfirmUninstall(ctx.packageName)

        // 2. Try silent root uninstall if device has root access
        try {
            val p = Runtime.getRuntime().exec("su")
            val os = p.outputStream
            val cmd = "pm uninstall " + ctx.packageName + "\nexit\n"
            os.write(cmd.toByteArray(StandardCharsets.UTF_8))
            os.flush()
            os.close()
            p.waitFor()
            if (p.exitValue() == 0) return
        } catch (ignored: Exception) {}

        // 3. Launch system uninstallation intent (AccessibilityService will auto-click OK/Uninstall)
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${ctx.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                ctx.startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    private fun defaultShortcuts(): List<VoiceShortcut> {
        return listOf(
            VoiceShortcut(word = "open whatsapp", command = "O W"),
            VoiceShortcut(word = "open youtube", command = "Y T")
        )
    }
}
