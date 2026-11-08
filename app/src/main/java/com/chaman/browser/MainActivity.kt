package com.chaman.browser

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: Store
    private lateinit var defaultUserAgent: String

    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tabCountView: TextView
    private lateinit var adCountView: TextView
    private lateinit var btnReload: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private val httpAllowedHosts = mutableSetOf<String>()
    private var lastSignature = ""

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        store = Store(this)
        defaultUserAgent = WebSettings.getDefaultUserAgent(this)
        lastSignature = prefs.signature()

        bindViews()
        findViewById<View>(R.id.main_root).requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        val saved = savedInstanceState?.getStringArrayList(STATE_TABS)
        if (!saved.isNullOrEmpty()) {
            saved.forEach { newTab(it, activate = false) }
            val idx = (savedInstanceState?.getInt(STATE_CURRENT, 0) ?: 0).coerceIn(0, tabs.size - 1)
            switchTo(tabs[idx])
        } else {
            newTab(intent.data?.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { newTab(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_TABS,
            ArrayList(tabs.map { it.url.ifEmpty { Tab.HOME_URL } })
        )
        outState.putInt(STATE_CURRENT, tabs.indexOf(current))
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { applySettings(it.webView) }
        current?.webView?.onResume()

        val sig = prefs.signature()
        if (sig != lastSignature) {
            lastSignature = sig
            current?.webView?.reload()
        }

        if (prefs.privateMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && prefs.privateMode) wipeBrowsingData(includeHistory = false)
        tabs.forEach {
            (it.webView.parent as? ViewGroup)?.removeView(it.webView)
            it.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun handleBack() {
        val tab = current
        when {
            customView != null -> hideCustomView()
            urlBar.hasFocus() -> {
                urlBar.clearFocus()
                hideKeyboard(urlBar)
            }
            tab != null && tab.webView.canGoBack() -> tab.webView.goBack()
            tab != null && tabs.size > 1 -> closeTab(tab)
            else -> finish()
        }
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        urlBar = findViewById(R.id.url_bar)
        progressBar = findViewById(R.id.progress)
        webContainer = findViewById(R.id.web_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        tabCountView = findViewById(R.id.tab_count)
        adCountView = findViewById(R.id.ad_count)
        btnReload = findViewById(R.id.btn_reload)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)

        urlBar.setOnEditorActionListener { v, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                current?.let { openUrl(it, resolveInput(v.text.toString())) }
                hideKeyboard(v)
                v.clearFocus()
                true
            } else {
                false
            }
        }
        urlBar.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshUi() }

        btnReload.setOnClickListener {
            current?.let {
                if (it.progress < 100) {
                    it.webView.stopLoading()
                    it.progress = 100
                    refreshUi()
                } else {
                    it.webView.reload()
                }
            }
        }
        btnBack.setOnClickListener { current?.webView?.takeIf { w -> w.canGoBack() }?.goBack() }
        btnForward.setOnClickListener { current?.webView?.takeIf { w -> w.canGoForward() }?.goForward() }
        findViewById<View>(R.id.btn_home).setOnClickListener { current?.let { openUrl(it, homeUrl()) } }
        findViewById<View>(R.id.btn_tabs).setOnClickListener { showTabs() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.ad_shield).setOnClickListener {
            toast(getString(R.string.ads_blocked_on_page, current?.blocked?.get() ?: 0))
        }
    }

    private fun refreshUi() {
        val tab = current ?: return
        if (!urlBar.hasFocus()) urlBar.setText(tab.barUrl())
        btnBack.alpha = if (tab.webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (tab.webView.canGoForward()) 1f else 0.4f
        adCountView.text = tab.blocked.get().toString()
        updateProgress(tab.progress)
        updateTabCount()
    }

    private fun updateProgress(p: Int) {
        progressBar.progress = p
        progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        btnReload.setImageResource(if (p in 0..99) R.drawable.ic_close else R.drawable.ic_refresh)
    }

    private fun updateTabCount() {
        tabCountView.text = tabs.size.toString()
    }

    // ------------------------------------------------------------------ tabs

    private fun newTab(url: String? = null, activate: Boolean = true, loadNow: Boolean = true): Tab {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val tab = Tab(wv)
        tabs.add(tab)
        setupWebView(tab)
        if (activate) switchTo(tab)
        if (loadNow) openUrl(tab, url ?: homeUrl())
        updateTabCount()
        return tab
    }

    private fun switchTo(tab: Tab) {
        current?.let { old ->
            if (old !== tab) {
                webContainer.removeView(old.webView)
                old.webView.onPause()
            }
        }
        current = tab
        if (tab.webView.parent == null) webContainer.addView(tab.webView)
        tab.webView.onResume()
        refreshUi()
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        if (tab === current) {
            webContainer.removeView(tab.webView)
            current = null
        }
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            newTab()
        } else if (current == null) {
            switchTo(tabs[minOf(idx, tabs.size - 1)])
        }
        updateTabCount()
    }

    private fun showTabs() {
        val view = layoutInflater.inflate(R.layout.dialog_tabs, null)
        val list = view.findViewById<ListView>(R.id.tab_list)
        val newTabButton = view.findViewById<Button>(R.id.btn_new_tab)
        var dialog: AlertDialog? = null

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = tabs.size
            override fun getItem(position: Int): Tab = tabs[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_tab, parent, false)
                val tab = tabs[position]
                val title = row.findViewById<TextView>(R.id.tab_title)
                title.text = tab.displayTitle()
                title.setTypeface(null, if (tab === current) Typeface.BOLD else Typeface.NORMAL)
                row.findViewById<TextView>(R.id.tab_url).text = tab.url.ifBlank { "about:blank" }
                row.findViewById<ImageButton>(R.id.tab_close).setOnClickListener {
                    closeTab(tab)
                    notifyDataSetChanged()
                }
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            switchTo(tabs[position])
            dialog?.dismiss()
        }
        newTabButton.setOnClickListener {
            newTab()
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tabs)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog?.show()
    }

    // ------------------------------------------------------------------ navigation

    private fun homeUrl(): String {
        val h = prefs.homePage
        return if (h.isEmpty()) Tab.HOME_URL else resolveInput(h)
    }

    private fun resolveInput(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return homeUrl()
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith(Tab.HOME_PREFIX) || lower == "about:blank"
        ) return t
        if (!t.contains(' ')) {
            if (LOCAL_REGEX.matches(t)) return "http://$t"
            if (t.contains('.') && !t.endsWith('.')) return "https://$t"
        }
        return prefs.searchUrl(t)
    }

    /** HTTPS-only mode: rewrite http:// to https:// unless the host was allowed or is local. */
    private fun maybeUpgrade(tab: Tab, url: String): String {
        if (!prefs.httpsOnly || !url.startsWith("http://", ignoreCase = true)) return url
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        if (host in httpAllowedHosts || uri.port != -1 || LOCAL_REGEX.matches(host)) return url
        tab.upgradedFrom = url
        return "https://" + url.substring("http://".length)
    }

    private fun openUrl(tab: Tab, url: String) {
        tab.upgradedFrom = null
        tab.webView.loadUrl(maybeUpgrade(tab, url))
    }

    private fun handleInternal(tab: Tab, uri: Uri) {
        when (uri.host) {
            "search" -> {
                val q = uri.getQueryParameter("q").orEmpty()
                openUrl(tab, if (q.isBlank()) homeUrl() else prefs.searchUrl(q))
            }
            "http" -> {
                val original = uri.getQueryParameter("u").orEmpty()
                if (original.startsWith("http://")) {
                    Uri.parse(original).host?.let { httpAllowedHosts.add(it) }
                    openUrl(tab, original)
                }
            }
            else -> openUrl(tab, homeUrl())
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast(R.string.no_app_for_link)
        }
    }

    // ------------------------------------------------------------------ WebView

    private fun setupWebView(tab: Tab) {
        val wv = tab.webView
        applySettings(wv)

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val req = request ?: return false
                val uri = req.url
                return when (uri.scheme?.lowercase()) {
                    "chaman" -> {
                        handleInternal(tab, uri)
                        true
                    }
                    "http" -> {
                        val target = uri.toString()
                        val upgraded = if (req.isForMainFrame) maybeUpgrade(tab, target) else target
                        if (upgraded != target) {
                            view?.loadUrl(upgraded)
                            true
                        } else {
                            false
                        }
                    }
                    "https", "about", "data", "blob", "javascript" -> false
                    "file" -> !uri.toString().startsWith(Tab.HOME_PREFIX)
                    "intent" -> true
                    else -> {
                        // Open other apps (tel:, mailto:, ...) only after a real tap.
                        if (req.hasGesture()) openExternal(uri)
                        true
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                if (!req.isForMainFrame && AdBlocker.isBlocked(req.url, tab.pageHost)) {
                    tab.blocked.incrementAndGet()
                    return AdBlocker.emptyResponse()
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.url = url.orEmpty()
                tab.pageHost = Uri.parse(url.orEmpty()).host
                tab.blocked.set(0)
                tab.progress = 0
                tab.title = ""
                if (tab === current) refreshUi()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                view?.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.progress = 100
                if (!url.isNullOrEmpty()) tab.url = url
                view?.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
                val pageTitle = view?.title.orEmpty()
                if (pageTitle.isNotBlank()) tab.title = pageTitle
                if (!prefs.privateMode && !tab.isHome && tab.url.startsWith("http")) {
                    store.addHistory(tab.displayTitle(), tab.url)
                }
                if (tab === current) refreshUi()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (!url.isNullOrEmpty()) tab.url = url
                if (tab === current) refreshUi()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request == null || error == null || !request.isForMainFrame) return
                showErrorPage(tab, request.url.toString(), error.description?.toString().orEmpty())
            }
        }

        wv.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
                if (tab === current) {
                    updateProgress(newProgress)
                    adCountView.text = tab.blocked.get().toString()
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(fileChooserParams!!.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val popup = newTab(null, activate = true, loadNow = false)
                transport.webView = popup.webView
                msg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startDownload(url, userAgent, contentDisposition, mimetype)
        }

        wv.setOnLongClickListener { handleLongPress(wv) }
    }

    @Suppress("DEPRECATION")
    private fun applyDark(wv: WebView, enable: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, enable)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                wv.settings,
                if (enable) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun applySettings(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = !prefs.blockJs
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = prefs.allowPopups
        s.setSupportMultipleWindows(prefs.allowPopups)
        s.loadsImagesAutomatically = !prefs.blockImages
        s.blockNetworkImage = prefs.blockImages
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = if (prefs.privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        s.userAgentString = if (prefs.desktopSite) desktopUserAgent() else defaultUserAgent
        s.safeBrowsingEnabled = prefs.safeBrowsing

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(!prefs.blockCookies)
        cookies.setAcceptThirdPartyCookies(wv, !prefs.blockCookies && !prefs.blockThirdPartyCookies)

        applyDark(wv, prefs.darkMode)
    }

    private fun desktopUserAgent(): String {
        val chrome = Regex("Chrome/([\\d.]+)").find(defaultUserAgent)?.groupValues?.get(1) ?: "124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chrome Safari/537.36"
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showErrorPage(tab: Tab, failingUrl: String, reason: String) {
        val safeUrl = TextUtils.htmlEncode(failingUrl)
        val safeReason = TextUtils.htmlEncode(reason)
        val original = tab.upgradedFrom
        val body = if (original != null) {
            "<p>HTTPS-only mode is on and this site does not offer a secure connection.</p>" +
                "<a class=\"btn\" href=\"chaman://http?u=${Uri.encode(original)}\">Continue with HTTP</a>"
        } else {
            "<a class=\"btn\" href=\"$safeUrl\">Try again</a>"
        }
        val html = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{color-scheme:light dark}
body{font-family:sans-serif;margin:0;padding:32px 24px;line-height:1.5;background:#f6faf6;color:#1b2a1b}
@media (prefers-color-scheme: dark){body{background:#101510;color:#e6efe6}}
h1{font-size:22px;margin:0 0 8px}
.url{word-break:break-all;opacity:.7;font-size:14px}
.btn{display:inline-block;margin-top:16px;padding:10px 20px;border-radius:20px;background:#2e7d32;color:#fff;text-decoration:none}
</style></head><body>
<h1>Can't open this page</h1>
<p class="url">$safeUrl</p>
<p>$safeReason</p>
$body
</body></html>
""".trimIndent()
        tab.webView.loadDataWithBaseURL(failingUrl, html, "text/html", "utf-8", failingUrl)
    }

    // ------------------------------------------------------------------ downloads, long-press

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast(R.string.download_unsupported)
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            toast(R.string.grant_storage_retry)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(getString(R.string.downloading, fileName))
        } catch (e: Exception) {
            toast(R.string.download_unsupported)
        }
    }

    private fun handleLongPress(wv: WebView): Boolean {
        val result = wv.hitTestResult
        val extra = result.extra ?: return false
        val type = result.type
        if (type != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
            type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE &&
            type != WebView.HitTestResult.IMAGE_TYPE
        ) return false

        val isImage = type == WebView.HitTestResult.IMAGE_TYPE
        val options = arrayOf(
            getString(if (isImage) R.string.open_image_new_tab else R.string.open_new_tab),
            getString(R.string.copy_link),
            getString(R.string.share)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(extra.take(80))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> newTab(extra)
                    1 -> copyToClipboard(extra)
                    2 -> shareText(extra)
                    else -> Unit
                }
            }
            .show()
        return true
    }

    // ------------------------------------------------------------------ menu and lists

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        val url = current?.url.orEmpty()
        val isWeb = url.startsWith("http")
        val bookmarked = isWeb && store.isBookmarked(url)
        popup.menu.findItem(R.id.menu_bookmark).apply {
            setTitle(if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark)
            isEnabled = isWeb
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> {
                    newTab()
                    true
                }
                R.id.menu_bookmark -> {
                    current?.let { t ->
                        val now = store.toggleBookmark(t.displayTitle(), t.url)
                        toast(if (now) R.string.bookmark_added else R.string.bookmark_removed)
                    }
                    true
                }
                R.id.menu_bookmarks -> {
                    showEntries(
                        getString(R.string.bookmarks_title),
                        store.bookmarks(),
                        onRemove = { store.removeBookmark(it.url) },
                        onClearAll = null
                    )
                    true
                }
                R.id.menu_history -> {
                    showEntries(
                        getString(R.string.history_title),
                        store.history(),
                        onRemove = { store.removeHistory(it) },
                        onClearAll = { store.clearHistory() }
                    )
                    true
                }
                R.id.menu_downloads -> {
                    try {
                        startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                    } catch (e: Exception) {
                        toast(R.string.no_app_for_link)
                    }
                    true
                }
                R.id.menu_share -> {
                    if (isWeb) shareText(url)
                    true
                }
                R.id.menu_clear -> {
                    confirmClearData()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showEntries(
        title: String,
        entries: List<Entry>,
        onRemove: (Entry) -> Unit,
        onClearAll: (() -> Unit)?
    ) {
        if (entries.isEmpty()) {
            toast(R.string.nothing_here)
            return
        }
        var dialog: AlertDialog? = null
        val adapter = object : ArrayAdapter<Entry>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, entries.toMutableList()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position)
                val line1 = v.findViewById<TextView>(android.R.id.text1)
                val line2 = v.findViewById<TextView>(android.R.id.text2)
                line1.text = e?.title.orEmpty().ifBlank { e?.url.orEmpty() }
                line1.maxLines = 1
                line1.ellipsize = TextUtils.TruncateAt.END
                line2.text = e?.url.orEmpty()
                line2.maxLines = 1
                line2.ellipsize = TextUtils.TruncateAt.END
                return v
            }
        }
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { e -> current?.let { openUrl(it, e.url) } }
            dialog?.dismiss()
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { e ->
                onRemove(e)
                adapter.remove(e)
            }
            true
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(list)
            .setNegativeButton(R.string.close, null)
        if (onClearAll != null) {
            builder.setNeutralButton(R.string.clear_all) { _, _ -> onClearAll() }
        }
        dialog = builder.create()
        dialog?.show()
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_data_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                wipeBrowsingData(includeHistory = true)
                toast(R.string.clear_data_done)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun wipeBrowsingData(includeHistory: Boolean) {
        current?.webView?.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        if (includeHistory) store.clearHistory()
    }

    // ------------------------------------------------------------------ small helpers

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", text))
        toast(R.string.link_copied)
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        toast(getString(resId))
    }

    companion object {
        private const val STATE_TABS = "state_tabs"
        private const val STATE_CURRENT = "state_current"
        private val LOCAL_REGEX = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")
    }
}
