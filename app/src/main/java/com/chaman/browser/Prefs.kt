package com.chaman.browser

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager

/** Typed access to settings. Every control defaults to OFF; only the ad blocker is always on. */
class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val privateMode: Boolean get() = sp.getBoolean(PRIVATE_MODE, false)
    val blockCookies: Boolean get() = sp.getBoolean(BLOCK_COOKIES, false)
    val blockThirdPartyCookies: Boolean get() = sp.getBoolean(BLOCK_3P_COOKIES, false)
    val safeBrowsing: Boolean get() = sp.getBoolean(SAFE_BROWSING, false)
    val httpsOnly: Boolean get() = sp.getBoolean(HTTPS_ONLY, false)
    val blockJs: Boolean get() = sp.getBoolean(BLOCK_JS, false)
    val blockImages: Boolean get() = sp.getBoolean(BLOCK_IMAGES, false)
    val allowPopups: Boolean get() = sp.getBoolean(ALLOW_POPUPS, false)
    val darkMode: Boolean get() = sp.getBoolean(DARK_MODE, false)
    val desktopSite: Boolean get() = sp.getBoolean(DESKTOP_SITE, false)
    val searchEngine: String get() = sp.getString(SEARCH_ENGINE, "duckduckgo") ?: "duckduckgo"
    val homePage: String get() = (sp.getString(HOME_PAGE, "") ?: "").trim()

    fun searchUrl(query: String): String {
        val q = Uri.encode(query)
        return when (searchEngine) {
            "google" -> "https://www.google.com/search?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            "brave" -> "https://search.brave.com/search?q=$q"
            "startpage" -> "https://www.startpage.com/do/search?q=$q"
            else -> "https://duckduckgo.com/?q=$q"
        }
    }

    /** Changes when a setting that needs a page reload changes. */
    fun signature(): String = listOf(
        blockJs, blockImages, desktopSite, blockCookies,
        blockThirdPartyCookies, safeBrowsing, allowPopups
    ).joinToString("|")

    companion object {
        const val PRIVATE_MODE = "pref_private_mode"
        const val BLOCK_COOKIES = "pref_block_cookies"
        const val BLOCK_3P_COOKIES = "pref_block_third_party_cookies"
        const val SAFE_BROWSING = "pref_safe_browsing"
        const val HTTPS_ONLY = "pref_https_only"
        const val BLOCK_JS = "pref_block_js"
        const val BLOCK_IMAGES = "pref_block_images"
        const val ALLOW_POPUPS = "pref_allow_popups"
        const val DARK_MODE = "pref_dark_mode"
        const val DESKTOP_SITE = "pref_desktop_site"
        const val SEARCH_ENGINE = "pref_search_engine"
        const val HOME_PAGE = "pref_home_page"
    }
}
