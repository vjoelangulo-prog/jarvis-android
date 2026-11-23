package com.vic.jarvis

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class JarvisAccess : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccess? = null

        fun enabled(c: Context): Boolean {
            val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(":").any { it.startsWith(c.packageName + "/") }
        }
    }

    private var up = false
    private var dn = false
    private var fired = false
    private val blocked = listOf("bank", "banco", "bbva", "bcp", "interbank", "scotia", "yape", "plin", "paypal", "wallet", "pay", "authenticator", "keepass", "bitwarden", "lastpass", "1password")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Util.prefs(this).getBoolean("vol_combo", true)) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_VOLUME_UP) up = down else dn = down
        if (up && dn) {
            if (!fired) {
                fired = true
                trigger()
            }
            return true
        }
        if (!up && !dn) {
            val was = fired
            fired = false
            return was
        }
        return fired
    }

    private fun trigger() {
        try {
            val i = Intent(this, JarvisService::class.java).setAction("TALK")
            if (JarvisService.running) startService(i) else startForegroundService(i)
        } catch (e: Exception) {
            try {
                Util.channels(this)
                val pi = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra("quick", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                val n = Notification.Builder(this, Util.CH_ACT).setSmallIcon(R.drawable.ic_jarvis).setContentTitle("Jarvis").setContentText("Toque para activar a Jarvis").setContentIntent(pi).setAutoCancel(true).build()
                getSystemService(NotificationManager::class.java).notify(77, n)
            } catch (x: Exception) {
            }
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    fun snapshot(): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return ""
        val low = pkg.lowercase(Locale.ROOT)
        if (blocked.any { low.contains(it) }) return "(App protegida: Jarvis no lee esta pantalla)"
        val dm = resources.displayMetrics
        val sb = StringBuilder()
        sb.append("App: ").append(appName(pkg)).append("\n")
        var n = 0
        fun walk(nd: AccessibilityNodeInfo?, depth: Int) {
            if (nd == null || n > 110 || sb.length > 3500 || depth > 25) return
            if (!nd.isVisibleToUser || nd.isPassword) return
            val t = (nd.text?.toString() ?: nd.contentDescription?.toString() ?: "").trim()
            if (t.isNotEmpty()) {
                val r = Rect()
                nd.getBoundsInScreen(r)
                val cx = r.centerX().toFloat() / dm.widthPixels
                val cy = r.centerY().toFloat() / dm.heightPixels
                val v = if (cy < 0.33f) "arriba" else if (cy < 0.66f) "centro" else "abajo"
                val h = if (cx < 0.33f) "izquierda" else if (cx < 0.66f) "centro" else "derecha"
                sb.append("- ").append(t.take(80))
                if (nd.isClickable || nd.isEditable) sb.append(" [tocable]")
                sb.append(" (").append(v).append(" ").append(h).append(")\n")
                n++
            }
            for (i in 0 until nd.childCount) walk(nd.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    fun autoSend(pkg: String) {
        val h = Handler(Looper.getMainLooper())
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == pkg && clickSend(root)) return
                if (tries < 24) h.postDelayed(this, 500)
            }
        }
        h.postDelayed(r, 1500)
    }

    private fun clickSend(root: AccessibilityNodeInfo): Boolean {
        val ids = root.findAccessibilityNodeInfosByViewId(root.packageName.toString() + ":id/send")
        if (ids != null) {
            for (x in ids) if (tap(x)) return true
        }
        return findSend(root, 0)
    }

    private fun findSend(nd: AccessibilityNodeInfo?, d: Int): Boolean {
        if (nd == null || d > 30) return false
        val cd = nd.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
        if ((cd == "enviar" || cd == "send") && tap(nd)) return true
        for (i in 0 until nd.childCount) if (findSend(nd.getChild(i), d + 1)) return true
        return false
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        var k = 0
        while (x != null && k < 4) {
            if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent
            k++
        }
        return false
    }
}
