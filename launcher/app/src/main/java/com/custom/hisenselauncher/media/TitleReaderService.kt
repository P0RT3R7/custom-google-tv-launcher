package com.custom.hisenselauncher.media

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads what's playing off the player screen of apps that don't publish media metadata
 * (Hulu, Netflix, Prime Video, Disney+, Max…). Best effort, everything stays on the TV.
 *
 * Only receives events from the apps listed in res/xml/title_reader.xml, and only acts while that app
 * has a live playback session, so browse screens are ignored. When the player overlay shows (pause,
 * seek, start of playback) it collects the visible text, finds an episode line ("S1 E10", "Season 1
 * Episode 10") and the show title next to it, or a movie title in the top part of the screen.
 * A title must be read twice in a row before it's saved.
 *
 * Enable over adb:
 *   adb shell settings put secure enabled_accessibility_services com.custom.hisenselauncher/com.custom.hisenselauncher.media.TitleReaderService
 *   adb shell settings put secure accessibility_enabled 1
 * Debug: adb shell setprop log.tag.TitleReader D ; adb logcat -s TitleReader
 */
class TitleReaderService : AccessibilityService() {

    private val lastScan = HashMap<String, Long>()
    private var pending: Triple<String, String, String?>? = null

    override fun onServiceConnected() {
        if (MediaSessionHub.hasAccess(this)) MediaSessionHub.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (now - (lastScan[pkg] ?: 0L) < SCAN_EVERY_MS) return
        if (!MediaSessionHub.isLive(pkg)) return
        lastScan[pkg] = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val texts = ArrayList<Line>()
        collect(root, texts, 0)
        if (texts.isEmpty()) return
        val screenH = texts.maxOf { it.bounds.bottom }.coerceAtLeast(1)
        val found = parse(texts, screenH)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "$pkg: ${texts.size} lines; picked=${found?.first}|${found?.second}; top=" +
                texts.filter { useful(it.text) }.take(8).joinToString(" / ") { "${it.text}@${it.bounds.top}" })
        }
        found ?: return
        val (title, sub) = found
        val candidate = Triple(pkg, title, sub)
        if (pending?.first == pkg && pending?.second == title) {
            MediaSessionHub.recordFromReader(pkg, title, sub)
        }
        pending = candidate
    }

    override fun onInterrupt() {}

    private class Line(val text: String, val bounds: Rect)

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Line>, depth: Int) {
        if (out.size > 250 || depth > 30) return
        if (!n.isVisibleToUser) return
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length <= 120) {
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out += Line(t.replace(Regex("\\s+"), " "), r)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    /** Returns (title, subtitle) or null. */
    private fun parse(lines: List<Line>, screenH: Int): Pair<String, String?>? {
        val sorted = lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        // 1) episode line: title is the nearest useful line above it (or the text before the episode marker)
        sorted.forEachIndexed { i, l ->
            val m = EPISODE.find(l.text) ?: return@forEachIndexed
            val label = "S${m.groupValues[1]} E${m.groupValues[2]}"
            val rest = l.text.removeRange(m.range).trim(' ', ':', '·', '-', '|', ',', '.')
            val before = l.text.substring(0, m.range.first).trim(' ', ':', '·', '-', '|', ',')
            val epName = l.text.substring(m.range.last + 1).trim(' ', ':', '·', '-', '|', ',', '"', '“', '”').takeIf { useful(it) }
            val sub = listOfNotNull(label, epName).joinToString(" · ")
            if (useful(before) && before.length >= 2) return before to sub
            val above = sorted.subList(0, i).lastOrNull { useful(it.text) && !EPISODE.containsMatchIn(it.text) && l.bounds.top - it.bounds.bottom < screenH / 5 }
            if (above != null) return above.text to sub
            val below = sorted.drop(i + 1).firstOrNull { useful(it.text) && it.bounds.top - l.bounds.bottom < screenH / 8 }
            if (below != null && rest.isBlank()) return below.text to sub
        }
        // 2) movie: biggest useful text in the top third
        val top = sorted.filter { it.bounds.top < screenH * 0.34 && useful(it.text) && it.text.length >= 2 }
        return top.maxByOrNull { it.bounds.height() }?.let { it.text to null }
    }

    private fun useful(t: String): Boolean {
        if (t.isBlank() || t.length > 80) return false
        val low = t.lowercase()
        if (low in UI_WORDS) return false
        if (UI_PREFIXES.any { low.startsWith(it) }) return false
        if (TIME.matches(t) || RATING.matches(t)) return false
        if (t.count { it.isLetter() } < 2) return false
        return true
    }

    companion object {
        private const val TAG = "TitleReader"
        private const val SCAN_EVERY_MS = 4_000L
        private val EPISODE = Regex("(?i)\\bS(?:eason)?\\s*(\\d{1,2})\\s*[:,·.\\-]?\\s*E(?:p(?:isode)?)?\\.?\\s*(\\d{1,3})\\b")
        private val TIME = Regex("^-?\\d{1,2}(:\\d{2}){1,2}( ?/ ?-?\\d{1,2}(:\\d{2}){1,2})?$|^\\d+ ?(min|m|h|hr|sec|s)( left| remaining)?$", RegexOption.IGNORE_CASE)
        private val RATING = Regex("^(TV-(Y7?|G|PG|14|MA)|PG(-13)?|G|R|NC-17|NR|HD|UHD|4K|HDR|CC|AD|SDH|5\\.1|Dolby.*|\\d{1,3}%)$", RegexOption.IGNORE_CASE)
        private val UI_WORDS = setOf(
            "play", "pause", "resume", "back", "rewind", "fast forward", "forward", "skip", "skip intro", "skip recap", "skip credits",
            "next episode", "up next", "episodes", "subtitles", "audio", "audio & subtitles", "audio and subtitles", "settings",
            "more", "more info", "details", "restart", "start over", "live", "watch credits", "close", "menu", "options",
            "speed", "playback speed", "quality", "report a problem", "seek bar", "progress", "video player", "player",
            "loading", "buffering", "home", "search", "my stuff", "my list", "watchlist", "ad", "ads", "advertisement",
            "your ad will end in", "ad break", "volume", "captions", "language", "english", "on", "off", "ok", "cancel",
        )
        private val UI_PREFIXES = listOf("ad ", "ad·", "ad ·", "your ad", "ends in", "playing on", "next episode in", "up next in", "rated ")
    }
}
