package com.custom.hisenselauncher.data

import android.content.Intent
import android.net.Uri

/**
 * Per-app knowledge for history tracking:
 *  - video platforms (YouTube…): items are videos, not movies/shows, so they skip catalog matching,
 *    get their own row, and reopen the exact video when the app shares its id
 *  - title-hiding apps (Hulu, Netflix…): no media metadata; TitleReaderService reads the player screen
 */
object VideoApps {
    const val YOUTUBE = "com.google.android.youtube.tv"
    const val YOUTUBE_KIDS = "com.google.android.youtube.tvkids"
    const val TWITCH = "tv.twitch.android.app"

    val PLATFORMS = setOf(YOUTUBE, YOUTUBE_KIDS, TWITCH)

    /** Apps that publish playback state but no titles on Google TV. Mirrors res/xml/title_reader.xml. */
    val TITLE_HIDING = setOf(
        "com.hulu.livingroomplus", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
        "com.wbd.stream", "com.cbs.ott", "com.peacocktv.peacockandroid", "com.apple.atve.androidtv.appletv",
        "com.philo.philo.google", "com.amcplus.amcandroidtv", "com.google.android.youtube.tvunplugged",
    )

    fun isPlatform(pkg: String?) = pkg in PLATFORMS

    private val ytId = Regex("^[A-Za-z0-9_-]{11}$")

    /** YouTube video id from a media id like "dQw4w9WgXcQ", "yt:dQw4w9WgXcQ" or a watch URL. */
    fun youTubeId(mediaId: String?): String? {
        val m = mediaId?.trim() ?: return null
        if (ytId.matches(m)) return m
        Regex("(?:v=|youtu\\.be/|/watch/|:)([A-Za-z0-9_-]{11})(?:\\b|$)").find(m)?.let { return it.groupValues[1] }
        return null
    }

    fun thumbnail(pkg: String, mediaId: String?): String? =
        if (pkg == YOUTUBE || pkg == YOUTUBE_KIDS) youTubeId(mediaId)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } else null

    /** Intent that reopens the exact video, or null to just launch the app. */
    fun openIntent(pkg: String, mediaId: String?): Intent? {
        val id = if (pkg == YOUTUBE || pkg == YOUTUBE_KIDS) youTubeId(mediaId) else null
        return id?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$it"))
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
