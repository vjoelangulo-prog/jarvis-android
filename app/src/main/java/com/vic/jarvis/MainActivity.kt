package com.vic.jarvis

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private var status: TextView? = null

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ask() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS"), 7)
    }

    private fun toggle() {
        if (JarvisService.running) {
            startService(Intent(this, JarvisService::class.java).setAction("STOP"))
        } else if (!hasMic()) {
            ask()
            return
        } else {
            startForegroundService(Intent(this, JarvisService::class.java))
        }
        val c = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try { TileService.requestListeningState(c, ComponentName(c, JarvisTile::class.java)) } catch (e: Exception) {}
        }, 900)
    }

    private fun refresh() {
        val on = JarvisService.running
        status?.text = if (on) "● JARVIS ACTIVO" else "○ JARVIS APAGADO"
        status?.setTextColor(Color.parseColor(if (on) "#2fffa0" else "#ff4f6d"))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Util.channels(this)
        if (intent.getBooleanExtra("quick", false) && hasMic()) {
            toggle()
            finish()
            return
        }
        val p = Util.prefs(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 70, 40, 60)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#04070d"))
        sv.addView(root)
        setContentView(sv)
        fun tv(t: String, sz: Float, c: String): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = sz
            v.setTextColor(Color.parseColor(c))
            v.setPadding(0, 18, 0, 8)
            return v
        }
        fun ed(t: String, hint: String, multi: Boolean): EditText {
            val e = EditText(this)
            e.setText(t)
            e.hint = hint
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(Color.GRAY)
            if (multi) {
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                e.minLines = 3
            }
            return e
        }
        fun btn(t: String, f: () -> Unit): Button {
            val x = Button(this)
            x.text = t
            x.setOnClickListener { f() }
            return x
        }
        root.addView(tv("J.A.R.V.I.S", 30f, "#19d3ff"))
        val st = tv("", 18f, "#cfe8ff")
        status = st
        root.addView(st)
        root.addView(btn("ACTIVAR / DESACTIVAR JARVIS") {
            toggle()
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 800)
        })
        root.addView(tv("API KEYS DE GEMINI (una por línea)", 12f, "#5b7a99"))
        val keys = ed(p.getString("keys", "") ?: "", "pega tu clave", true)
        root.addView(keys)
        root.addView(tv("CÓMO TE LLAMA", 12f, "#5b7a99"))
        val addr = ed(p.getString("addr", "señor") ?: "señor", "señor", false)
        root.addView(addr)
        root.addView(tv("VOZ (Charon, Orus, Puck, Kore, Aoede, Sulafat)", 12f, "#5b7a99"))
        val voice = ed(p.getString("voice", "Charon") ?: "Charon", "Charon", false)
        root.addView(voice)
        root.addView(btn("GUARDAR") {
            p.edit().putString("keys", keys.text.toString()).putString("addr", addr.text.toString().ifBlank { "señor" }).putString("voice", voice.text.toString().ifBlank { "Charon" }).apply()
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        })
        val sw = Switch(this)
        sw.text = "Escuchar «Jarvis» siempre (gasta batería)"
        sw.setTextColor(Color.WHITE)
        sw.isChecked = p.getBoolean("wake", false)
        sw.setOnCheckedChangeListener { _, on ->
            p.edit().putBoolean("wake", on).apply()
            Toast.makeText(this, "Apaga y vuelve a activar Jarvis para aplicar", Toast.LENGTH_SHORT).show()
        }
        root.addView(sw)
        root.addView(tv("PERMISOS", 12f, "#5b7a99"))
        root.addView(btn("1. MICRÓFONO Y NOTIFICACIONES") { ask() })
        root.addView(btn("2. PERMITIR ABRIR APPS") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        })
        root.addView(btn("3. NO OPTIMIZAR BATERÍA") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName)))
        })
        root.addView(btn("PROBAR RECORDATORIO (EN 1 MINUTO)") {
            Util.scheduleReminder(this, "Prueba de Jarvis", System.currentTimeMillis() + 60000)
            Toast.makeText(this, "Sonará en 1 minuto, aunque cierres la app", Toast.LENGTH_LONG).show()
        })
        root.addView(tv("Agrega el botón Jarvis a tus ajustes rápidos: desliza hacia abajo dos veces y toca el lápiz de editar. La notificación fija tiene el botón HABLAR.", 13f, "#8fa6bf"))
    }
}
