package com.vic.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText

class WebActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: NativeBridge
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val home = "https://vjoelangulo-prog.github.io/"
    private val offline = "<body style='background:#04070d;color:#cfe8ff;font-family:sans-serif;padding:30px'><h2>Jarvis sin conexión</h2><p>Revisa tu internet.</p><button style='font-size:20px;padding:14px' onclick=\"location.href='https://vjoelangulo-prog.github.io/'\">REINTENTAR</button><p><a style='color:#19d3ff' href='jarvis://native'>Abrir ajustes nativos</a></p></body>"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Util.channels(this)
        window.statusBarColor = Color.parseColor("#04070d")
        window.navigationBarColor = Color.parseColor("#04070d")
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#04070d"))
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " JarvisApp"
        bridge = NativeBridge(this, web)
        web.addJavascriptInterface(bridge, "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url?.toString() ?: return false
                if (u.startsWith("https://vjoelangulo-prog.github.io")) return false
                if (u.startsWith("jarvis://native")) {
                    startActivity(Intent(this@WebActivity, MainActivity::class.java).putExtra("native", true))
                    return true
                }
                external(u)
                return true
            }

            override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                if (r != null && r.isForMainFrame) v?.loadDataWithBaseURL(null, offline, "text/html", "utf-8", null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>?, p: WebChromeClient.FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    this@WebActivity.startActivityForResult(Intent.createChooser(i, "Elegir archivos"), 4242)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    cb?.onReceiveValue(null)
                    false
                }
            }

            override fun onPermissionRequest(r: PermissionRequest?) {
                r?.let { req -> runOnUiThread { req.grant(req.resources) } }
            }

            override fun onJsAlert(v: WebView?, url: String?, m: String?, r: JsResult?): Boolean {
                AlertDialog.Builder(this@WebActivity).setMessage(m).setPositiveButton("OK") { _, _ -> r?.confirm() }.setOnCancelListener { r?.cancel() }.show()
                return true
            }

            override fun onJsConfirm(v: WebView?, url: String?, m: String?, r: JsResult?): Boolean {
                AlertDialog.Builder(this@WebActivity).setMessage(m)
                    .setPositiveButton("Sí") { _, _ -> r?.confirm() }
                    .setNegativeButton("No") { _, _ -> r?.cancel() }
                    .setOnCancelListener { r?.cancel() }.show()
                return true
            }

            override fun onJsPrompt(v: WebView?, url: String?, m: String?, def: String?, r: JsPromptResult?): Boolean {
                val e = EditText(this@WebActivity)
                e.setText(def ?: "")
                AlertDialog.Builder(this@WebActivity).setMessage(m).setView(e)
                    .setPositiveButton("OK") { _, _ -> r?.confirm(e.text.toString()) }
                    .setNegativeButton("Cancelar") { _, _ -> r?.cancel() }
                    .setOnCancelListener { r?.cancel() }.show()
                return true
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS"), 5)
        }
        web.loadUrl(home)
    }

    private fun external(u: String) {
        try {
            val i = if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
            if (u.startsWith("tel:") && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) i.action = Intent.ACTION_CALL
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                if (u.startsWith("intent:")) {
                    val fb = Intent.parseUri(u, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                    if (fb != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fb)))
                }
            } catch (x: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        JarvisService.uiVisible = true
        if (JarvisService.running) startService(Intent(this, JarvisService::class.java).setAction("PAUSE"))
    }

    override fun onPause() {
        super.onPause()
        bridge.abortListening()
        JarvisService.uiVisible = false
        if (JarvisService.running) startService(Intent(this, JarvisService::class.java).setAction("RESUME"))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 4242) {
            var r: Array<Uri>? = null
            if (res == RESULT_OK && data != null) {
                val cd = data.clipData
                if (cd != null) {
                    r = Array(cd.itemCount) { cd.getItemAt(it).uri }
                } else {
                    val d = data.data
                    if (d != null) r = arrayOf(d)
                }
            }
            fileCb?.onReceiveValue(r)
            fileCb = null
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
