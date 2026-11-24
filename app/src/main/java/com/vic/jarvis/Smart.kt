package com.vic.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Smart {
    private val CODE = Regex("(?i)(c[oó]digo|programa|script|funci[oó]n|bug|debug|python|kotlin|java|javascript|html|css|sql|regex|\\bapi\\b|\\bgit\\b|bash|algoritmo|compil|clase|array|variable|bucle|json)")
    private val HARD = Regex("(?i)(explica|por qu[eé]|analiza|compara|calcula|demuestra|resuelve|razona|diferencia|estrategia|paso a paso)")

    private fun post(url: String, headers: Map<String, String>, body: String, timeout: Int): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = timeout
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            val t = (if (ok) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            if (ok) t else null
        } catch (e: Exception) {
            null
        }
    }

    private fun gem(c: Context, sys: String, hist: List<Pair<String, String>>, user: String, models: List<String>, maxT: Int, to: Int, left: () -> Long): String? {
        val contents = JSONArray()
        for (h in hist) contents.put(JSONObject().put("role", h.first).put("parts", JSONArray().put(JSONObject().put("text", h.second))))
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", maxT))
            .toString()
        for (m in models) {
            for (k in Util.keys(c)) {
                if (left() <= 0) return null
                val r = post("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent", mapOf("x-goog-api-key" to k), body, to)
                if (r != null) {
                    try {
                        return JSONObject(r).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    } catch (e: Exception) {
                    }
                }
            }
        }
        return null
    }

    private fun claude(c: Context, sys: String, hist: List<Pair<String, String>>, user: String, maxT: Int, to: Int): String? {
        val kc = Util.prefs(c).getString("k_claude", "")!!.trim()
        if (kc.length < 16) return null
        val msgs = JSONArray()
        for (h in hist) msgs.put(JSONObject().put("role", if (h.first == "model") "assistant" else "user").put("content", h.second))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", maxT).put("system", sys).put("messages", msgs).toString()
        val r = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to kc, "anthropic-version" to "2023-06-01"), body, to) ?: return null
        return try {
            JSONObject(r).getJSONArray("content").getJSONObject(0).getString("text")
        } catch (e: Exception) {
            null
        }
    }

    private fun gpt(c: Context, sys: String, hist: List<Pair<String, String>>, user: String, maxT: Int, to: Int): String? {
        val ko = Util.prefs(c).getString("k_openai", "")!!.trim()
        if (ko.length < 16) return null
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for (h in hist) msgs.put(JSONObject().put("role", if (h.first == "model") "assistant" else "user").put("content", h.second))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", "gpt-6.1-terra").put("max_completion_tokens", maxT * 3).put("messages", msgs).toString()
        val r = post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer " + ko), body, to) ?: return null
        return try {
            JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            null
        }
    }

    fun ask(c: Context, sys: String, hist: List<Pair<String, String>>, userFull: String): String? {
        val user = userFull.substringBefore("\n\n[PANTALLA")
        val code = CODE.containsMatchIn(user)
        val hard = code || user.length > 160 || HARD.containsMatchIn(user)
        val maxT = if (code) 2500 else if (hard) 800 else 500
        val h = if (hard) hist else hist.takeLast(6)
        val t0 = System.currentTimeMillis()
        val left = { 32000L - (System.currentTimeMillis() - t0) }
        val extra = if (code) " El usuario pide código o ayuda técnica: pon el código completo y correcto en el campo code, y en say solo un resumen de una o dos frases, sin leer el código. Piensa con cuidado antes de responder." else if (hard) " Razona con cuidado y responde con precisión." else ""
        val s = sys + extra
        val to = if (hard) 25000 else 9000
        val models = if (hard) listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite") else Util.MODELS
        val order = if (code) listOf("claude", "gemini", "gpt") else listOf("gemini", "claude", "gpt")
        for (p in order) {
            if (left() <= 0) break
            val r = when (p) {
                "gemini" -> gem(c, s, h, userFull, models, maxT, to, left)
                "claude" -> claude(c, s, h, userFull, maxT, to)
                else -> gpt(c, s, h, userFull, maxT, to)
            }
            if (r != null) return r
        }
        return null
    }
}
