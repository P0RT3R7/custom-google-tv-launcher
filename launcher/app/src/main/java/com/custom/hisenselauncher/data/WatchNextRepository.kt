package com.custom.hisenselauncher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

data class ContinueItem(
    val title: String,
    val subtitle: String?,
    val posterUri: Uri?,
    /** 0f..1f, or -1f when the provider gave no duration. */
    val progress: Float,
    val intent: Intent?,
    val pkg: String?,
)

class WatchNextResult(val items: List<ContinueItem>, val permissionDenied: Boolean)

/**
 * Reads Android TV's Watch Next + Preview programs. Requires
 * com.android.providers.tv.permission.READ_EPG_DATA (grant with adb).
 * Preview programs are only shown if they carry playback progress, so passive
 * recommendations / sponsored rows never appear in the Continue Watching rail.
 */
class WatchNextRepository(private val ctx: Context) {

    fun load(): WatchNextResult {
        val out = ArrayList<ContinueItem>()
        var denied = false

        try {
            ctx.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_ITEMS) {
                    val p = WatchNextProgram.fromCursor(c)
                    out += toItem(
                        p.title, p.description, p.posterArtUri ?: p.thumbnailUri,
                        p.lastPlaybackPositionMillis, p.durationMillis, p.intentUri?.toString(), p.packageName,
                    )
                }
            }
        } catch (_: SecurityException) { denied = true } catch (_: Exception) { }

        try {
            ctx.contentResolver.query(
                TvContractCompat.PreviewPrograms.CONTENT_URI, PreviewProgram.PROJECTION, null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_ITEMS) {
                    val p = PreviewProgram.fromCursor(c)
                    if (p.lastPlaybackPositionMillis <= 0) continue   // not "continue watching"
                    out += toItem(
                        p.title, p.description, p.posterArtUri ?: p.thumbnailUri,
                        p.lastPlaybackPositionMillis, p.durationMillis, p.intentUri?.toString(), p.packageName,
                    )
                }
            }
        } catch (_: SecurityException) { denied = true } catch (_: Exception) { }

        return WatchNextResult(out.distinctBy { it.title + it.pkg }, denied)
    }

    private fun toItem(
        title: String?, desc: String?, poster: Uri?, pos: Int, dur: Int, intentUri: String?, pkg: String?,
    ): ContinueItem {
        val intent = intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
            ?.apply { selector = null; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val progress = if (dur > 0 && pos >= 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else -1f
        return ContinueItem(title ?: "", desc, poster, progress, intent, pkg)
    }

    companion object { private const val MAX_ITEMS = 20 }
}
