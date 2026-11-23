package com.vic.jarvis

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Util {
    const val CH_SERVICE = "jarvis_service"
    const val CH_REMIND = "jarvis_remind"
    const val CH_ACT = "jarvis_act"
    val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-flash-lite")

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Jarvis activo", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ACT, "Acciones de Jarvis", NotificationManager.IMPORTANCE_HIGH))
        val r = NotificationChannel(CH_REMIND, "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
        val at = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        r.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), at)
        r.enableVibration(true)
        r.vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
        nm.createNotificationChannel(r)
    }

    fun keys(c: Context): List<String> = prefs(c).getString("keys", "")!!.split("\n").map { it.trim() }.filter { it.length > 15 }

    fun facts(c: Context): MutableList<String> {
        val a = JSONArray(prefs(c).getString("facts", "[]"))
        return MutableList(a.length()) { a.getString(it) }
    }

    fun addFact(c: Context, f: String) {
        val l = facts(c)
        if (f.isNotBlank() && !l.contains(f)) {
            l.add(f)
            while (l.size > 60) l.removeAt(0)
            prefs(c).edit().putString("facts", JSONArray(l).toString()).apply()
        }
    }

    fun scheduleReminder(c: Context, text: String, at: Long) {
        val id = (at / 1000).toInt() xor text.hashCode()
        val arr = JSONArray(prefs(c).getString("rem", "[]"))
        arr.put(JSONObject().put("id", id).put("t", text).put("at", at))
        prefs(c).edit().putString("rem", arr.toString()).apply()
        setAlarm(c, id, text, at)
    }

    fun setAlarm(c: Context, id: Int, text: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val i = Intent(c, ReminderReceiver::class.java).putExtra("t", text).putExtra("id", id)
        val pi = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    fun rescheduleAll(c: Context) {
        val a = JSONArray(prefs(c).getString("rem", "[]"))
        val keep = JSONArray()
        val now = System.currentTimeMillis()
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            if (o.getLong("at") > now) {
                keep.put(o)
                setAlarm(c, o.getInt("id"), o.getString("t"), o.getLong("at"))
            }
        }
        prefs(c).edit().putString("rem", keep.toString()).apply()
    }

    fun cancelAlarm(c: Context, id: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, id, Intent(c, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
    }

    fun rescheduleWeb(c: Context) {
        val a = JSONArray(prefs(c).getString("remweb", "[]"))
        val now = System.currentTimeMillis()
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            if (o.getLong("at") > now) setAlarm(c, o.getInt("id"), o.getString("t"), o.getLong("at"))
        }
    }

    fun extractJson(t: String): String {
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        return if (a >= 0 && b > a) t.substring(a, b + 1) else t
    }

    private fun httpPost(url: String, headers: Map<String, String>, body: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 16000
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

    fun ask(c: Context, sys: String, hist: List<Pair<String, String>>, user: String): String? {
        val t0 = System.currentTimeMillis()
        val left = { 28000L - (System.currentTimeMillis() - t0) }
        val contents = JSONArray()
        for (h in hist) contents.put(JSONObject().put("role", h.first).put("parts", JSONArray().put(JSONObject().put("text", h.second))))
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        val gb = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 500))
            .toString()
        for (m in MODELS) {
            for (k in keys(c)) {
                if (left() < 0) break
                val r = post(k, m, gb)
                if (r != null) {
                    try {
                        return JSONObject(r).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    } catch (e: Exception) {
                    }
                }
            }
        }
        val kc = prefs(c).getString("k_claude", "")!!.trim()
        if (kc.length > 15 && left() > 0) {
            val msgs = JSONArray()
            for (h in hist) msgs.put(JSONObject().put("role", if (h.first == "model") "assistant" else "user").put("content", h.second))
            msgs.put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 500).put("system", sys).put("messages", msgs).toString()
            val r = httpPost("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to kc, "anthropic-version" to "2023-06-01"), body)
            if (r != null) {
                try {
                    return JSONObject(r).getJSONArray("content").getJSONObject(0).getString("text")
                } catch (e: Exception) {
                }
            }
        }
        val ko = prefs(c).getString("k_openai", "")!!.trim()
        if (ko.length > 15 && left() > 0) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            for (h in hist) msgs.put(JSONObject().put("role", if (h.first == "model") "assistant" else "user").put("content", h.second))
            msgs.put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject().put("model", "gpt-6.1-terra").put("max_completion_tokens", 1500).put("messages", msgs).toString()
            val r = httpPost("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer " + ko), body)
            if (r != null) {
                try {
                    return JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    fun post(key: String, model: String, body: String): String? {
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 14000
            c.doOutput = true
            c.setRequestProperty("x-goog-api-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            val t = (if (ok) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            if (ok) t else null
        } catch (e: Exception) {
            null
        }
    }

    fun gemini(c: Context, body: String, models: List<String> = MODELS): String? {
        for (m in models) for (k in keys(c)) {
            val r = post(k, m, body)
            if (r != null) return r
        }
        return null
    }
}
