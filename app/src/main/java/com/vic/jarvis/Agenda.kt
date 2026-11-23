package com.vic.jarvis

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Agenda {
    private fun tasks(c: Context): JSONArray = JSONArray(Util.prefs(c).getString("tasks", "[]"))

    private fun save(c: Context, a: JSONArray) {
        Util.prefs(c).edit().putString("tasks", a.toString()).apply()
    }

    private fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

    fun addTask(c: Context, text: String, due: Long?) {
        if (text.isBlank()) return
        val a = tasks(c)
        a.put(JSONObject().put("id", System.currentTimeMillis()).put("t", text.trim()).put("due", due ?: 0L).put("done", false))
        save(c, a)
    }

    fun pending(c: Context): List<JSONObject> {
        val a = tasks(c)
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.getBoolean("done")) out.add(o)
        }
        return out
    }

    private fun match(c: Context, q: String): Int {
        val a = tasks(c)
        val w = norm(q).split(" ").filter { it.length > 2 }
        var best = -1
        var bs = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getBoolean("done")) continue
            val n = norm(o.getString("t"))
            val s = w.count { n.contains(it) }
            if (s > bs) {
                bs = s
                best = i
            }
        }
        return best
    }

    fun done(c: Context, q: String): Boolean {
        val i = match(c, q)
        if (i < 0) return false
        val a = tasks(c)
        a.getJSONObject(i).put("done", true)
        save(c, a)
        return true
    }

    fun remove(c: Context, q: String): Boolean {
        val i = match(c, q)
        if (i < 0) return false
        val a = tasks(c)
        a.remove(i)
        save(c, a)
        return true
    }

    fun pendingText(c: Context, addr: String): String {
        val l = pending(c)
        if (l.isEmpty()) return "No tiene pendientes, " + addr + "."
        return "Tiene " + l.size + (if (l.size == 1) " pendiente: " else " pendientes: ") + l.joinToString(", ") { it.getString("t") } + "."
    }

    fun events(c: Context, from: Long, to: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return out
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, from)
            ContentUris.appendId(b, to)
            val cur = c.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE), null, null, CalendarContract.Instances.BEGIN + " ASC")
            if (cur != null) {
                cur.use {
                    while (it.moveToNext()) out.add(Pair(it.getLong(0), it.getString(1) ?: "Evento"))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun insertEvent(c: Context, title: String, start: Long, end: Long): Boolean {
        if (c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        var ok = false
        try {
            var id = -1L
            val cur = c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ? AND " + CalendarContract.Calendars.VISIBLE + " = 1",
                arrayOf("500"), null
            )
            if (cur != null) {
                cur.use { if (it.moveToFirst()) id = it.getLong(0) }
            }
            if (id >= 0) {
                val cv = ContentValues()
                cv.put(CalendarContract.Events.CALENDAR_ID, id)
                cv.put(CalendarContract.Events.TITLE, title)
                cv.put(CalendarContract.Events.DTSTART, start)
                cv.put(CalendarContract.Events.DTEND, end)
                cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                ok = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv) != null
            }
        } catch (e: Exception) {
            ok = false
        }
        return ok
    }

    fun dayText(c: Context, day: Long, addr: String): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val from = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val to = cal.timeInMillis
        val today = System.currentTimeMillis() in from until to
        val tf = SimpleDateFormat("h:mm a", Locale("es", "ES"))
        val label = if (today) "Hoy" else SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES")).format(Date(from))
        val parts = ArrayList<String>()
        val ts = ArrayList<String>()
        for (o in pending(c)) {
            val due = o.optLong("due", 0L)
            if (due in from until to || (due == 0L && today)) ts.add(o.getString("t"))
        }
        if (ts.isNotEmpty()) parts.add("pendientes: " + ts.joinToString(", "))
        val rs = ArrayList<Pair<Long, String>>()
        for (key in listOf("rem", "remweb")) {
            val a = JSONArray(Util.prefs(c).getString(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val at = o.getLong("at")
                if (at in from until to) rs.add(Pair(at, o.getString("t")))
            }
        }
        rs.sortBy { it.first }
        if (rs.isNotEmpty()) parts.add("recordatorios: " + rs.joinToString(", ") { "a las " + tf.format(Date(it.first)) + " " + it.second })
        val ev = events(c, from, to)
        if (ev.isNotEmpty()) parts.add("en el calendario: " + ev.joinToString(", ") { "a las " + tf.format(Date(it.first)) + " " + it.second })
        if (parts.isEmpty()) return label + " no tiene nada pendiente, " + addr + "."
        return label + ", " + addr + ", tiene " + parts.joinToString(". ") + "."
    }
}
