package com.chaman.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Native ad and tracker blocker. Always on, no setting for it.
 *
 * Two layers:
 *  1. Network: every sub-resource request (scripts, images, iframes, XHR) is checked against a
 *     domain block list and a few URL patterns inside WebViewClient.shouldInterceptRequest.
 *  2. Cosmetic: a small stylesheet hides leftover ad containers that did not load from a blocked host.
 *
 * The block list lives in assets/blocklist.txt. It accepts plain domains or hosts-file lines
 * ("0.0.0.0 domain"). The CI build appends a large public list to it before compiling.
 */
object AdBlocker {

    @Volatile
    private var hosts: Set<String> = emptySet()

    val size: Int get() = hosts.size

    /** Only applied to third-party requests. Kept conservative to avoid breaking sites. */
    private val urlPatterns = listOf(
        "/pagead/",
        "/adsbygoogle.js",
        "://adservice.google.",
        "/adserver/",
        "/ads/prebid",
        "/prebid.js",
        "/gpt.js",
        "/show_ads"
    )

    fun init(context: Context) {
        val set = HashSet<String>(8192)
        try {
            context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { raw -> parseHost(raw)?.let { set.add(it) } }
            }
        } catch (e: Exception) {
            // Missing list: stay empty rather than crash.
        }
        hosts = set
    }

    private fun parseHost(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null
        val hash = line.indexOf('#')
        if (hash > 0) line = line.substring(0, hash).trim()
        val parts = line.split(Regex("\\s+"))
        val host = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]
        val h = host.lowercase()
        if (h == "localhost" || !h.contains('.')) return null
        return h
    }

    /** True if this sub-resource request should be blocked. Safe to call from any thread. */
    fun isBlocked(url: Uri, pageHost: String?): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (pageHost != null && isSameSite(host, pageHost.lowercase())) return false

        val list = hosts
        var h = host
        while (h.contains('.')) {
            if (list.contains(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }

        val full = url.toString().lowercase()
        return urlPatterns.any { full.contains(it) }
    }

    private fun isSameSite(a: String, b: String): Boolean =
        a == b || a.endsWith(".$b") || b.endsWith(".$a")

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    /** Injected after a page starts rendering and again when it finishes. */
    val COSMETIC_JS: String = """
(function(){
  if (window.__chamanAdCss) return;
  window.__chamanAdCss = true;
  var css = 'ins.adsbygoogle,.adsbygoogle,[id^="google_ads_"],[id^="div-gpt-ad"],[id^="gpt-ad"],' +
    '[class*="gpt-ad"],[data-ad-slot],[data-google-query-id],[data-ad-client],' +
    'iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],iframe[src*="adservice.google"],' +
    'iframe[id^="google_ads_iframe"],.ad-banner,.ad-container,.ad-slot,.ad-wrapper,.advert,.advertisement,' +
    '.sponsored-ad,.taboola,[id^="taboola-"],[id^="outbrain"],.OUTBRAIN,' +
    '[aria-label="Advertisement"],[aria-label="advertisement"]' +
    '{display:none !important;height:0 !important;min-height:0 !important;}';
  var s = document.createElement('style');
  s.setAttribute('data-chaman', 'adblock');
  s.textContent = css;
  (document.head || document.documentElement).appendChild(s);
})();
""".trimIndent()
}
