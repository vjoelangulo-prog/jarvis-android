package com.vic.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private class Row(val sw: Switch, val name: String, val check: () -> Boolean)

    private var status: TextView? = null
    private var updating = false
    private val rows = ArrayList<Row>()
    private val main = Handler(Looper.getMainLooper())

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = has(Manifest.permission.RECORD_AUDIO)

    private fun appSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
    }

    private fun toggle() {
        if (JarvisService.running) {
            startService(Intent(this, JarvisService::class.java).setAction("STOP"))
        } else if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS"), 7)
            return
        } else {
            startForegroundService(Intent(this, JarvisService::class.java))
        }
        val c = applicationContext
        main.postDelayed({
            try { TileService.requestListeningState(c, ComponentName(c, JarvisTile::class.java)) } catch (e: Exception) {}
            refresh()
        }, 900)
    }

    private fun refresh() {
        updating = true
        for (r in rows) {
            val ok = r.check()
            r.sw.isChecked = ok
            r.sw.text = r.name + "  ·  " + (if (ok) "ACTIVADO" else "APAGADO")
            r.sw.setTextColor(Color.parseColor(if (ok) "#2fffa0" else "#ff9aa8"))
        }
        updating = false
        val on = JarvisService.running
        status?.text = if (on) "● JARVIS ACTIVO" else "○ JARVIS APAGADO"
        status?.setTextColor(Color.parseColor(if (on) "#2fffa0" else "#ff4f6d"))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refresh()
        var blocked = false
        for (i in perms.indices) {
            if (res[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(perms[i])) blocked = true
        }
        if (blocked) {
            Toast.makeText(this, "Android bloqueó el aviso. Actívalo en la pantalla que se abre.", Toast.LENGTH_LONG).show()
            appSettings()
        }
    }

    private fun permRow(root: LinearLayout, name: String, check: () -> Boolean, on: () -> Unit, off: () -> Unit) {
        val sw = Switch(this)
        sw.textSize = 16f
        sw.setPadding(0, 24, 0, 24)
        sw.setOnCheckedChangeListener { _, v ->
            if (!updating) {
                if (v && !check()) on() else if (!v && check()) off()
                main.postDelayed({ refresh() }, 700)
            }
        }
        rows.add(Row(sw, name, check))
        root.addView(sw)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Util.channels(this)
        if (intent.getBooleanExtra("quick", false) && hasMic()) {
            toggle()
            finish()
            return
        }
        if (!intent.getBooleanExtra("native", false) && !intent.getBooleanExtra("quick", false)) {
            startActivity(Intent(this, WebActivity::class.java))
            finish()
            return
        }
        val p = Util.prefs(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 60, 40, 80)
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
                e.minLines = 2
            }
            return e
        }
        fun btn(t: String, f: () -> Unit): Button {
            val x = Button(this)
            x.text = t
            x.setOnClickListener { f() }
            return x
        }
        fun section(t: String): TextView {
            val v = tv(t, 13f, "#19d3ff")
            v.setPadding(0, 50, 0, 10)
            return v
        }
        fun feature(label: String, key: String, def: Boolean, note: String?) {
            val s = Switch(this)
            s.text = label
            s.textSize = 16f
            s.setTextColor(Color.WHITE)
            s.setPadding(0, 24, 0, 24)
            s.isChecked = p.getBoolean(key, def)
            s.setOnCheckedChangeListener { _, v ->
                p.edit().putBoolean(key, v).apply()
                if (note != null) Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
            }
            root.addView(s)
        }
        fun runtime(vararg x: String): () -> Unit = { requestPermissions(arrayOf(*x), 7) }

        root.addView(tv("J.A.R.V.I.S", 30f, "#19d3ff"))
        val st = tv("", 18f, "#cfe8ff")
        status = st
        root.addView(st)
        root.addView(btn("ENCENDER / APAGAR JARVIS") { toggle() })

        root.addView(section("PERMISOS Y ACCESOS"))
        permRow(root, "Micrófono", { hasMic() }, runtime(Manifest.permission.RECORD_AUDIO), { appSettings() })
        permRow(root, "Contactos", { has(Manifest.permission.READ_CONTACTS) }, runtime(Manifest.permission.READ_CONTACTS), { appSettings() })
        permRow(root, "Teléfono (llamar)", { has(Manifest.permission.CALL_PHONE) }, runtime(Manifest.permission.CALL_PHONE), { appSettings() })
        permRow(root, "Calendario", { has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR) }, runtime(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), { appSettings() })
        permRow(root, "Notificaciones", { Build.VERSION.SDK_INT < 33 || has("android.permission.POST_NOTIFICATIONS") }, runtime("android.permission.POST_NOTIFICATIONS"), { appSettings() })
        val overlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))) }
        permRow(root, "Mostrar sobre otras apps", { Settings.canDrawOverlays(this) }, overlay, overlay)
        val battery = { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))) }
        permRow(root, "Batería sin restricciones", { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }, battery, battery)
        root.addView(tv("Al tocar un interruptor, Android pide el permiso. Cuando lo aceptes, el interruptor se enciende solo. Si quedó apagado, el permiso no se concedió.", 12f, "#8fa6bf"))

        root.addView(section("FUNCIONES"))
        feature("Burbuja flotante", "bubble", true, "Se aplica al reiniciar Jarvis")
        feature("Escuchar «Jarvis» siempre (gasta batería)", "wake", false, "Apaga y enciende Jarvis para aplicar")
        feature("Confirmar antes de llamar", "confirm_call", false, null)

        root.addView(section("MI AGENDA"))
        root.addView(btn("VER PENDIENTES Y AGENDA DE HOY") {
            val l = Agenda.pending(this).joinToString("\n") { "• " + it.getString("t") }
            AlertDialog.Builder(this).setTitle("Mi agenda")
                .setMessage(Agenda.dayText(this, System.currentTimeMillis(), "señor") + "\n\nPendientes:\n" + (if (l.isEmpty()) "Ninguno" else l))
                .setPositiveButton("OK", null).show()
        })
        root.addView(btn("PROBAR RECORDATORIO (EN 1 MINUTO)") {
            Util.scheduleReminder(this, "Prueba de Jarvis", System.currentTimeMillis() + 60000)
            Toast.makeText(this, "Sonará en 1 minuto, aunque cierres la app", Toast.LENGTH_LONG).show()
        })

        root.addView(section("IA Y VOZ"))
        root.addView(tv("Claves de Gemini (una por línea)", 12f, "#5b7a99"))
        val keys = ed(p.getString("keys", "") ?: "", "pega tu clave", true)
        root.addView(keys)
        root.addView(tv("Clave de Claude (opcional)", 12f, "#5b7a99"))
        val kc = ed(p.getString("k_claude", "") ?: "", "sk-ant-...", false)
        root.addView(kc)
        root.addView(tv("Clave de OpenAI (opcional)", 12f, "#5b7a99"))
        val ko = ed(p.getString("k_openai", "") ?: "", "sk-...", false)
        root.addView(ko)
        root.addView(tv("Cómo te llama", 12f, "#5b7a99"))
        val addr = ed(p.getString("addr", "señor") ?: "señor", "señor", false)
        root.addView(addr)
        root.addView(tv("Voz (Charon, Orus, Puck, Kore, Aoede, Sulafat)", 12f, "#5b7a99"))
        val voice = ed(p.getString("voice", "Charon") ?: "Charon", "Charon", false)
        root.addView(voice)
        root.addView(btn("GUARDAR") {
            p.edit()
                .putString("keys", keys.text.toString())
                .putString("k_claude", kc.text.toString().trim())
                .putString("k_openai", ko.text.toString().trim())
                .putString("addr", addr.text.toString().ifBlank { "señor" })
                .putString("voice", voice.text.toString().ifBlank { "Charon" })
                .apply()
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        })

        root.addView(section("PANTALLA WEB"))
        root.addView(btn("ABRIR JARVIS (PANTALLA PRINCIPAL)") { startActivity(Intent(this, WebActivity::class.java)) })
        refresh()
    }
}
