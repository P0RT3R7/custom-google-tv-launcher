package com.custom.hisenselauncher.data

import android.content.Context
import android.content.Intent

/** Anything shown as a media card: a title, an in-progress episode, a history entry. */
data class PosterItem(
    val title: String,
    val subtitle: String?,
    val image: String?,          // URL or absolute file path
    val progress: Float = -1f,
    val imdb: String? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val pkg: String? = null,
    val intent: Intent? = null,
    val historyKey: String? = null,
    val rating: Double? = null,
)

class FeedRow(val title: String, val items: List<PosterItem>, val landscape: Boolean = false)

class Feed(
    val continueWatching: List<PosterItem>,
    val recentlyWatched: List<PosterItem>,
    val suggestions: List<FeedRow>,
)

/**
 * Builds all title rows for the home screen:
 *  Continue Watching  = TV provider Watch Next + Trakt playback + local in-progress
 *  Recently Watched   = local media-session history + Trakt history
 *  Because you watched X / Picked for you = genre matching on Cinemeta (no keys)
 *  Recommended on Trakt = Trakt's personalized picks (when linked)
 * Blocking; run on a background thread.
 */
class HomeFeed(private val ctx: Context, prefs: Prefs) {

    private val store = WatchHistoryStore(ctx)
    private val trakt = Trakt(prefs)
    private val watchNext = WatchNextRepository(ctx)

    private class Seed(val imdb: String, val type: String, val name: String)

    fun build(): Feed {
        resolveLocalHistory()
        val local = store.all()
        val traktPlayback = safe { trakt.playback() }
        val traktHistory = safe { trakt.history() }

        // --- Continue Watching: provider rows first, then everything else newest first
        val now = System.currentTimeMillis()
        val cont = ArrayList<PosterItem>()
        watchNext.load().items.forEach { c ->
            cont += PosterItem(c.title, c.subtitle, c.posterUri?.toString(), c.progress, pkg = c.pkg, intent = c.intent)
        }
        val timed = ArrayList<Pair<Long, PosterItem>>()
        traktPlayback.filter { it.imdb != null }.forEach { timed += it.at to it.toPoster(landscape = true) }
        local.filter { !it.appLevel && it.progress in 0.03f..0.92f && now - it.lastSeen < 21 * 86_400_000L }
            .forEach { timed += it.lastSeen to it.toPoster() }
        // apps that hide titles (Hulu, Netflix): one "Resume" card per app, last 3 days
        local.filter { it.appLevel && now - it.lastSeen < 3 * 86_400_000L }.forEach { h ->
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(h.lastSeen, now, android.text.format.DateUtils.MINUTE_IN_MILLIS)
            timed += h.lastSeen to PosterItem(
                title = "Resume ${h.title}", subtitle = "Last watched $ago", image = null, pkg = h.pkg, historyKey = h.key,
            )
        }
        cont += timed.sortedByDescending { it.first }.map { it.second }

        // --- Recently Watched (titles only; merge by imdb / normalized title, newest first)
        val merged = ArrayList<Pair<Long, PosterItem>>()
        local.filter { !it.appLevel }.forEach { merged += it.lastSeen to it.toPoster() }
        traktHistory.forEach { merged += it.at to it.toPoster(landscape = true) }
        val recent = merged.sortedByDescending { it.first }.map { it.second }
            .distinctBy { it.imdb ?: WatchHistoryStore.normalize(it.title) }.take(20)

        // --- Seeds for suggestions (most recent first)
        val seeds = LinkedHashMap<String, Seed>()
        val recentEvents = (local.mapNotNull { h -> h.resolvedImdb?.let { Triple(h.lastSeen, it, h.type ?: "series") to h.title } } +
            traktHistory.mapNotNull { t -> t.imdb?.let { Triple(t.at, it, t.type) to t.title } })
            .sortedByDescending { it.first.first }
        recentEvents.forEach { (k, name) -> seeds.getOrPut(k.second) { Seed(k.second, k.third, name) } }
        val watched = seeds.keys + cont.mapNotNull { it.imdb }

        val rows = ArrayList<FeedRow>()
        val used = HashSet<String>(watched)

        // Because you watched X (two most recent seeds)
        seeds.values.take(2).forEach { seed ->
            val items = safe { becauseYouWatched(seed, used) }
            if (items.size >= 4) {
                rows += FeedRow("Because you watched ${seed.name}", items)
                used += items.mapNotNull { it.imdb }
            }
        }
        // Picked for you: weighted genre taste across all seeds
        if (seeds.size >= 3) {
            val items = safe { pickedForYou(seeds.values.take(12), used) }
            if (items.size >= 4) { rows += FeedRow("Picked for you", items); used += items.mapNotNull { it.imdb } }
        }
        // Trakt recommendations
        val recs = safe { trakt.recommendations() }.filter { it.imdb != null && it.imdb !in used }.map { it.toPoster(landscape = false) }
        if (recs.isNotEmpty()) rows += FeedRow("Recommended on Trakt", recs)
        // Nothing known yet -> popular
        if (seeds.isEmpty()) {
            safe { Cinemeta.top("movie").take(20).map { it.toPoster() } }.takeIf { it.isNotEmpty() }?.let { rows += FeedRow("Popular movies", it) }
            safe { Cinemeta.top("series").take(20).map { it.toPoster() } }.takeIf { it.isNotEmpty() }?.let { rows += FeedRow("Popular shows", it) }
        }

        return Feed(cont.distinctBy { it.imdb ?: (it.pkg + WatchHistoryStore.normalize(it.title)) }.take(20), recent, rows)
    }

