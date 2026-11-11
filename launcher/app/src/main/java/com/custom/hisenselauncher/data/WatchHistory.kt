package com.custom.hisenselauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One thing you watched, recorded locally from media sessions (or merged in from Trakt). */
data class HistoryItem(
    val key: String,              // pkg + normalized title
    val title: String,
    val subtitle: String?,
    val pkg: String,
    val artPath: String?,         // local artwork file captured from the media session
    val imdb: String?,            // resolved via Cinemeta ("-" = looked up, no match)
    val type: String?,            // movie | series
    val lastSeen: Long,
    val position: Long,
    val duration: Long,
    /** true = the app played something but didn't say what (Hulu, Netflix): shown as a Resume card. */
    val appLevel: Boolean = false,
    /** The app's own id for the item (YouTube: the video id), from the media session. */
    val mediaId: String? = null,
    /** Artwork URL from the media session (used when no bitmap was shared). */
    val imageUrl: String? = null,
    /** session = from the app's media session; reader = read off the player screen (TitleReaderService). */
    val source: String = "session",
) {
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else -1f
    val resolvedImdb: String? get() = imdb?.takeIf { it.startsWith("tt") }
}

/** Small JSON-file store in the app's private files dir. Thread-safe. */
class WatchHistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    val artDir: File = File(ctx.filesDir, "art").apply { mkdirs() }

    @Synchronized
    fun all(): List<HistoryItem> = read().sortedByDescending { it.lastSeen }

    @Synchronized
    fun upsert(item: HistoryItem) {
        val list = read().filter { it.key != item.key }.toMutableList()
        val old = read().firstOrNull { it.key == item.key }
        list += item.copy(
            imdb = item.imdb ?: old?.imdb,
            type = item.type ?: old?.type,
            artPath = item.artPath ?: old?.artPath,
            mediaId = item.mediaId ?: old?.mediaId,
            imageUrl = item.imageUrl ?: old?.imageUrl,
        )
        write(list.sortedByDescending { it.lastSeen }.take(MAX))
    }

    @Synchronized
    fun setResolution(key: String, imdb: String, type: String?) {
        write(read().map { if (it.key == key) it.copy(imdb = imdb, type = type ?: it.type) else it })
    }

    @Synchronized
    fun remove(key: String) {
        read().firstOrNull { it.key == key }?.artPath?.let { File(it).delete() }
        write(read().filter { it.key != key })
    }

    private fun read(): List<HistoryItem> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            HistoryItem(
                key = o.getString("key"), title = o.getString("title"), subtitle = o.optString("subtitle").ifBlank { null },
                pkg = o.getString("pkg"), artPath = o.optString("art").ifBlank { null },
                imdb = o.optString("imdb").ifBlank { null }, type = o.optString("type").ifBlank { null },
                lastSeen = o.optLong("seen"), position = o.optLong("pos"), duration = o.optLong("dur"),
                appLevel = o.optBoolean("app", false),
                mediaId = o.optString("mid").ifBlank { null }, imageUrl = o.optString("img").ifBlank { null },
                source = o.optString("src").ifBlank { "session" },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<HistoryItem>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().apply {
                put("key", it.key); put("title", it.title); put("subtitle", it.subtitle ?: "")
                put("pkg", it.pkg); put("art", it.artPath ?: ""); put("imdb", it.imdb ?: ""); put("type", it.type ?: "")
                put("seen", it.lastSeen); put("pos", it.position); put("dur", it.duration); put("app", it.appLevel)
                put("mid", it.mediaId ?: ""); put("img", it.imageUrl ?: ""); put("src", it.source)
            })
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val MAX = 80
        fun keyFor(pkg: String, title: String) = pkg + "|" + normalize(title)
        fun appKey(pkg: String) = "$pkg|@app"
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
