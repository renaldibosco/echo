package com.reno.echo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiError(val code: Int, message: String) : Exception(message)

data class GeminiReply(val text: String, val model: String)

/** Echo's brain on the phone: Google's Gemini API (free tier key from aistudio.google.com). */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    // Tried in order. If Google renames or retires one, Echo moves on to the next,
    // and as a last resort asks Google which Flash models this key can use.
    private val DEFAULTS = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-latest")

    fun ask(key: String, preferred: String, system: String, historyJson: String, search: Boolean, image: String? = null): GeminiReply {
        val tried = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        if (preferred.isNotBlank()) queue.add(preferred)
        DEFAULTS.forEach { if (it != preferred) queue.add(it) }

        var last: GeminiError? = null
        var listed = false
        while (true) {
            if (queue.isEmpty()) {
                if (listed) break
                listed = true
                try { availableFlash(key).forEach { if (it !in tried) queue.add(it) } } catch (_: Exception) {}
                if (queue.isEmpty()) break
            }
            val model = queue.removeFirst()
            if (!tried.add(model)) continue
            try {
                return GeminiReply(call(key, model, system, historyJson, search, image), model)
            } catch (e: GeminiError) {
                last = e
                // Search tool not allowed on this model/key: same model, no search.
                if (search && e.code == 400 && !isKeyProblem(e)) {
                    try { return GeminiReply(call(key, model, system, historyJson, false, image), model) }
                    catch (e2: GeminiError) { last = e2 }
                }
                if (isKeyProblem(last!!)) throw GeminiError(last!!.code, "BAD_KEY")
                // 404 = model not found, 429 = this model's free quota used up, 5xx = busy: try the next one
                if (last!!.code == 404 || last!!.code == 429 || last!!.code >= 500 || last!!.code == 400) continue
                throw last!!
            }
        }
        throw last ?: GeminiError(-1, "NO_MODEL")
    }

    private fun isKeyProblem(e: GeminiError): Boolean {
        val m = (e.message ?: "").lowercase()
        return e.code == 401 || e.code == 403 || m.contains("api key not valid") || m.contains("api_key_invalid")
    }

    private fun call(key: String, model: String, system: String, historyJson: String, search: Boolean, image: String?): String {
        val contents = JSONArray(historyJson)
        if (image != null && contents.length() > 0) {
            // Attach the photo to Paul's latest message
            val last = contents.getJSONObject(contents.length() - 1)
            val parts = last.optJSONArray("parts") ?: JSONArray().also { last.put("parts", it) }
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image)))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.8))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val (status, text) = http("$BASE/models/$model:generateContent", key, body.toString())
        if (status !in 200..299) throw GeminiError(status, errorMessage(text))

        val json = JSONObject(text)
        val cands = json.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
            throw GeminiError(200, if (reason.isNotEmpty()) "BLOCKED" else "EMPTY")
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw GeminiError(200, "EMPTY")
        return out
    }

    private fun availableFlash(key: String): List<String> {
        val (status, text) = http("$BASE/models?pageSize=200", key, null)
        if (status !in 200..299) return emptyList()
        val arr = JSONObject(text).optJSONArray("models") ?: return emptyList()
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            val name = m.optString("name").removePrefix("models/")
            if (methods.contains("generateContent") && name.contains("flash") &&
                !name.contains("image") && !name.contains("tts") && !name.contains("live") &&
                !name.contains("audio") && !name.contains("embedding")
            ) names.add(name)
        }
        // Newest-looking first, plain "flash" before "flash-lite"
        return names.sortedWith(compareByDescending<String> { it.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0 }
            .thenBy { if (it.contains("lite")) 1 else 0 }
            .thenBy { if (it.contains("preview") || it.contains("exp")) 1 else 0 })
    }

    private fun errorMessage(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(200)
    } catch (_: Exception) { text.take(200) }

    private fun http(url: String, key: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return status to text
        } finally {
            c.disconnect()
        }
    }
}
