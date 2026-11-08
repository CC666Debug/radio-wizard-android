package io.github.cc666debug.radiowizard

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.ValueCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.app.Dialog
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Shows the RadioWizard website, but casts through Android's own Google Cast service
 * (like YouTube Music) instead of Chrome's speaker list. The page talks to this class through
 * window.AndroidCast (see android-cast.js on the website, the same as Halloween Caster's); reports go back through
 * window.AndroidCastEvent.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var castContext: CastContext
    private val main = Handler(Looper.getMainLooper())

    private var session: CastSession? = null
    private var pickId = 0   // the page's open "pick a speaker" request, if any

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    // "Back up": the first time, pick a folder; later backups go straight there (📁 Folder picks another).
    private val prefs by lazy { getSharedPreferences("backup", MODE_PRIVATE) }
    private var pendingBackup: Pair<String, String?>? = null   // text null = only pick the folder (pickBackupFolder)
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val b = pendingBackup ?: return@registerForActivityResult
        pendingBackup = null
        if (uri == null) { sendBackup(false, b.first, "cancel"); return@registerForActivityResult }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
        prefs.edit().putString("folder", uri.toString()).apply()
        val text = b.second
        if (text == null) sendBackup(true, b.first, "picked") else writeBackup(uri, b.first, text, false)
    }

    private fun askForFolder(name: String, text: String?) {
        pendingBackup = name to text
        try { pickFolder.launch(null) } catch (e: Exception) { pendingBackup = null; sendBackup(false, name, "error") }
    }

    private fun sendBackup(ok: Boolean, name: String, where: String) = main.post {
        val r = JSONObject().put("ok", ok).put("name", name).put("where", where)
        web.evaluateJavascript("window.onAndroidBackup && window.onAndroidBackup($r)", null)
    }

    /** Writes the file into the folder, replacing one with the same name (so no "(1)" copies). */
    private fun writeBackup(tree: Uri, name: String, text: String, remembered: Boolean) {
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            var folderName = "your folder"
            contentResolver.query(dir, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) folderName = it.getString(0)
            }
            var file: Uri? = null
            contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (file == null && it.moveToNext())
                    if (it.getString(1) == name) file = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
            }
            // octet-stream keeps the name exactly as given (no extra extension added)
            val target = file ?: DocumentsContract.createDocument(contentResolver, dir, "application/octet-stream", name)
                ?: throw Exception("couldn't create the file")
            contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: throw Exception("couldn't open the file")
            sendBackup(true, name, folderName)
        } catch (e: Exception) {
            // The remembered folder is gone or no longer allowed: ask for one again.
            if (remembered) { prefs.edit().remove("folder").apply(); askForFolder(name, text) }
            else sendBackup(false, name, "error")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        castContext = CastContext.getSharedInstance(this)
        // Android's output picker and volume panel can show and control the speaker.
        MediaRouter.getInstance(this).setRouterParams(MediaRouterParams.Builder().setOutputSwitcherEnabled(true).build())
        // Android 13+ only shows the speaker's media notification (and its controls) with this permission; asked once.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Radio Caprice and other old stations only have http:// links; let them play like on the speakers.
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.webChromeClient = object : WebChromeClient() {
            // "Restore" in Saved songs: let the page pick a backup file.
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFile.launch("*/*"); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.webViewClient = object : WebViewClient() {
            // Links to other sites (station home pages...) open in their own apps.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == Uri.parse(APP_URL).host) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidCast")

        castContext.addCastStateListener { sendCastState() }
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back works like the page's ‹ Back (closes an open box, leaves an album/list/playlist). With nothing
                // to go back to, RW goes to the background instead of closing, so casting and your place are kept.
                web.evaluateJavascript("(window.rwBack && window.rwBack()) ? 'y' : 'n'") { r ->
                    if (r?.contains("y") != true) moveTaskToBack(true)
                }
            }
        })

        web.loadUrl(APP_URL)
    }

    // ── Phone volume keys ───────────────────────────

    // While casting, the volume keys turn the speaker up and down (like YouTube Music), 5% a press.
    // Quick repeated presses build on the level we just asked for, not on the speaker's slower report.
    private var keyVolume = 0.0
    private var keyVolumeAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val s = session
        if ((up || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && s != null && s.isConnected) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                val base = if (now - keyVolumeAt < 1500) keyVolume else s.volume
                keyVolume = (Math.round(base * 20) + if (up) 1 else -1).coerceIn(0, 20) / 20.0
                keyVolumeAt = now
                try {
                    if (s.isMute && up) s.isMute = false
                    s.volume = keyVolume
                } catch (e: Exception) {}
            }
            return true   // the phone's own volume stays where it is
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUpSuspended)
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        net.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    // ── Reports to the page ─────────────────────────

    private fun send(event: JSONObject) = main.post {
        web.evaluateJavascript("window.AndroidCastEvent && window.AndroidCastEvent($event)", null)
    }

    private fun sendResult(id: Int, ok: Boolean, error: String? = null) {
        if (id == 0) return
        send(JSONObject().put("type", "result").put("id", id).put("ok", ok).put("error", error ?: JSONObject.NULL))
    }

    private fun sendCastState() {
        val name = when (castContext.castState) {
            CastState.NO_DEVICES_AVAILABLE -> "NO_DEVICES_AVAILABLE"
            CastState.CONNECTING -> "CONNECTING"
            CastState.CONNECTED -> "CONNECTED"
            else -> "NOT_CONNECTED"
        }
        send(JSONObject().put("type", "castState").put("castState", name))
    }

    private fun sendSession(state: String, s: CastSession?, errorCode: String? = null) {
        val e = JSONObject().put("type", "session").put("sessionState", state)
        s?.castDevice?.friendlyName?.let { e.put("device", it) }
        errorCode?.let { e.put("errorCode", it) }
        send(e)
    }

    private fun sendStatus() {
        val s = session ?: return
        val status = s.remoteMediaClient?.mediaStatus
        val media = status?.let {
            val playerState = when (it.playerState) {
                MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
                MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
                MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> "BUFFERING"
                else -> null
            }
            val idleReason = when (it.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
                MediaStatus.IDLE_REASON_CANCELED -> "CANCELLED"
                MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
                MediaStatus.IDLE_REASON_ERROR -> "ERROR"
                else -> null
            }
            JSONObject()
                .put("playerState", playerState ?: JSONObject.NULL)
                .put("idleReason", idleReason ?: JSONObject.NULL)
                .put("contentId", it.mediaInfo?.contentId ?: JSONObject.NULL)
        }
        send(JSONObject().put("type", "status")
            .put("media", media ?: JSONObject.NULL)
            .put("volume", if (s.isConnected) s.volume else JSONObject.NULL)
            .put("muted", s.isMute))
    }

    // ── Following the speaker ───────────────────────

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = sendStatus()
    }
    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() = sendStatus()
    }

    private fun attach(s: CastSession) {
        session = s
        s.remoteMediaClient?.registerCallback(mediaCallback)
        s.addCastListener(volumeListener)
        CastKeepAliveService.start(this, s.castDevice?.friendlyName)   // keeps the app (and the speaker) alive when swiped away
        // Songs (unlike radio) need the page to send the next one when a song ends; this stops Android from freezing
        // the page while the app is in the background. It does NOT keep the phone awake (no wake lock: battery).
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    /** Lets go of the speaker (or group) in Android's own speaker list too. Ending only the cast left the
     *  group marked as in use by this app, so it vanished from the app's list until the phone was rebooted.
     *  Only for the user's own Stop: letting go stops the music on every speaker in the group, so when the
     *  speakers or Google end the cast (Google Home's volume slider does that), the app leaves them playing. */
    private fun releaseRoute() {
        try {
            val router = MediaRouter.getInstance(this)
            if (!router.selectedRoute.isDefaultOrBluetooth) router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
            if (!router.selectedRoute.isDefaultOrBluetooth) router.selectRoute(router.defaultRoute)
        } catch (e: Exception) {}
    }

    // A dropped speaker (Wi-Fi blip, or a speaker taken out of the group) gets a while to come back by itself;
    // after that the cast is ended properly instead of being left hanging.
    private val giveUpSuspended = Runnable {
        val s = castContext.sessionManager.currentCastSession
        if (s == null || !s.isConnected) {
            castContext.sessionManager.endCurrentSession(false)
            detach()
            sendSession("SESSION_ENDED", null, "gave up after 15s")
        }
    }

    private fun detach() {
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session?.removeCastListener(volumeListener)
        session = null
        CastKeepAliveService.stop(this)
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) = sendSession("SESSION_STARTING", null)
        override fun onSessionStarted(s: CastSession, sessionId: String) {
            main.removeCallbacks(giveUpSuspended)
            attach(s)
            sendSession("SESSION_STARTED", s)
            sendResult(pickId, true); pickId = 0
            sendStatus()
        }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            detach()
            sendSession("SESSION_START_FAILED", null, "session_error ($error)")
            sendResult(pickId, false, "session_error ($error)"); pickId = 0
        }
        override fun onSessionEnding(s: CastSession) = sendSession("SESSION_ENDING", s)
        override fun onSessionEnded(s: CastSession, error: Int) {
            main.removeCallbacks(giveUpSuspended)
            detach()
            sendSession("SESSION_ENDED", null, "code $error")
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            main.removeCallbacks(giveUpSuspended)
            attach(s)
            sendSession("SESSION_RESUMED", s)
            sendStatus()
        }
        override fun onSessionResumeFailed(s: CastSession, error: Int) {
            main.removeCallbacks(giveUpSuspended)
            detach()
            sendSession("SESSION_ENDED", null, "resume failed, code $error")
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) {
            sendSession("SESSION_SUSPENDED", s, "reason $reason")   // only for the log; the page waits for the end or the resume
            main.removeCallbacks(giveUpSuspended)
            main.postDelayed(giveUpSuspended, 15000)
        }
    }

    // ── Commands from the page ──────────────────────

    // ── Bandcamp sign-in ────────────────────────────

    private fun bandcampSignedIn() =
        Regex("(^|;\\s*)identity=").containsMatchIn(CookieManager.getInstance().getCookie("https://bandcamp.com") ?: "")

    @SuppressLint("SetJavaScriptEnabled")
    private fun showBandcampLogin() {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        var done = false
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Signed in once Bandcamp has set its "identity" cookie and moved on from the login page.
                if (!done && bandcampSignedIn() && !url.contains("/login")) {
                    done = true
                    CookieManager.getInstance().flush()
                    dialog.dismiss()
                }
            }
        }
        dialog.setContentView(w)
        dialog.setOnDismissListener {
            w.destroy()
            val ok = done || bandcampSignedIn()
            web.evaluateJavascript("window.onBandcampLogin && window.onBandcampLogin($ok)", null)
        }
        w.loadUrl("https://bandcamp.com/login")
        dialog.show()
    }

    // ── SoundCloud sign-in ──────────────────────────

    /**
     * Your SoundCloud sign-in: caught from SoundCloud's own requests while its page is open (it sends
     * "Authorization: OAuth <token>"), or its "oauth_token" cookie. It goes along on SoundCloud API requests.
     */
    private fun soundcloudToken(): String? =
        prefs.getString("sc_token", null)?.takeIf { it.isNotEmpty() }
            ?: (CookieManager.getInstance().getCookie("https://soundcloud.com") ?: "").split(";").map { it.trim() }
                .firstOrNull { it.startsWith("oauth_token=") }?.removePrefix("oauth_token=")?.takeIf { it.isNotEmpty() }

    /** Watches the sign-in page's own requests to SoundCloud for your sign-in. */
    private fun catchSoundcloudToken(request: WebResourceRequest, found: () -> Unit) {
        // Only the API's own requests carry the real sign-in (others can carry short-lived sign-in-step tokens).
        if (request.url.host != "api-v2.soundcloud.com") return
        val auth = request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value ?: return
        if (!auth.startsWith("OAuth ")) return
        val t = auth.removePrefix("OAuth ").trim()
        if (t.isEmpty() || t == prefs.getString("sc_token", null)) { if (t.isNotEmpty()) main.post(found); return }
        prefs.edit().putString("sc_token", t).apply()
        main.post(found)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loginView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        // Looks like desktop Chrome: a phone gets sent to SoundCloud's mobile site, whose after-sign-in page
        // just spins forever inside an app (seen on the S26). Desktop Chrome also keeps "Continue with Google" working.
        val chromeVer = Regex("Chrome/([\\d.]+)").find(settings.userAgentString)?.groupValues?.get(1) ?: "140.0.0.0"
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    /**
     * SoundCloud's sign-in opens its own pop-up window and reports back to the page that opened it,
     * so pop-ups get a real window of their own here (without one, the sign-in never finishes).
     * Closes by itself once SoundCloud has set its "oauth_token" cookie (checked every second).
     */
    private fun showSoundcloudLogin() {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val w = loginView()
        var done = false
        // Set the old sign-in aside, so only a fresh one from SoundCloud's page closes the window
        // (a refused one used to close it straight away and nothing changed). ✕ puts it back.
        val old = prefs.getString("sc_token", null)
        prefs.edit().remove("sc_token").apply()
        val popups = mutableListOf<Pair<Dialog, WebView>>()
        fun scDone() {
            if (done) return
            done = true
            CookieManager.getInstance().flush()
            popups.toList().forEach { (d, _) -> d.dismiss() }
            dialog.dismiss()
        }
        val check = object : Runnable {
            override fun run() {
                if (done) return
                if (soundcloudToken() != null) scDone() else main.postDelayed(this, 1000)
            }
        }
        val chrome = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val pw = loginView()
                pw.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                        catchSoundcloudToken(request) { scDone() }; return null
                    }
                }
                pw.webChromeClient = this
                val pd = Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_NoActionBar)
                pd.setContentView(signinBox(pw) { pd.dismiss() })
                pd.setOnDismissListener { popups.removeAll { it.second === pw }; pw.destroy() }
                popups.add(pd to pw)
                pd.show()
                (resultMsg.obj as WebView.WebViewTransport).webView = pw
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                popups.firstOrNull { it.second === window }?.first?.dismiss()
            }
        }
        w.webChromeClient = chrome
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { if (soundcloudToken() != null) scDone() }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                catchSoundcloudToken(request) { scDone() }; return null
            }
        }
        dialog.setContentView(signinBox(w) { dialog.dismiss() })
        dialog.setOnDismissListener {
            main.removeCallbacks(check)
            popups.toList().forEach { (d, _) -> d.dismiss() }
            w.destroy()
            if (!done && old != null && prefs.getString("sc_token", null) == null) prefs.edit().putString("sc_token", old).apply()
            val ok = done
            web.evaluateJavascript("window.onSoundcloudLogin && window.onSoundcloudLogin($ok)", null)
        }
        w.loadUrl("https://soundcloud.com/signin")
        dialog.show()
        main.postDelayed(check, 1000)
    }

    /** SoundCloud's page under a bar (↻ reload, ✕ close), so it's clear it's SoundCloud's sign-in and never a trap. */
    private fun signinBox(w: WebView, close: () -> Unit): android.view.View {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1B1622.toInt())
            setPadding(32, 24, 16, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Sign in to SoundCloud · closes by itself"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "↻"; setTextColor(0xFFFFFFFF.toInt()); textSize = 22f; setPadding(32, 0, 32, 0)
                setOnClickListener { w.reload() }
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "✕"; setTextColor(0xFFFFFFFF.toInt()); textSize = 22f; setPadding(32, 0, 32, 0)
                setOnClickListener { close() }
            })
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, -1, -2)
            addView(w, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    // ── Web requests for the page ───────────────────
    // The radio directories (TuneIn, SHOUTcast, Radio Garden...) block requests from websites, but not from apps,
    // so the page asks the phone to fetch them. Answers go back through window.AndroidHttp(id, result).

    private val net = Executors.newFixedThreadPool(10)
    private val browserAgent by lazy { try { WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { "Mozilla/5.0" } }

    private fun answer(id: Int, r: JSONObject) = main.post {
        web.evaluateJavascript("window.AndroidHttp && window.AndroidHttp($id, $r)", null)
    }

    /** Opens a connection, following redirects by hand (Java won't follow http <-> https on its own). */
    private fun open(start: String, a: JSONObject, follow: Boolean): Pair<HttpURLConnection, String> {
        var url = start
        var method = a.optString("method", "GET")
        var body: String? = if (a.isNull("body")) null else a.optString("body")
        val timeout = a.optInt("timeout", 12000)
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.requestMethod = method
            c.setRequestProperty("User-Agent", browserAgent)
            c.setRequestProperty("Icy-MetaData", "0")
            // X-RW-NoAuth: the page asks as nobody (it retries this way when SoundCloud turns the sign-in down).
            val noAuth = a.optJSONObject("headers")?.has("X-RW-NoAuth") == true
            a.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> if (k != "X-RW-NoAuth") c.setRequestProperty(k, h.optString(k)) } }
            // Signed in to Bandcamp: its pages and collection answer as you (so bought songs play in full).
            val host = c.url.host
            if (host == "bandcamp.com" || host.endsWith(".bandcamp.com"))
                CookieManager.getInstance().getCookie("https://bandcamp.com")?.let { c.setRequestProperty("Cookie", it) }
            // Signed in to SoundCloud: its API answers as you (private likes and playlists, full Go+ songs).
            if (host == "api-v2.soundcloud.com" && !noAuth) soundcloudToken()?.let { c.setRequestProperty("Authorization", "OAuth $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", a.optString("type", "application/x-www-form-urlencoded"))
                c.outputStream.use { it.write(body!!.toByteArray()) }
            }
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            if (follow && code in 300..399 && loc != null) {
                url = URL(URL(url), loc).toString()
                c.disconnect()
                if (code != 307 && code != 308) { method = "GET"; body = null }
                return@repeat
            }
            return c to url
        }
        throw Exception("too many redirects")
    }

    private fun readUpTo(c: HttpURLConnection, max: Int): ByteArray {
        val stream = try { c.inputStream } catch (e: Exception) { c.errorStream } ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        stream.use {
            while (out.size() < max) {
                val n = it.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private inner class Bridge {
        private fun json(args: String) = try { JSONObject(args) } catch (e: Exception) { JSONObject() }

        /** A directory search or a playlist file: the whole answer as text (up to 3 MB). */
        @JavascriptInterface fun httpGet(id: Int, args: String) {
            val a = json(args)
            net.execute {
                val r = JSONObject()
                try {
                    val (c, finalUrl) = open(a.optString("url"), a, a.optBoolean("follow", true))
                    r.put("status", c.responseCode).put("url", finalUrl)
                        .put("location", c.getHeaderField("Location") ?: JSONObject.NULL)
                        .put("contentType", c.contentType ?: "")
                    r.put("text", if (c.responseCode in 300..399) "" else String(readUpTo(c, 3_000_000), Charsets.UTF_8))
                    c.disconnect()
                } catch (e: Exception) { r.put("error", e.javaClass.simpleName + ": " + (e.message ?: "")) }
                answer(id, r)
            }
        }

        /** The song playing on a station: asks the stream for its titles ("ICY" metadata) and reads the first one. */
        @JavascriptInterface fun icy(id: Int, args: String) {
            val a = json(args)
            net.execute {
                val r = JSONObject()
                try {
                    a.put("headers", JSONObject().put("Icy-MetaData", "1")).put("timeout", a.optInt("timeout", 9000))
                    val (c, _) = open(a.optString("url"), a, true)
                    val every = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull()
                    if (every == null || every <= 0 || every > 300_000) r.put("ok", false).put("why", "no titles")
                    else c.inputStream.use { ins ->
                        var skip = every.toLong()
                        while (skip > 0) { val n = ins.skip(skip); if (n <= 0) { if (ins.read() < 0) break; skip-- } else skip -= n }
                        val len = ins.read() * 16
                        val buf = ByteArray(maxOf(len, 0))
                        var got = 0
                        while (got < len) { val n = ins.read(buf, got, len - got); if (n < 0) break; got += n }
                        var text = String(buf, 0, got, Charsets.UTF_8)
                        if ('\uFFFD' in text) text = String(buf, 0, got, Charsets.ISO_8859_1)
                        val title = Regex("StreamTitle='(.*?)';").find(text)?.groupValues?.get(1)?.trim() ?: ""
                        r.put("ok", title.isNotEmpty()).put("title", title)
                    }
                    c.disconnect()
                } catch (e: Exception) { r.put("ok", false).put("error", e.javaClass.simpleName) }
                answer(id, r)
            }
        }

        /** Tests a stream: does it answer and start sending something? Only the first few KB are read. */
        @JavascriptInterface fun probe(id: Int, args: String) {
            val a = json(args)
            net.execute {
                val r = JSONObject()
                try {
                    val (c, finalUrl) = open(a.optString("url"), a.put("timeout", a.optInt("timeout", 7000)), true)
                    val code = c.responseCode
                    val bytes = if (code in 200..299) readUpTo(c, 4096) else ByteArray(0)
                    val type = (c.contentType ?: "").lowercase()
                    val texty = type.startsWith("text/") || "mpegurl" in type || "scpls" in type || "html" in type
                    r.put("ok", code in 200..299 && bytes.size > 200 || (texty && bytes.isNotEmpty() && code in 200..299))
                        .put("status", code).put("url", finalUrl).put("contentType", type)
                    if (texty || bytes.take(16).all { it in 9..126 }) r.put("head", String(bytes, Charsets.UTF_8))
                    c.disconnect()
                } catch (e: Exception) {
                    // Some old SHOUTcast servers answer "ICY 200 OK", which Java can't read but players can.
                    val icy = (e.message ?: "").contains("ICY", true)
                    r.put("ok", icy).put("url", a.optString("url")).put("contentType", if (icy) "audio/mpeg" else "")
                        .put("error", e.javaClass.simpleName)
                }
                answer(id, r)
            }
        }

        /** "Share" at the bottom of the page: the phone's share menu (a WebView has no navigator.share). */
        @JavascriptInterface fun shareText(title: String, text: String) = main.post {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, title))
        }

        /** "Back up" in Saved songs: write the file to the phone's Downloads folder. */
        @JavascriptInterface fun saveFile(name: String, text: String): Boolean {
            if (Build.VERSION.SDK_INT < 29) return false
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
            } catch (e: Exception) { false }
        }

        /** "Back up": what the device's list file in the remembered folder already holds, so new songs are added to it. */
        @JavascriptInterface fun readBackup(name: String): String {
            val tree = prefs.getString("folder", null)?.let { Uri.parse(it) } ?: return JSONObject().put("ok", false).toString()
            return try {
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                var file: Uri? = null
                contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    while (file == null && it.moveToNext())
                        if (it.getString(1) == name) file = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
                val text = file?.let { f -> contentResolver.openInputStream(f)?.use { it.readBytes().toString(Charsets.UTF_8) } } ?: ""
                JSONObject().put("ok", true).put("text", text).toString()
            } catch (e: Exception) { JSONObject().put("ok", false).toString() }
        }

        /** Picks the backup folder without writing, so the page can read the list file already there first; answers through onAndroidBackup ("picked"). */
        @JavascriptInterface fun pickBackupFolder(name: String) = main.post { askForFolder(name, null) }

        /** "Back up" with a folder: asks for one the first time (or when pickNew), then saves there; answers through onAndroidBackup. */
        @JavascriptInterface fun saveBackup(name: String, text: String, pickNew: Boolean) = main.post {
            val folder = prefs.getString("folder", null)
            if (folder != null && !pickNew) writeBackup(Uri.parse(folder), name, text, true)
            else askForFolder(name, text)
        }

        /** "Sign in to Bandcamp": Bandcamp's own login page in a full-screen window; closes once you're signed in. */
        @JavascriptInterface fun bandcampLogin() = main.post { showBandcampLogin() }

        /** "Sign out / forget": drops Bandcamp's sign-in from this phone. */
        @JavascriptInterface fun bandcampLogout() = main.post {
            val cm = CookieManager.getInstance()
            (cm.getCookie("https://bandcamp.com") ?: "").split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.forEach { n ->
                cm.setCookie("https://bandcamp.com", "$n=; Max-Age=0; Path=/")
                cm.setCookie("https://bandcamp.com", "$n=; Max-Age=0; Path=/; Domain=.bandcamp.com")
            }
            cm.flush()
        }

        /** "Sign in to SoundCloud": SoundCloud's own sign-in page in a full-screen window; closes once you're signed in. */
        @JavascriptInterface fun soundcloudLogin() = main.post { showSoundcloudLogin() }

        /** "Forget my SoundCloud": drops SoundCloud's sign-in from this phone. */
        @JavascriptInterface fun soundcloudLogout() = main.post {
            prefs.edit().remove("sc_token").apply()
            val cm = CookieManager.getInstance()
            // Every SoundCloud address and what its pages stored, so the next sign-in starts clean
            // (left-overs on secure./api-auth. made SoundCloud's sign-in page spin forever).
            val hosts = listOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "secure.soundcloud.com", "api-auth.soundcloud.com", "api-v2.soundcloud.com", "api.soundcloud.com")
            hosts.forEach { h ->
                (cm.getCookie("https://$h") ?: "").split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.forEach { n ->
                    cm.setCookie("https://$h", "$n=; Max-Age=0; Path=/")
                    cm.setCookie("https://$h", "$n=; Max-Age=0; Path=/; Domain=$h")
                    cm.setCookie("https://$h", "$n=; Max-Age=0; Path=/; Domain=.soundcloud.com")
                }
                android.webkit.WebStorage.getInstance().deleteOrigin("https://$h")
            }
            cm.flush()
        }

        /** The page is loaded: tell it where things stand, and rejoin a speaker that's still going. */
        @JavascriptInterface fun ready() = main.post {
            sendCastState()
            // Only rejoin a speaker that's fully connected; one still reconnecting reports in onSessionResumed.
            castContext.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let {
                attach(it)
                sendSession("SESSION_RESUMED", it)
                sendStatus()
            }
        }

        /** The cast button: Android's own speaker list, or the speaker's controls when connected. */
        @JavascriptInterface fun pickSpeaker(id: Int, args: String) = main.post {
            val selector = castContext.mergedSelector
            if (selector == null) { sendResult(id, false, "no_speakers"); return@post }
            if (session != null) {
                MediaRouteControllerDialog(this@MainActivity).apply {
                    setOnDismissListener { sendResult(id, false, "cancel") }
                }.show()
                return@post
            }
            pickId = id
            MediaRouteChooserDialog(this@MainActivity).apply {
                routeSelector = selector
                // The list closes as soon as a speaker is picked; only a close with nothing
                // connecting counts as cancelled.
                setOnDismissListener {
                    main.postDelayed({
                        val st = castContext.castState
                        if (pickId == id && st != CastState.CONNECTING && st != CastState.CONNECTED) {
                            sendResult(id, false, "cancel"); pickId = 0
                        }
                    }, 700)
                }
            }.show()
        }

        private fun mediaInfo(a: JSONObject): MediaInfo {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, a.optString("title"))
                a.optString("subtitle").takeIf { it.isNotEmpty() }?.let { putString(MediaMetadata.KEY_ARTIST, it) }
                a.optString("image").takeIf { it.isNotEmpty() }?.let { addImage(WebImage(Uri.parse(it))) }
            }
            return MediaInfo.Builder(a.optString("url"))
                .setStreamType(if (a.optString("streamType") == "BUFFERED") MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE)
                .setContentType(a.optString("contentType", "audio/mpeg"))
                .setMetadata(metadata)
                .build()
        }
        // Songs go in a speaker queue: the speaker moves on to the next song by itself, so the phone can sleep.
        private fun queueItem(a: JSONObject) = MediaQueueItem.Builder(mediaInfo(a)).setAutoplay(true).setPreloadTime(15.0).build()

        @JavascriptInterface fun load(id: Int, args: String) = main.post {
            val a = json(args)
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            val req = MediaLoadRequestData.Builder().setAutoplay(true).setCurrentTime((a.optDouble("currentTime", 0.0) * 1000).toLong())
            if (a.optBoolean("queue")) {
                req.setQueueData(MediaQueueData.Builder().setItems(listOf(queueItem(a))).setStartIndex(0)
                    .setRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF).build())
            } else req.setMediaInfo(mediaInfo(a))
            client.load(req.build()).setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage ?: "load_failed") }
        }

        // More songs at the end of the speaker's queue.
        @JavascriptInterface fun queueAdd(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            val list = json(args).optJSONArray("items") ?: run { sendResult(id, false, "no_items"); return@post }
            val items = (0 until list.length()).map { queueItem(list.getJSONObject(it)) }.toTypedArray()
            client.queueInsertItems(items, MediaQueueItem.INVALID_ITEM_ID, null)
                .setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage ?: "queue_failed") }
        }

        @JavascriptInterface fun pause(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            client.pause().setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage) }
        }

        @JavascriptInterface fun play(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            client.play().setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage) }
        }

        @JavascriptInterface fun stop(id: Int, args: String) = main.post {
            val client = session?.remoteMediaClient ?: run { sendResult(id, false, "no_session"); return@post }
            client.stop().setResultCallback { r -> sendResult(id, r.status.isSuccess, r.status.statusMessage) }
        }

        @JavascriptInterface fun setVolume(id: Int, args: String) = main.post {
            try { session?.volume = json(args).optDouble("level", 0.5); sendResult(id, true) }
            catch (e: Exception) { sendResult(id, false, e.message) }
        }

        @JavascriptInterface fun setMuted(id: Int, args: String) = main.post {
            try { session?.isMute = json(args).optBoolean("muted"); sendResult(id, true) }
            catch (e: Exception) { sendResult(id, false, e.message) }
        }

        @JavascriptInterface fun endSession(id: Int, args: String) = main.post {
            val stop = json(args).optBoolean("stopCasting", true)
            castContext.sessionManager.endCurrentSession(stop)
            if (stop) releaseRoute()
            sendResult(id, true)
        }
    }

    companion object {
        const val APP_URL = "https://cc666debug.github.io/radio-wizard/"
    }
}
