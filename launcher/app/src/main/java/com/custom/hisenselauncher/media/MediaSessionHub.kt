package com.custom.hisenselauncher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.custom.hisenselauncher.data.HistoryItem
import com.custom.hisenselauncher.data.WatchHistoryStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Bound by the system once notification-listener access is granted; keeps the hub alive. */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() { MediaSessionHub.attach(this) }
    override fun onListenerDisconnected() { MediaSessionHub.detach() }
}

/**
 * Watches every active media session on the TV:
 *  - powers the Now Playing bar (any app, incl. Spotify)
 *  - records video titles into the local watch history
 */
object MediaSessionHub {

    data class NowPlaying(
        val pkg: String,
        val title: String,
        val subtitle: String?,
        val art: Bitmap?,
        val playing: Boolean,
        val controller: MediaController,
        /** false when the app shares no title (title is then the app's name). */
        val titled: Boolean = true,
    )

    /** Music apps show in Now Playing but never in "Recently Watched". */
    private val MUSIC_PKGS = setOf(
        "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.pandora.android.atv",
        "com.amazon.music.tv", "com.apple.android.music", "com.soundcloud.android", "deezer.android.tv",
        "com.tidal.android.tv",
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var appCtx: Context? = null
    private var manager: MediaSessionManager? = null
    private var store: WatchHistoryStore? = null
    private val controllers = HashMap<MediaSession_Token, Pair<MediaController, MediaController.Callback>>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val lastSaved = HashMap<String, Long>()

    var current: NowPlaying? = null
        private set

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    /** Safe to call repeatedly (service connect, activity resume). */
    fun attach(ctx: Context) {
        if (manager != null) { refresh(); return }
        val c = ctx.applicationContext
        appCtx = c
        store = WatchHistoryStore(c)
        val m = c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(c, MediaListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            manager = m
            onSessions(m.getActiveSessions(component))
        } catch (_: SecurityException) {
            manager = null   // access not granted yet
        }
    }

    fun detach() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        controllers.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        update()
    }

    private fun refresh() {
        val m = manager ?: return
        val c = appCtx ?: return
        try { onSessions(m.getActiveSessions(ComponentName(c, MediaListenerService::class.java))) } catch (_: SecurityException) { }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list) }

    private fun onSessions(list: List<MediaController>?) {
        val live = list.orEmpty().filter { it.packageName != appCtx?.packageName }
        val tokens = live.map { MediaSession_Token(it.sessionToken.hashCode(), it.packageName) }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { k -> controllers.remove(k)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for (c in live) {
            val k = MediaSession_Token(c.sessionToken.hashCode(), c.packageName)
            if (k in controllers) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = update()
                override fun onPlaybackStateChanged(state: PlaybackState?) = update()
                override fun onSessionDestroyed() = refresh()
            }
            c.registerCallback(cb, main)
            controllers[k] = c to cb
        }
        update()
    }

    private val liveStates = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING)

    private fun titleOf(c: MediaController): String? = c.metadata?.let { md ->
        (md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE))?.takeIf { it.isNotBlank() }
    }

    private fun appName(pkg: String): String = appCtx?.packageManager?.let { pm ->
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    } ?: pkg

    private fun update() {
        // Sessions with a title, or untitled ones that are clearly playing/paused (Hulu, Netflix publish no metadata).
        val all = controllers.values.map { it.first }
            .filter { titleOf(it) != null || it.playbackState?.state in liveStates }
        val pick = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        current = pick?.let { c ->
            val md = c.metadata
            val title = titleOf(c)
            NowPlaying(
                pkg = c.packageName,
                title = title ?: appName(c.packageName),
                subtitle = if (title == null) null else (md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                controller = c,
                titled = title != null,
            )
        }
        current?.takeIf { it.playing && it.pkg !in MUSIC_PKGS }?.let(::record)
        listeners.toList().forEach { it() }
    }

    /** Writes the playing video into local history at most every 30 s per title (or per app when untitled). */
    private fun record(np: NowPlaying) {
        val s = store ?: return
        val key = if (np.titled) WatchHistoryStore.keyFor(np.pkg, np.title) else WatchHistoryStore.appKey(np.pkg)
        val now = System.currentTimeMillis()
        if (now - (lastSaved[key] ?: 0L) < 30_000) return
        lastSaved[key] = now
        android.util.Log.d("MediaSessionHub", "recorded ${if (np.titled) "title" else "app-level"} entry for ${np.pkg}")
        val state = np.controller.playbackState
        val duration = np.controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val art = np.art
        io.execute {
            val artPath = art?.let { bmp ->
                val f = File(s.artDir, key.hashCode().toUInt().toString(16) + ".jpg")
                if (!f.exists()) runCatching { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                f.takeIf { it.exists() }?.absolutePath
            }
            s.upsert(
                HistoryItem(
                    key = key, title = np.title, subtitle = np.subtitle, pkg = np.pkg, artPath = artPath,
                    imdb = if (np.titled) null else "-", type = null, lastSeen = now,
                    position = state?.position ?: 0L, duration = duration,
                    appLevel = !np.titled,
                )
            )
        }
    }

    private data class MediaSession_Token(val hash: Int, val pkg: String)
}