    private fun becauseYouWatched(seed: Seed, exclude: Set<String>): List<PosterItem> {
        val meta = Cinemeta.meta(seed.type, seed.imdb) ?: return emptyList()
        val genres = meta.genres.take(3)
        if (genres.isEmpty()) return emptyList()
        val pool = genres.take(2).flatMap { g -> Cinemeta.top(seed.type, g) }.distinctBy { it.imdb }
        return pool.filter { it.imdb !in exclude && it.imdb != seed.imdb }
            .sortedByDescending { t -> t.genres.count { it in genres } * 2.0 + (t.rating ?: 6.0) / 2.0 }
            .take(15).map { it.toPoster() }
    }

    private fun pickedForYou(seeds: List<Seed>, exclude: Set<String>): List<PosterItem> {
        val weights = HashMap<String, Double>()
        val typeVotes = HashMap<String, Int>()
        seeds.forEachIndexed { i, s ->
            val m = Cinemeta.meta(s.type, s.imdb) ?: return@forEachIndexed
            m.genres.forEach { g -> weights[g] = (weights[g] ?: 0.0) + 1.0 / (i + 1) }
            typeVotes[s.type] = (typeVotes[s.type] ?: 0) + 1
        }
        val topGenres = weights.entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (topGenres.isEmpty()) return emptyList()
        val types = typeVotes.entries.sortedByDescending { it.value }.map { it.key }.ifEmpty { listOf("movie", "series") }
        val pool = types.take(2).flatMap { type -> topGenres.flatMap { g -> Cinemeta.top(type, g) } }.distinctBy { it.imdb }
        return pool.filter { it.imdb !in exclude }
            .sortedByDescending { t -> t.genres.sumOf { weights[it] ?: 0.0 } + (t.rating ?: 6.0) / 4.0 }
            .take(20).map { it.toPoster() }
    }

    /** Matches up to 6 unresolved local titles to Cinemeta ids per build (cached per item). */
    private fun resolveLocalHistory() {
        store.all().filter { it.imdb == null && !it.appLevel }.take(6).forEach { h ->
            val queries = listOfNotNull(h.subtitle?.takeIf { it.length >= 3 }?.let { it to "series" }, h.title to null)
            var found: Title? = null
            loop@ for ((q, forced) in queries) {
                for (type in forced?.let { listOf(it) } ?: listOf("series", "movie")) {
                    val hit = safe { Cinemeta.search(type, q) }.firstOrNull { matches(q, it.name) }
                    if (hit != null) { found = hit; break@loop }
                }
            }
            store.setResolution(h.key, found?.imdb ?: "-", found?.type)
        }
    }

    private fun matches(query: String, name: String): Boolean {
        val q = WatchHistoryStore.normalize(query)
        val n = WatchHistoryStore.normalize(name)
        return q == n || (n.length >= 4 && q.startsWith(n)) || (q.length >= 4 && n.startsWith(q))
    }

    private fun HistoryItem.toPoster() = PosterItem(
        title = title, subtitle = subtitle,
        image = resolvedImdb?.let { Cinemeta.backgroundUrl(it) } ?: artPath,
        progress = progress, imdb = resolvedImdb, type = type, pkg = pkg, historyKey = key,
    )

    private fun TraktItem.toPoster(landscape: Boolean) = PosterItem(
        title = title, subtitle = episodeLabel,
        image = imdb?.let { if (landscape) Cinemeta.backgroundUrl(it) else Cinemeta.posterUrl(it) },
        progress = progress, imdb = imdb, type = type, season = season, episode = episode,
    )

    private fun Title.toPoster() = PosterItem(
        title = name, subtitle = listOfNotNull(year, genres.take(2).joinToString(" · ").ifBlank { null }).joinToString("  "),
        image = poster, imdb = imdb, type = type, rating = rating,
    )

    private inline fun <T> safe(block: () -> List<T>): List<T> = runCatching(block).getOrDefault(emptyList())
}
