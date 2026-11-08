package com.chaman.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Entry(val title: String, val url: String, val time: Long)

/** Tiny JSON-file storage for bookmarks and history. */
class Store(context: Context) {

    private val dir: File = context.applicationContext.filesDir
    private val lock = Any()

    private fun read(name: String): MutableList<Entry> {
        val f = File(dir, name)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optString("t"), o.optString("u"), o.optLong("d"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(name: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        File(dir, name).writeText(arr.toString())
    }

    // ---- Bookmarks ----

    fun bookmarks(): List<Entry> = synchronized(lock) { read(BOOKMARKS) }

    fun isBookmarked(url: String): Boolean = synchronized(lock) { read(BOOKMARKS).any { it.url == url } }

    /** Returns true if the page is bookmarked after the call. */
    fun toggleBookmark(title: String, url: String): Boolean = synchronized(lock) {
        val list = read(BOOKMARKS)
        val existing = list.indexOfFirst { it.url == url }
        if (existing >= 0) {
            list.removeAt(existing)
            write(BOOKMARKS, list)
            false
        } else {
            list.add(0, Entry(title, url, System.currentTimeMillis()))
            write(BOOKMARKS, list)
            true
        }
    }

    fun removeBookmark(url: String) = synchronized(lock) {
        val list = read(BOOKMARKS)
        list.removeAll { it.url == url }
        write(BOOKMARKS, list)
    }

    // ---- History ----

    fun history(): List<Entry> = synchronized(lock) { read(HISTORY) }

    fun addHistory(title: String, url: String) = synchronized(lock) {
        val list = read(HISTORY)
        val now = System.currentTimeMillis()
        if (list.isNotEmpty() && list[0].url == url) {
            list[0] = Entry(title.ifBlank { list[0].title }, url, now)
        } else {
            list.add(0, Entry(title, url, now))
        }
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        write(HISTORY, list)
    }

    fun removeHistory(entry: Entry) = synchronized(lock) {
        val list = read(HISTORY)
        list.removeAll { it.url == entry.url && it.time == entry.time }
        write(HISTORY, list)
    }

    fun clearHistory() = synchronized(lock) { write(HISTORY, emptyList()) }

    companion object {
        private const val BOOKMARKS = "bookmarks.json"
        private const val HISTORY = "history.json"
        private const val MAX_HISTORY = 1000
    }
}
