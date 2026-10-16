package com.nextgen.agent

import com.chaquo.python.Python
import org.json.JSONObject

/** Kotlin -> Python bridge (brain.py). Call these from a background thread. */
object Brain {
    data class Reply(
        val ok: Boolean,
        val speech: String = "",
        val content: String = "",
        val actions: List<JSONObject> = emptyList(),
        val error: String = ""
    )

    data class ModelResult(
        val models: List<Pair<String, String>>,
        val isFallback: Boolean = false,
        val error: String = ""
    )

    private fun py() = Python.getInstance().getModule("brain")

    fun models(provider: String, key: String, url: String = ""): Result<ModelResult> = runCatching {
        val j = JSONObject(py().callAttr("list_models", provider, key, url).toString())
        val isFallback = j.optBoolean("fallback", false)
        val err = j.optString("error", "")
        if (!j.getBoolean("ok") && !j.has("models")) error(err.ifEmpty { "Failed to list models" })
        val a = j.optJSONArray("models") ?: org.json.JSONArray()
        val list = (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
        ModelResult(list, isFallback, err)
    }

    fun chat(c: Config, text: String): Reply = try {
        val cfg = JSONObject().put("lang", c.lang).put("name", c.name).put("type", c.type)
            .put("provider", c.provider).put("key", c.key).put("model", c.model).put("url", c.url)
            .put("stop_word", c.commandingStopWord)
        val j = JSONObject(py().callAttr("chat", cfg.toString(), text).toString())
        if (j.getBoolean("ok")) {
            val arr = j.optJSONArray("actions") ?: org.json.JSONArray()
            val speech = j.optString("speech", "")
            val content = j.optString("content", speech)
            Reply(true, speech = speech, content = content, actions = (0 until arr.length()).map { arr.getJSONObject(it) })
        } else Reply(false, error = j.optString("error"))
    } catch (e: Exception) {
        Reply(false, error = e.message ?: "error")
    }

    fun reset() { runCatching { py().callAttr("reset") } }
}