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
