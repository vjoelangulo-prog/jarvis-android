package com.vic.jarvis

import android.app.Notification
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service() {
    companion object { @Volatile var running = false
        @Volatile var state = "idle"
        @Volatile var uiVisible = false
        @Volatile var screenOn = false }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var busy = false
    private var wakeMode = false
    private var awaiting = false
    private var gen = 0
    private var pending: Pair<String, () -> Unit>? = null
    private var pendingFn: ((String) -> Boolean)? = null
    private var lastContact: Pair<String, String>? = null
    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null
    private val hist = ArrayList<Pair<String, String>>()

    override fun onBind(i: Intent?): IBinder? = null

    private fun addr(): String = Util.prefs(this).getString("addr", "señor") ?: "señor"

    override fun onCreate() {
        super.onCreate()
        Util.channels(this)
        running = true
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "ES")
                ttsReady = true
            }
        }
    }

    private fun notif(text: String): Notification {
        val flag = PendingIntent.FLAG_IMMUTABLE
        val talk = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction("TALK"), flag)
        val stop = PendingIntent.getService(this, 2, Intent(this, JarvisService::class.java).setAction("STOP"), flag)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flag)
        return Notification.Builder(this, Util.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_jarvis).setContentTitle("Jarvis activo").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "HABLAR", talk).addAction(0, "APAGAR", stop).build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(11, notif(text))
        tintBubble(text)
    }

    private fun fg(text: String) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, notif(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(11, notif(text))
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (i?.action == "PAUSE") {
            try { rec?.cancel() } catch (e: Exception) {}
            return START_NOT_STICKY
        }
        if (i?.action == "RESUME") {
            if (wakeMode) main.post { listen() }
            return START_NOT_STICKY
        }
        fg("Listo, " + addr())
        main.post { showBubble() }
        if (i?.action == "TALK") {
            stopSpeaking()
            main.post { listen() }
        } else {
            wakeMode = Util.prefs(this).getBoolean("wake", false)
            main.removeCallbacks(watchdog)
            if (wakeMode) main.postDelayed(watchdog, 10000L)
            if (wakeMode) main.post { listen() }
        }
        return START_NOT_STICKY
    }

    private var lastEvent = 0L
    private var errCount = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (wakeMode && !speaking && !busy && !uiVisible && SystemClock.elapsedRealtime() - lastEvent > 20000L) listen()
            main.postDelayed(this, 10000L)
        }
    }

    private fun errName(e: Int): String? = when (e) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Error: falta el permiso del micrófono"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Error: micrófono ocupado"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Error: sin internet para la voz"
        SpeechRecognizer.ERROR_SERVER -> "Error: el reconocedor de Google falló"
        SpeechRecognizer.ERROR_AUDIO -> "Error: no pude abrir el micrófono"
        else -> null
    }

    private fun listen() {
        if (speaking || busy || uiVisible) return
        try {
            thread { try { val u = java.net.URL("https://generativelanguage.googleapis.com/").openConnection() as java.net.HttpURLConnection; u.connectTimeout = 3000; u.readTimeout = 3000; u.responseCode; u.disconnect() } catch (e: Exception) {} }
            rec?.destroy()
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                update("Error: este teléfono no tiene reconocimiento de voz")
                return
            }
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            lastEvent = SystemClock.elapsedRealtime()
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(res: Bundle?) {
                    lastEvent = SystemClock.elapsedRealtime()
                    errCount = 0
                    handle(res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
                }
                override fun onError(e: Int) {
                    lastEvent = SystemClock.elapsedRealtime()
                    val quiet = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (quiet) errCount = 0 else errCount++
                    if (busy || speaking) return
                    if (!wakeMode) {
                        update(errName(e) ?: ("Listo, " + addr()))
                        return
                    }
                    errName(e)?.let { update(it) }
                    val d = if (quiet) 250L else minOf(1200L * errCount, 8000L)
                    main.postDelayed({ if (wakeMode && !busy && !speaking) listen() }, d)
                }
                override fun onReadyForSpeech(p: Bundle?) {
                    lastEvent = SystemClock.elapsedRealtime()
                    update(if (awaiting) "Dígame, " + addr() else if (wakeMode) "Escuchando: di «Jarvis» y tu orden" else "Escuchando…")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            r.startListening(ri)
        } catch (e: Exception) {
            update("Error de micrófono")
        }
    }

    private fun handle(t0: String) {
        val t = t0.trim()
        pendingFn?.let { f ->
            if (t.isEmpty()) {
                listen()
                return
            }
            pendingFn = null
            awaiting = false
            if (!f(t)) speak("Cancelado, " + addr() + ".")
            return
        }
        pending?.let { p ->
            pending = null
            awaiting = false
            if (Regex("(?i)^(s[ií]|claro|dale|adelante|hazlo|ok|okey|abre|abrelo|ábrelo|llama)").containsMatchIn(t)) {
                p.second()
                speak("Enseguida, " + addr() + ".")
            } else {
                speak("Cancelado, " + addr() + ".")
            }
            return
        }
        if (t.isEmpty()) {
            if (wakeMode) listen() else update("Listo, " + addr())
            return
        }
        if (wakeMode && !awaiting) {
            val m = Regex("(?i)(jarvis|yarvis|harvis|jarbis|yervis|charvis|garvis|jarves)[\\s,.:;!¡¿?]*(.*)").find(t)
            if (m == null) { listen(); return }
            val rest = m.groupValues[2].trim()
            if (rest.length < 3) {
                awaiting = true
                speak("Sí, " + addr() + ".")
                return
            }
            process(rest)
            return
        }
        awaiting = false
        process(t)
    }

    private fun process(text: String) {
        busy = true
        update("Procesando…")
        thread {
            var say = ""
            var act: JSONObject? = null
            try {
                if (Util.keys(this).isEmpty()) {
                    say = "Falta su clave de Gemini, " + addr() + ". Abra la aplicación y agréguela."
                } else {
                    val now = SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", Locale("es", "ES")).format(Date())
                    val facts = Util.facts(this).joinToString("; ")
                    val sys = Prompt.system(this, addr())
                    val raw = Util.ask(this, sys, hist.takeLast(10), text + screenCtx())
                    if (raw == null) {
                        say = "No pude conectar con ninguna inteligencia, " + addr() + ". Revise sus claves o su internet."
                    } else {
                        val txt = Util.extractJson(raw)
                        val j = JSONObject(txt)
                        say = j.optString("say").ifBlank { "Entendido, " + addr() + "." }
                        act = j.optJSONObject("action")
                        val rem = j.optString("remember")
                        if (rem.isNotBlank()) Util.addFact(this, rem)
                        hist.add(Pair("user", text))
                        hist.add(Pair("model", say))
                    }
                }
            } catch (e: Exception) {
                say = "Tuve un problema para entender la respuesta, " + addr() + "."
            }
            main.post {
                busy = false
                speak(doAction(act, say))
            }
        }
    }

    private fun parse(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    private fun doAction(a: JSONObject?, say: String): String {
        if (a == null) return say
        try {
            when (a.optString("type")) {
                "screen_on" -> return screenSwitch(true)
                "screen_off" -> return screenSwitch(false)
                "call", "whatsapp" -> return contactAction(a)
                "event" -> return eventAction(a, say)
                "task_add" -> {
                    Agenda.addTask(this, a.optString("text"), parse(a.optString("at")))
                    return if (say.isBlank()) "Anotado, " + addr() + "." else say
                }
                "task_list" -> return Agenda.pendingText(this, addr())
                "task_done" -> return if (Agenda.done(this, a.optString("text"))) (if (say.isBlank()) "Hecho, " + addr() + "." else say) else "No encontré ese pendiente, " + addr() + "."
                "task_remove" -> return if (Agenda.remove(this, a.optString("text"))) (if (say.isBlank()) "Eliminado, " + addr() + "." else say) else "No encontré ese pendiente, " + addr() + "."
                "agenda" -> return Agenda.dayText(this, parse(a.optString("at")) ?: System.currentTimeMillis(), addr())
                "reminder" -> {
                    val at = parse(a.optString("at"))
                    if (at != null && at > System.currentTimeMillis()) Util.scheduleReminder(this, a.optString("text").ifBlank { "Recordatorio" }, at)
                    else return "No entendí la hora del recordatorio, " + addr() + "."
                }
                "youtube" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("q")))).setPackage("com.google.android.youtube"), "YouTube")
                "maps" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a.optString("q")))).setPackage("com.google.android.apps.maps"), "Maps")
                "open_app" -> {
                    val pkg = pkgFor(a.optString("app"))
                    val li = if (pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
                    if (li != null) launch(li, a.optString("app")) else return "No encontré esa aplicación, " + addr() + "."
                }
                "alarm" -> {
                    var at: Long = parse(a.optString("at")) ?: return "No entendí la hora de la alarma, " + addr() + "."
                    while (at <= System.currentTimeMillis()) at += 86400000L
                    Util.scheduleReminder(this, a.optString("text").ifBlank { "Alarma" }, at)
                    return if (say.isBlank()) "Alarma lista, " + addr() + "." else say
                }
                "call_old" -> {
                    val c = findContact(a.optString("name"))
                    if (c == null) return "No encontré a " + a.optString("name") + " en sus contactos, " + addr() + ". Revise el permiso de contactos."
                    pending = Pair(c.first, { dial(c.second) })
                    awaiting = true
                    return "¿Llamo a " + c.first + ", " + addr() + "?"
                }
                "whatsapp_old" -> {
                    val c = findContact(a.optString("name"))
                    val m = a.optString("message")
                    if (c == null) return "No encontré a " + a.optString("name") + " en sus contactos, " + addr() + ". Revise el permiso de contactos."
                    if (m.isBlank()) return "¿Qué quiere decirle a " + c.first + ", " + addr() + "?"
                    pending = Pair(c.first, { openWa(c.second, m) })
                    awaiting = true
                    return "Voy a escribirle a " + c.first + ": " + m + ". ¿Lo abro?"
                }
                "event_old" -> {
                    val at = parse(a.optString("at"))
                    if (at != null) {
                        val dur = a.optInt("minutes", 60).coerceIn(5, 600) * 60000L
                        launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, a.optString("text")).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + dur), "el calendario")
                    } else {
                        return "No entendí la fecha del evento, " + addr() + "."
                    }
                }
                "timer" -> launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60)).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "el temporizador")
            }
        } catch (e: Exception) {
        }
        return say
    }

    private fun pkgFor(n: String): String? {
        val k = n.lowercase(Locale.ROOT).trim()
        if (k.isEmpty()) return null
        val li = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        for (r in li) {
            val l = r.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
            if (l == k || l.contains(k) || k.contains(l)) return r.activityInfo.packageName
        }
        return null
    }

    private fun launch(i: Intent, label: String) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(i); return } catch (e: Exception) {}
        }
        val code = (System.currentTimeMillis() % 100000).toInt()
        val pi = PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Util.CH_ACT).setSmallIcon(R.drawable.ic_jarvis).setContentTitle("Jarvis").setContentText("Toque para abrir " + label).setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(code + 100, n)
    }

    private fun showBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this) || !Util.prefs(this).getBoolean("bubble", true)) return
        try {
            val wm = getSystemService(WindowManager::class.java)
            val d = resources.displayMetrics.density
            val size = (58 * d).toInt()
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.parseColor("#E6061220"))
            bg.setStroke((3 * d).toInt(), Color.parseColor("#19d3ff"))
            val v = TextView(this)
            v.text = "J"
            v.gravity = Gravity.CENTER
            v.textSize = 24f
            v.setTextColor(Color.WHITE)
            v.background = bg
            val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (12 * d).toInt()
            lp.y = (260 * d).toInt()
            var dx = 0f
            var dy = 0f
            var sx = 0
            var sy = 0
            var moved = false
            v.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = e.rawX
                        dy = e.rawY
                        sx = lp.x
                        sy = lp.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val mx = e.rawX - dx
                        val my = e.rawY - dy
                        if (Math.abs(mx) + Math.abs(my) > 12 * d) moved = true
                        if (moved) {
                            lp.x = sx + mx.toInt()
                            lp.y = sy + my.toInt()
                            wm.updateViewLayout(v, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onBubbleTap()
                    }
                }
                true
            }
            wm.addView(v, lp)
            bubble = v
            bubbleBg = bg
        } catch (e: Exception) {
        }
    }

    private fun hideBubble() {
        try {
            bubble?.let { getSystemService(WindowManager::class.java).removeView(it) }
        } catch (e: Exception) {
        }
        bubble = null
        bubbleBg = null
    }

    private fun tintBubble(text: String) {
        val c = when {
            text.startsWith("Escuchando") || text.startsWith("Dígame") -> "#2fffa0"
            text.startsWith("Procesando") -> "#ffb02e"
            text.startsWith("Listo") -> "#19d3ff"
            else -> "#ff4fd8"
        }
        main.post {
            bubbleBg?.setStroke((3 * resources.displayMetrics.density).toInt(), Color.parseColor(c))
            bubble?.text = if (screenOn) "👁" else "J"
            bubble?.invalidate()
        }
    }

    private fun onBubbleTap() {
        if (speaking) stopSpeaking()
        main.post { listen() }
    }

    private fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

    private fun findContact(name: String): Pair<String, String>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val words = norm(name.replace(Regex("(?i)^(mi|el|la|a)\\s+"), "")).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = -1000
        val cur = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null) ?: return null
        cur.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val nn = norm(n)
                if (words.all { w -> nn.contains(w) }) {
                    val score = if (nn == words.joinToString(" ")) 100 else 50 - nn.length
                    if (score > bestScore) {
                        bestScore = score
                        best = Pair(n, num)
                    }
                }
            }
        }
        return best
    }

    private fun dial(num: String) {
        val call = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(Intent(if (call) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))), "la llamada")
    }

    private fun openWa(num: String, msg: String) {
        var d = num.replace(Regex("[^0-9+]"), "")
        d = if (d.startsWith("+")) d.drop(1) else d.trimStart('0').let { if (it.length <= 10) (Util.prefs(this).getString("cc", "51") ?: "51") + it else it }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + d + "?text=" + Uri.encode(msg))).setPackage("com.whatsapp"), "WhatsApp")
    }

    private fun hasContacts() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun findContacts(name: String): List<Pair<String, String>> {
        if (!hasContacts()) return emptyList()
        val words = norm(name.replace(Regex("(?i)^(mi|mis|el|la|a)\\s+"), "")).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, Pair<Int, Pair<String, String>>>()
        val cur = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return emptyList()
        cur.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val nn = norm(n)
                if (words.all { w -> nn.contains(w) } && !found.containsKey(nn)) {
                    val score = if (nn == words.joinToString(" ")) 100 else 50 - nn.length
                    found[nn] = Pair(score, Pair(n, num))
                }
            }
        }
        val sorted = found.values.sortedByDescending { it.first }
        if (sorted.isNotEmpty() && sorted[0].first == 100) return listOf(sorted[0].second)
        return sorted.map { it.second }
    }

    private fun contactAction(a: JSONObject): String {
        val isCall = a.optString("type") == "call"
        var name = a.optString("name").trim()
        if (name.isEmpty() || Regex("(?i)^(el|ella|lo|la|le|ese|esa)$").matches(name)) name = lastContact?.first ?: ""
        if (name.isEmpty()) return "¿A quién, " + addr() + "?"
        val found = findContacts(name)
        if (found.isEmpty()) return "No encontré a " + name + " en sus contactos, " + addr() + "." + (if (!hasContacts()) " Falta el permiso de contactos." else "")
        if (found.size > 1) {
            val opts = found.take(3)
            awaiting = true
            pendingFn = { ans ->
                val n = norm(ans)
                val pick: Pair<String, String>? = opts.firstOrNull { o -> norm(o.first).split(" ").any { w -> w.length > 2 && n.contains(w) } }
                    ?: (if (n.contains("primer") || n.contains("uno")) opts[0] else if (n.contains("segund") || n.contains("dos")) opts.getOrNull(1) else null)
                if (pick != null) {
                    speak(runContact(isCall, pick, a))
                    true
                } else {
                    false
                }
            }
            return "Tengo varios, " + addr() + ": " + opts.joinToString(" o ") { it.first } + ". ¿A cuál?"
        }
        return runContact(isCall, found[0], a)
    }

    private fun runContact(isCall: Boolean, c: Pair<String, String>, a: JSONObject): String {
        lastContact = c
        Util.prefs(this).edit().putString("lastContact", c.first).apply()
        if (isCall) {
            if (Util.prefs(this).getBoolean("confirm_call", false)) {
                awaiting = true
                pending = Pair(c.first, { dial(c.second) })
                return "¿Llamo a " + c.first + ", " + addr() + "?"
            }
            dial(c.second)
            return "Llamando a " + c.first + ", " + addr() + "."
        }
        val msg = a.optString("message").trim()
        if (msg.isEmpty()) {
            awaiting = true
            pendingFn = { ans ->
                speak(sendWa(c, ans.trim(), a))
                true
            }
            return "¿Qué le digo a " + c.first + ", " + addr() + "?"
        }
        return sendWa(c, msg, a)
    }

    private fun waPackages(): List<String> = listOf("com.whatsapp", "com.whatsapp.w4b").filter {
        try {
            packageManager.getPackageInfo(it, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendWa(c: Pair<String, String>, msg: String, a: JSONObject): String {
        val pk = waPackages()
        if (pk.isEmpty()) return "No encontré WhatsApp instalado, " + addr() + "."
        val v = norm(a.optString("wa"))
        val pref: String? = if (v.contains("empres") || v.contains("busin") || v.contains("negoc")) "com.whatsapp.w4b" else if (v.contains("normal") || v.contains("person")) "com.whatsapp" else null
        val pkg: String? = if (pref != null && pk.contains(pref)) pref else if (pk.size == 1) pk[0] else null
        if (pkg == null) {
            awaiting = true
            pendingFn = { ans ->
                val n = norm(ans)
                val chosen: String? = if (n.contains("empres") || n.contains("busin") || n.contains("negoc")) "com.whatsapp.w4b" else if (n.contains("normal") || n.contains("person") || n.contains("comun") || n.contains("verde")) "com.whatsapp" else null
                if (chosen != null) {
                    speak(openWaPkg(c, msg, chosen))
                    true
                } else {
                    false
                }
            }
            return "Tiene dos WhatsApp, " + addr() + ". ¿El normal o el de empresa?"
        }
        return openWaPkg(c, msg, pkg)
    }

    private fun openWaPkg(c: Pair<String, String>, msg: String, pkg: String): String {
        var d = c.second.replace(Regex("[^0-9+]"), "")
        d = if (d.startsWith("+")) d.drop(1) else d.trimStart('0').let { if (it.length <= 10) (Util.prefs(this).getString("cc", "51") ?: "51") + it else it }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + d + "?text=" + Uri.encode(msg))).setPackage(pkg), "WhatsApp")
        if (Util.prefs(this).getBoolean("auto_send", false) && JarvisAccess.enabled(this)) {
            JarvisAccess.instance?.autoSend(pkg)
            return "Enviando el mensaje a " + c.first + ", " + addr() + "."
        }
        return "Abrí WhatsApp con el mensaje para " + c.first + ". Solo falta tocar enviar, " + addr() + "."
    }

    private fun eventAction(a: JSONObject, say: String): String {
        val at = parse(a.optString("at")) ?: return "No entendí la fecha del evento, " + addr() + "."
        val dur = a.optInt("minutes", 60).coerceIn(5, 600) * 60000L
        val title = a.optString("text").ifBlank { "Evento" }
        if (Agenda.insertEvent(this, title, at, at + dur)) return (if (say.isBlank()) "Listo." else say) + " Quedó guardado en su calendario."
        launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + dur), "el calendario")
        return say
    }

    private fun screenSwitch(on: Boolean): String {
        if (!on) {
            screenOn = false
            update("Listo, " + addr())
            return "Dejé de ver su pantalla, " + addr() + "."
        }
        if (!JarvisAccess.enabled(this)) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "los ajustes de accesibilidad")
            return "Necesito el permiso de Accesibilidad, " + addr() + ". Active Jarvis en esa lista y vuelva a pedírmelo."
        }
        screenOn = true
        update("Listo, " + addr())
        return "Ahora veo su pantalla, " + addr() + ". Dígame qué necesita."
    }

    private fun screenCtx(): String {
        if (!screenOn) return ""
        val s = JarvisAccess.instance?.snapshot() ?: return ""
        if (s.isBlank()) return ""
        return "\n\n[PANTALLA ACTUAL, texto visible con su posición]\n" + s
    }

    @Volatile private var track: AudioTrack? = null
    private var focusReq: android.media.AudioFocusRequest? = null
    private var voiceWarned = false

    private fun requestFocus() {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focusReq = r
            am.requestAudioFocus(r)
        } catch (e: Exception) {
        }
    }

    private fun abandonFocus() {
        try {
            val r = focusReq
            if (r != null) getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(r)
        } catch (e: Exception) {
        }
        focusReq = null
    }

    private fun stopSpeaking() {
        gen++
        speaking = false
        try { tts?.stop() } catch (e: Exception) {}
        try {
            track?.pause()
            track?.flush()
        } catch (e: Exception) {
        }
        abandonFocus()
    }

    private fun speak(text: String, then: (() -> Unit)? = null) {
        stopSpeaking()
        val my = gen
        speaking = true
        update(text.take(90))
        state = "speaking"
        val clean = text.replace(Regex("[*_#`~>|]"), " ").replace(Regex("\\s+"), " ").trim()
        thread {
            var ok = false
            try {
                ok = streamNatural(clean, my)
            } catch (e: Exception) {
                ok = false
            }
            if (my == gen) main.post { if (ok) finishSpeak(my, then) else deviceSpeak(clean, my, then) }
        }
    }

    private fun splitSpeech(t: String): List<String> {
        val parts = t.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (p in parts) {
            val limit = if (out.isEmpty()) 90 else 260
            if (cur.isNotEmpty() && cur.length + p.length > limit) {
                out.add(cur.toString().trim())
                cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(p)
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private fun fetchPcm(text: String): ByteArray? {
        val voice = Util.prefs(this).getString("voice", "Charon") ?: "Charon"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Di en español, con voz natural, fluida y serena: " + text)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .toString()
        for (attempt in 0 until 2) {
            val raw = Util.gemini(this, body, listOf("gemini-3.1-flash-tts-preview"))
            if (raw != null) {
                try {
                    val d = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
                    return Base64.decode(d, Base64.DEFAULT)
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 4, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun streamNatural(text: String, my: Int): Boolean {
        val chunks = splitSpeech(text)
        if (chunks.isEmpty()) return false
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val futs = arrayOfNulls<java.util.concurrent.Future<ByteArray?>>(chunks.size)
        fun start(i: Int) {
            if (i < chunks.size && futs[i] == null) {
                futs[i] = pool.submit(java.util.concurrent.Callable<ByteArray?> { fetchPcm(chunks[i]) })
            }
        }
        var at: AudioTrack? = null
        var started = false
        var frames = 0L
        try {
            start(0)
            start(1)
            for (i in chunks.indices) {
                if (my != gen) break
                val pcm: ByteArray? = try {
                    futs[i]?.get(18, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    null
                }
                start(i + 2)
                if (my != gen) break
                if (pcm == null) {
                    if (!started) return false
                    break
                }
                val tr = at ?: openTrack().also {
                    at = it
                    track = it
                    it.play()
                    requestFocus()
                }
                started = true
                var off = 0
                while (off < pcm.size && my == gen) {
                    val n = tr.write(pcm, off, minOf(pcm.size - off, 4800))
                    if (n <= 0) break
                    off += n
                }
                frames += (off / 2).toLong()
            }
            val t = at
            if (t != null && my == gen) {
                val t0 = SystemClock.elapsedRealtime()
                val limit = frames * 1000L / 24000L + 2500L
                while (my == gen && t.playbackHeadPosition.toLong() < frames && SystemClock.elapsedRealtime() - t0 < limit) Thread.sleep(30)
            }
        } finally {
            pool.shutdownNow()
            val t = at
            try { t?.stop() } catch (e: Exception) {}
            try { t?.release() } catch (e: Exception) {}
            if (track === t) track = null
        }
        return started
    }

    private fun noVoice() {
        if (voiceWarned) return
        voiceWarned = true
        try {
            val i = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(this, 91, i, PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, Util.CH_ACT).setSmallIcon(R.drawable.ic_jarvis).setContentTitle("Jarvis no tiene voz").setContentText("Instala la voz en español: toca aquí").setContentIntent(pi).setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(91, n)
        } catch (e: Exception) {
        }
    }

    private fun deviceSpeak(text: String, my: Int, then: (() -> Unit)?) {
        val t = tts
        if (!ttsReady || t == null) {
            noVoice()
            finishSpeak(my, then)
            return
        }
        var ok = false
        for (l in listOf(Locale("es", "ES"), Locale("es", "MX"), Locale("es", "US"), Locale("es"))) {
            val r = t.setLanguage(l)
            if (r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                ok = true
                break
            }
        }
        if (!ok) noVoice()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { main.post { finishSpeak(my, then) } }
            override fun onError(u: String?) { main.post { finishSpeak(my, then) } }
        })
        requestFocus()
        val rc = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j" + my)
        if (rc != TextToSpeech.SUCCESS) finishSpeak(my, then)
        main.postDelayed({ if (my == gen && speaking) finishSpeak(my, then) }, 45000L)
    }

    private fun finishSpeak(my: Int, then: (() -> Unit)?) {
        if (my != gen) return
        speaking = false
        abandonFocus()
        update("Listo, " + addr())
        if (then != null) {
            then()
        } else if (awaiting || wakeMode) {
            main.postDelayed({ if (my == gen && !speaking && !busy) listen() }, 350L)
        }
    }

    override fun onDestroy() {
        abandonFocus()
        main.removeCallbacks(watchdog)
        screenOn = false
        running = false
        gen++
        hideBubble()
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
