package com.vic.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

class NativeBridge(private val act: Activity, private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null

    private fun ev(type: String, data: String = "null") {
        main.post { web.evaluateJavascript("window.__jsrEvent&&window.__jsrEvent('" + type + "'," + data + ")", null) }
    }

    private fun has(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun startListening(lang: String, interim: Boolean) {
        main.post {
            try {
                rec?.destroy()
                rec = null
                if (!has(Manifest.permission.RECORD_AUDIO)) {
                    act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9)
                    ev("error", "\"not-allowed\"")
                    ev("end")
                    return@post
                }
                if (!SpeechRecognizer.isRecognitionAvailable(act)) {
                    ev("error", "\"service-not-allowed\"")
                    ev("end")
                    return@post
                }
                val r = SpeechRecognizer.createSpeechRecognizer(act)
                rec = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) { ev("start") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                    override fun onPartialResults(p: Bundle?) {
                        val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!t.isNullOrEmpty()) ev("result", JSONObject().put("t", t).put("f", false).toString())
                    }
                    override fun onResults(res: Bundle?) {
                        val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!t.isNullOrEmpty()) ev("result", JSONObject().put("t", t).put("f", true).toString())
                        ev("end")
                    }
                    override fun onError(e: Int) {
                        val n = when (e) {
                            6, 7 -> "no-speech"
                            1, 2, 4 -> "network"
                            3 -> "audio-capture"
                            9 -> "not-allowed"
                            else -> "aborted"
                        }
                        ev("error", "\"" + n + "\"")
                        ev("end")
                    }
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                r.startListening(i)
            } catch (e: Exception) {
                ev("error", "\"audio-capture\"")
                ev("end")
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        main.post { try { rec?.stopListening() } catch (e: Exception) {} }
    }

    @JavascriptInterface
    fun abortListening() {
        main.post {
            try { rec?.cancel() } catch (e: Exception) {}
            try { rec?.destroy() } catch (e: Exception) {}
            rec = null
        }
    }

    @JavascriptInterface
    fun syncConfig(json: String) {
        try {
            val o = JSONObject(json)
            val e = Util.prefs(act).edit()
            val ks = o.optJSONArray("keys")
            if (ks != null && ks.length() > 0) {
                val l = ArrayList<String>()
                for (i in 0 until ks.length()) l.add(ks.getString(i))
                e.putString("keys", l.joinToString("\n"))
            }
            o.optString("addr").takeIf { it.isNotBlank() }?.let { e.putString("addr", it) }
            o.optString("voice").takeIf { it.isNotBlank() }?.let { e.putString("voice", it) }
            o.optString("cc").takeIf { it.isNotBlank() }?.let { e.putString("cc", it) }
            val f = o.optJSONArray("facts")
            if (f != null && f.length() > 0) e.putString("facts", f.toString())
            e.apply()
        } catch (x: Exception) {
        }
    }

    @JavascriptInterface
    fun syncReminders(json: String) {
        try {
            val now = JSONArray(json)
            val p = Util.prefs(act)
            val old = JSONArray(p.getString("remweb", "[]"))
            val oldMap = HashMap<Int, Long>()
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                oldMap[o.getInt("id")] = o.getLong("at")
            }
            val arr = JSONArray()
            val keep = HashSet<Int>()
            for (i in 0 until now.length()) {
                val o = now.getJSONObject(i)
                val id = o.getString("id").hashCode()
                val at = o.getLong("at")
                keep.add(id)
                arr.put(JSONObject().put("id", id).put("t", o.getString("t")).put("at", at))
                if (oldMap[id] != at) Util.setAlarm(act, id, o.getString("t"), at)
            }
            for (id in oldMap.keys) if (!keep.contains(id)) Util.cancelAlarm(act, id)
            p.edit().putString("remweb", arr.toString()).apply()
        } catch (x: Exception) {
        }
    }

    @JavascriptInterface
    fun allContacts(): String {
        val arr = JSONArray()
        try {
            if (!has(Manifest.permission.READ_CONTACTS)) return "[]"
            val cur = act.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            ) ?: return "[]"
            cur.use {
                while (it.moveToNext() && arr.length() < 3000) {
                    val n = it.getString(0)
                    val t = it.getString(1)
                    if (n != null && t != null) arr.put(JSONObject().put("name", n).put("number", t))
                }
            }
        } catch (e: Exception) {
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun info(): String {
        val p = Util.prefs(act)
        return JSONObject()
            .put("service", JarvisService.running)
            .put("mic", has(Manifest.permission.RECORD_AUDIO))
            .put("contacts", has(Manifest.permission.READ_CONTACTS))
            .put("call", has(Manifest.permission.CALL_PHONE))
            .put("overlay", Settings.canDrawOverlays(act))
            .put("bubble", p.getBoolean("bubble", true))
            .put("wake", p.getBoolean("wake", false))
            .toString()
    }

    @JavascriptInterface
    fun toggleService() {
        act.runOnUiThread {
            if (JarvisService.running) {
                act.startService(Intent(act, JarvisService::class.java).setAction("STOP"))
            } else if (has(Manifest.permission.RECORD_AUDIO)) {
                act.startForegroundService(Intent(act, JarvisService::class.java))
            } else {
                act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS"), 7)
            }
        }
    }

    @JavascriptInterface
    fun askPerms() {
        act.runOnUiThread {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS", Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 7)
        }
    }

    @JavascriptInterface
    fun openOverlay() {
        act.runOnUiThread { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName))) }
    }

    @JavascriptInterface
    fun openBattery() {
        act.runOnUiThread { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName))) }
    }

    @JavascriptInterface
    fun openNative() {
        act.runOnUiThread { act.startActivity(Intent(act, MainActivity::class.java).putExtra("native", true)) }
    }

    @JavascriptInterface
    fun agendaGet(): String {
        return try {
            JSONObject().put("tasks", JSONArray(Agenda.tasksJson(act))).put("rem", JSONArray(Agenda.remListJson(act))).toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    @JavascriptInterface
    fun taskAdd(text: String, due: String): Boolean {
        if (text.isBlank()) return false
        val d = due.toLongOrNull() ?: 0L
        Agenda.addTask(act, text, if (d > 0L) d else null)
        return true
    }

    @JavascriptInterface
    fun taskDone(id: String, v: String) {
        val i = id.toLongOrNull() ?: return
        Agenda.doneById(act, i, v == "1")
    }

    @JavascriptInterface
    fun taskDel(id: String) {
        val i = id.toLongOrNull() ?: return
        Agenda.removeById(act, i)
    }

    @JavascriptInterface
    fun remAdd(text: String, at: String): Boolean {
        val t = at.toLongOrNull() ?: return false
        if (t <= System.currentTimeMillis() || text.isBlank()) return false
        Util.scheduleReminder(act, text.trim(), t)
        return true
    }

    @JavascriptInterface
    fun remDel(id: String) {
        val i = id.toIntOrNull() ?: return
        Util.removeReminder(act, i)
    }

    @JavascriptInterface
    fun setBubble(on: Boolean) {
        Util.prefs(act).edit().putBoolean("bubble", on).apply()
    }

    @JavascriptInterface
    fun setWake(on: Boolean) {
        Util.prefs(act).edit().putBoolean("wake", on).apply()
    }
}
