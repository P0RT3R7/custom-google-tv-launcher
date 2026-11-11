package com.custom.hisenselauncher.data

import com.custom.hisenselauncher.BuildConfig
import com.custom.hisenselauncher.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A title from Trakt (movie or an episode of a show). */
data class TraktItem(
    val type: String,          // "movie" | "series"
    val title: String,         // movie title or show title
    val episodeLabel: String?, // "S2 E5 · Episode name"
    val imdb: String?,
    val season: Int?,
    val episode: Int?,
    val progress: Float,       // 0..1, or -1
    val at: Long,
)

/**
 * Trakt.tv via the OAuth device flow (no password ever touches the TV):
 * the TV shows a short code, you approve it at trakt.tv/activate on your phone.
 * Needs trakt.clientId / trakt.clientSecret in launcher/trakt.properties at build time.
 */
class Trakt(private val prefs: Prefs) {

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int)
    enum class Poll { PENDING, SLOW_DOWN, SUCCESS, EXPIRED, DENIED, ERROR }

    /** Public Trakt apps have no secret; the device flow works with the client ID alone. */
    val configured: Boolean get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank()

    private fun JSONObject.withSecret(): JSONObject =
        if (BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()) put("client_secret", BuildConfig.TRAKT_CLIENT_SECRET) else this
    val linked: Boolean get() = configured && prefs.traktAccess != null

    fun startDeviceAuth(): DeviceCode {
        val r = Http.request("POST", "$API/oauth/device/code", JSONObject().put("client_id", BuildConfig.TRAKT_CLIENT_ID).toString(), baseHeaders())
        val o = JSONObject(r.body)
        return DeviceCode(o.getString("device_code"), o.getString("user_code"), o.getString("verification_url"),
            o.optInt("interval", 5), o.optInt("expires_in", 600))
    }

    fun pollToken(deviceCode: String): Poll {
        val body = JSONObject().put("code", deviceCode).put("client_id", BuildConfig.TRAKT_CLIENT_ID)
            .withSecret().toString()
        val r = runCatching { Http.request("POST", "$API/oauth/device/token", body, baseHeaders()) }.getOrNull() ?: return Poll.ERROR
        return when (r.code) {
            200 -> { saveTokens(JSONObject(r.body)); Poll.SUCCESS }
            400 -> Poll.PENDING
            429 -> Poll.SLOW_DOWN
            410, 404 -> Poll.EXPIRED
            418 -> Poll.DENIED
            else -> Poll.ERROR
        }
    }

    fun unlink() { prefs.traktAccess = null; prefs.traktRefresh = null; prefs.traktExpires = 0L }

    /** In-progress items (Continue Watching). */
    fun playback(): List<TraktItem> = authedArray("/sync/playback?limit=20").mapNotNull { parseItem(it, it.optDouble("progress", -1.0).toFloat() / 100f, "paused_at") }

    /** Recently watched (movies + episodes, newest first, one entry per show). */
    fun history(): List<TraktItem> = authedArray("/sync/history?limit=40")
        .mapNotNull { parseItem(it, -1f, "watched_at") }
        .distinctBy { it.imdb ?: it.title }

    /** Personalized recommendations from Trakt, movies + shows interleaved. */
    fun recommendations(): List<TraktItem> {
        val movies = authedArray("/recommendations/movies?limit=15&ignore_collected=true").mapNotNull { o ->
            ids(o)?.let { TraktItem("movie", o.optString("title"), null, it, null, null, -1f, 0L) }
        }
        val shows = authedArray("/recommendations/shows?limit=15&ignore_collected=true").mapNotNull { o ->
            ids(o)?.let { TraktItem("series", o.optString("title"), null, it, null, null, -1f, 0L) }
        }
        return movies.zip(shows).flatMap { listOf(it.first, it.second) } + movies.drop(shows.size) + shows.drop(movies.size)
    }

    // ------------------------------------------------------------------ internals

    private fun parseItem(o: JSONObject, progress: Float, timeKey: String): TraktItem? {
        val at = runCatching { Instant.parse(o.optString(timeKey)).toEpochMilli() }.getOrDefault(0L)
        return when (o.optString("type")) {
            "movie" -> o.optJSONObject("movie")?.let { m -> TraktItem("movie", m.optString("title"), null, ids(m), null, null, progress, at) }
            "episode" -> {
                val show = o.optJSONObject("show") ?: return null
                val ep = o.optJSONObject("episode") ?: return null
                val s = ep.optInt("season"); val e = ep.optInt("number")
                val label = "S$s E$e" + ep.optString("title").takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" }.orEmpty()
                TraktItem("series", show.optString("title"), label, ids(show), s, e, progress, at)
            }
            else -> null
        }
    }

    private fun ids(o: JSONObject): String? = o.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }

    private fun authedArray(path: String): List<JSONObject> {
        if (!linked) return emptyList()
        refreshIfNeeded()
        var r = Http.request("GET", API + path, null, authHeaders())
        if (r.code == 401 && refresh()) r = Http.request("GET", API + path, null, authHeaders())
        if (r.code !in 200..299) return emptyList()
        val a = JSONArray(r.body)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun refreshIfNeeded() { if (System.currentTimeMillis() > prefs.traktExpires - 24 * 3600_000L) refresh() }

    private fun refresh(): Boolean {
        val rt = prefs.traktRefresh ?: return false
        val body = JSONObject().put("refresh_token", rt).put("client_id", BuildConfig.TRAKT_CLIENT_ID)
            .withSecret().put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
            .put("grant_type", "refresh_token").toString()
        val r = runCatching { Http.request("POST", "$API/oauth/token", body, baseHeaders()) }.getOrNull() ?: return false
        if (r.code != 200) return false
        saveTokens(JSONObject(r.body))
        return true
    }

    private fun saveTokens(o: JSONObject) {
        prefs.traktAccess = o.getString("access_token")
        prefs.traktRefresh = o.optString("refresh_token")
        prefs.traktExpires = (o.optLong("created_at") + o.optLong("expires_in")) * 1000L
    }

    private fun baseHeaders() = mapOf(
        "Content-Type" to "application/json",
        "trakt-api-version" to "2",
        "trakt-api-key" to BuildConfig.TRAKT_CLIENT_ID,
    )

    private fun authHeaders() = baseHeaders() + ("Authorization" to "Bearer ${prefs.traktAccess}")

    companion object { private const val API = "https://api.trakt.tv" }
}
