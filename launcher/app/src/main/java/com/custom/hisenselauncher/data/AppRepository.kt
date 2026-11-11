package com.custom.hisenselauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val label: String,
    val image: Drawable?,
    val isBanner: Boolean,
    val launchIntent: Intent?,
    val isAllAppsTile: Boolean = false,
)

class AppRepository(private val ctx: Context) {

    /** Streaming apps pinned first on a fresh install, in priority order. */
    private val preferredFavorites = listOf(
        "com.nuvio.app", "com.hulu.livingroomplus", "com.stremio.one", "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.google.android.youtube.tv",
        "com.google.android.youtube.tvunplugged", "com.apple.atve.androidtv.appletv", "com.wbd.stream",
        "com.cbs.ott", "com.peacocktv.peacockandroid", "com.amcplus.amcandroidtv",
        "com.philo.philo.google", "com.spotify.tv.android", "com.google.android.youtube.tvmusic",
    )

    fun loadAll(): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                val banner = runCatching { ai.loadBanner(pm) }.getOrNull()
                val image = banner ?: runCatching { ai.loadIcon(pm) }.getOrNull()
                out += AppEntry(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    image = image,
                    isBanner = banner != null,
                    launchIntent = Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(ComponentName(pkg, ai.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun defaultFavorites(all: List<AppEntry>): List<String> {
        val installed = all.map { it.pkg }.toSet()
        return preferredFavorites.filter { it in installed }
    }
}
