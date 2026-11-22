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
        @Volatile var uiVisible = false }

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
            if (wakeMode) main.post { listen() }
        }
        return START_NOT_STICKY
    }

    private fun listen() {
        if (speaking || busy || uiVisible) return
        try {
            rec?.destroy()
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                speak("El reconocimiento de voz no está disponible en este teléfono.")
                return
            }
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(res: Bundle?) {
                    handle(res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
                }
                override fun onError(e: Int) {
                    if (wakeMode && !busy && !speaking) main.postDelayed({ listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 400L)
                    else update("Listo, " + addr())
                }
                override fun onReadyForSpeech(p: Bundle?) { update(if (awaiting) "Dígame, " + addr() else "Escuchando…") }
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
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            r.startListening(ri)
        } catch (e: Exception) {
            update("Error de micrófono")
        }
    }

    private fun handle(t0: String) {
        val t = t0.trim()
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
                    val sys = """Eres J.A.R.V.I.S., el mayordomo de inteligencia artificial de Tony Stark: educado, eficiente, con ingenio sutil. Llama al usuario "${addr()}". Responde en español, máximo dos frases, sin listas ni símbolos. Si recibes una orden confirma con "Sí, ${addr()}" o "Enseguida". Fecha y hora actual: $now. Lo que sabes del usuario: $facts. Responde SOLO un JSON: {"say":"lo que dirás en voz alta","remember":"dato personal nuevo y permanente del usuario, o vacío","action":{"type":"none|reminder|open_app|youtube|maps|alarm|timer|call|whatsapp|event","text":"texto del recordatorio","at":"YYYY-MM-DDTHH:MM:SS en hora local","app":"nombre de la app","q":"búsqueda o destino","seconds":0,"name":"nombre del contacto","message":"texto del mensaje en primera persona","minutes":60}}. Usa call para llamar a un contacto, whatsapp para escribir por WhatsApp (con name y message), event para agendar en el calendario (text es el título y at la fecha). Usa reminder para recuérdame, alarm para despertadores y timer para temporizadores."""
                    val contents = JSONArray()
                    for (h in hist.takeLast(10)) contents.put(JSONObject().put("role", h.first).put("parts", JSONArray().put(JSONObject().put("text", h.second))))
                    contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
                    val body = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                        .put("contents", contents)
                        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 500))
                        .toString()
                    val raw = Util.gemini(this, body)
                    if (raw == null) {
                        say = "No pude conectar con Gemini, " + addr() + ". Revise su clave o su internet."
                    } else {
                        val txt = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
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
                    val at = parse(a.optString("at"))
                    if (at != null) {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = at
                        launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY)).putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE)).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("text")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "la alarma")
                    }
                }
                "call" -> {
                    val c = findContact(a.optString("name"))
                    if (c == null) return "No encontré a " + a.optString("name") + " en sus contactos, " + addr() + ". Revise el permiso de contactos."
                    pending = Pair(c.first, { dial(c.second) })
                    awaiting = true
                    return "¿Llamo a " + c.first + ", " + addr() + "?"
                }
                "whatsapp" -> {
                    val c = findContact(a.optString("name"))
                    val m = a.optString("message")
                    if (c == null) return "No encontré a " + a.optString("name") + " en sus contactos, " + addr() + ". Revise el permiso de contactos."
                    if (m.isBlank()) return "¿Qué quiere decirle a " + c.first + ", " + addr() + "?"
                    pending = Pair(c.first, { openWa(c.second, m) })
                    awaiting = true
                    return "Voy a escribirle a " + c.first + ": " + m + ". ¿Lo abro?"
                }
                "event" -> {
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

    private fun stopSpeaking() {
        gen++
        speaking = false
        tts?.stop()
    }

    private fun speak(text: String, then: (() -> Unit)? = null) {
        stopSpeaking()
        val my = gen
        speaking = true
        update(text.take(90))
        thread {
            var ok = false
            try {
                val clean = text.replace(Regex("[*_#`~>|]"), " ").take(700)
                val voice = Util.prefs(this).getString("voice", "Charon") ?: "Charon"
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Di en español, con voz natural, fluida y serena: " + clean)))))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
                    .toString()
                val raw = Util.gemini(this, body, listOf("gemini-3.1-flash-tts-preview"))
                if (raw != null && my == gen) {
                    val d = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
                    playPcm(Base64.decode(d, Base64.DEFAULT), my)
                    ok = true
                }
            } catch (e: Exception) {
            }
            if (my == gen) main.post { if (ok) finishSpeak(my, then) else deviceSpeak(text, my, then) }
        }
    }

    private fun playPcm(pcm: ByteArray, my: Int) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size)
        t.play()
        val frames = pcm.size / 2
        val t0 = SystemClock.elapsedRealtime()
        val limit = frames * 1000L / 24000L + 1500L
        while (my == gen && t.playbackHeadPosition < frames && SystemClock.elapsedRealtime() - t0 < limit) Thread.sleep(40)
        try { t.stop() } catch (e: Exception) {}
        t.release()
    }

    private fun deviceSpeak(text: String, my: Int, then: (() -> Unit)?) {
        if (!ttsReady) {
            finishSpeak(my, then)
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { main.post { finishSpeak(my, then) } }
            override fun onError(u: String?) { main.post { finishSpeak(my, then) } }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j" + my)
    }

    private fun finishSpeak(my: Int, then: (() -> Unit)?) {
        if (my != gen) return
        speaking = false
        update("Listo, " + addr())
        if (then != null) then() else if (awaiting || wakeMode) listen()
    }

    override fun onDestroy() {
        running = false
        gen++
        hideBubble()
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
