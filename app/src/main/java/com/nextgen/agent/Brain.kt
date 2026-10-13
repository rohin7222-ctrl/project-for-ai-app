package com.nextgen.agent

import com.chaquo.python.Python
import org.json.JSONObject

/** Kotlin -> Python bridge (brain.py). Call these from a background thread. */
object Brain {
    data class Reply(
        val ok: Boolean,
        val speech: String = "",
        val actions: List<JSONObject> = emptyList(),
        val error: String = ""
    )

    private fun py() = Python.getInstance().getModule("brain")

    fun models(provider: String, key: String, url: String = ""): Result<List<Pair<String, String>>> = runCatching {
        val j = JSONObject(py().callAttr("list_models", provider, key, url).toString())
        if (!j.getBoolean("ok")) error(j.getString("error"))
        val a = j.getJSONArray("models")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
    }

    fun chat(c: Config, text: String): Reply = try {
        val cfg = JSONObject().put("lang", c.lang).put("name", c.name).put("type", c.type)
            .put("provider", c.provider).put("key", c.key).put("model", c.model).put("url", c.url)
        val j = JSONObject(py().callAttr("chat", cfg.toString(), text).toString())
        if (j.getBoolean("ok")) {
            val arr = j.getJSONArray("actions")
            Reply(true, j.optString("speech"), (0 until arr.length()).map { arr.getJSONObject(it) })
        } else Reply(false, error = j.optString("error"))
    } catch (e: Exception) {
        Reply(false, error = e.message ?: "error")
    }

    fun reset() { runCatching { py().callAttr("reset") } }
}