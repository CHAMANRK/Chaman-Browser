package com.chaman.browser

import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

class Tab(val webView: WebView) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 100

    /** Set when HTTPS-only mode rewrote an http:// address; used for the fallback page. */
    var upgradedFrom: String? = null

    /** Host of the page being shown; read from the network thread by the ad blocker. */
    @Volatile
    var pageHost: String? = null

    val blocked = AtomicInteger(0)

    val isHome: Boolean
        get() = url.isEmpty() || url.startsWith(HOME_PREFIX) || url == "about:blank"

    fun displayTitle(): String = title.ifBlank { if (isHome) "New tab" else url }

    fun barUrl(): String = if (isHome) "" else url

    companion object {
        const val HOME_PREFIX = "file:///android_asset/"
        const val HOME_URL = "file:///android_asset/home.html"
    }
}
