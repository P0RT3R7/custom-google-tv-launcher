package com.custom.hisenselauncher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.custom.hisenselauncher.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A movie or series in Stremio's Cinemeta catalog (IMDb ids). */
data class Title(
    val imdb: String,
    val type: String,            // "movie" | "series"
    val name: String,
    val year: String?,
    val genres: List<String>,
    val rating: Double?,
    val poster: String?,
    val background: String?,
    val description: String?,
    /** Series only, from meta(): every episode with its release time. */
    val episodes: List<Episode> = emptyList(),
)

data class Episode(val season: Int, val episode: Int, val name: String?, val released: Long, val thumbnail: String?)

/**
 * Free, key-less catalog (Stremio Cinemeta) + metahub artwork.
 * Responses are cached in memory for a few hours.
 */
object Cinemeta {
    private const val BASE = "https://v3-cinemeta.strem.io"
    private const val TTL = 6 * 60 * 60 * 1000L
    private class Entry(val at: Long, val value: Any)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun posterUrl(imdb: String) = "https://images.metahub.space/poster/medium/$imdb/img"
    fun backgroundUrl(imdb: String) = "https://images.metahub.space/background/medium/$imdb/img"

    fun search(type: String, query: String): List<Title> =
        catalog("$BASE/catalog/$type/top/search=${enc(query)}.json", type)

    fun top(type: String, genre: String? = null): List<Title> =
        catalog(if (genre == null) "$BASE/catalog/$type/top.json" else "$BASE/catalog/$type/top/genre=${enc(genre)}.json", type)

    fun meta(type: String, imdb: String): Title? = cached("meta:$type:$imdb") {
        val o = JSONObject(Http.get("$BASE/meta/$type/$imdb.json")).optJSONObject("meta")
        o?.let { m -> parse(m, type)?.copy(episodes = parseEpisodes(m)) } ?: NONE
    }.let { if (it === NONE) null else it as Title }

    private val NONE = Any()

    @Suppress("UNCHECKED_CAST")
    private fun catalog(url: String, type: String): List<Title> = cached(url) {
        val arr = JSONObject(Http.get(url)).optJSONArray("metas") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o, type) } }
    } as List<Title>

    private fun cached(key: String, load: () -> Any): Any {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < TTL }?.let { return it.value }
        val v = load()
        cache[key] = Entry(now, v)
        return v
    }

    private fun parse(o: JSONObject, fallbackType: String): Title? {
        val id = o.optString("imdb_id").ifBlank { o.optString("id") }
        if (!id.startsWith("tt")) return null
        val g = o.optJSONArray("genres") ?: o.optJSONArray("genre")
        return Title(
            imdb = id,
            type = o.optString("type").ifBlank { fallbackType },
            name = o.optString("name"),
            year = o.optString("releaseInfo").ifBlank { null },
            genres = g?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            rating = o.optString("imdbRating").toDoubleOrNull(),
            poster = o.optString("poster").ifBlank { posterUrl(id) },
            background = o.optString("background").ifBlank { backgroundUrl(id) },
            description = o.optString("description").ifBlank { null },
        )
    }

    private val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    private fun parseEpisodes(m: JSONObject): List<Episode> {
        val v = m.optJSONArray("videos") ?: return emptyList()
        return (0 until v.length()).mapNotNull { i ->
            val o = v.optJSONObject(i) ?: return@mapNotNull null
            val season = o.optInt("season", -1); val ep = o.optInt("episode", o.optInt("number", -1))
            val rel = o.optString("released").ifBlank { o.optString("firstAired") }.take(19)
            if (season <= 0 || ep <= 0 || rel.length < 10) return@mapNotNull null
            val at = runCatching { synchronized(iso) { iso.parse(if (rel.length == 10) rel + "T00:00:00" else rel)!!.time } }.getOrNull() ?: return@mapNotNull null
            Episode(season, ep, o.optString("name").ifBlank { o.optString("title") }.ifBlank { null }, at, o.optString("thumbnail").ifBlank { null })
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Opens a title in Nuvio (default) or Stremio using the stremio:// deep link both apps register. */
object TitleLauncher {
    const val NUVIO = "com.nuvio.app"
    const val STREMIO = "com.stremio.one"

    fun uri(type: String, imdb: String, season: Int? = null, episode: Int? = null): Uri {
        val t = if (type == "movie") "movie" else "series"
        return if (t == "series" && season != null && episode != null)
            Uri.parse("stremio:///detail/series/$imdb/$imdb:$season:$episode")
        else Uri.parse("stremio:///detail/$t/$imdb")
    }

    fun open(ctx: Context, type: String, imdb: String, season: Int? = null, episode: Int? = null, pkg: String = NUVIO): Boolean {
        val base = Intent(Intent.ACTION_VIEW, uri(type, imdb, season, episode)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (candidate in listOf(pkg, NUVIO, STREMIO)) {
            try { ctx.startActivity(Intent(base).setPackage(candidate)); return true } catch (_: Exception) { }
        }
        return false
    }
}
