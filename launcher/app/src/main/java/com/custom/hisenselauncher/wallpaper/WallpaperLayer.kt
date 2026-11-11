package com.custom.hisenselauncher.wallpaper

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Background layer: a muted, looping, hardware-decoded ExoPlayer video when
 * /sdcard/Movies/jellyfish_4k.mp4 exists (swap the file any time, no recompile),
 * otherwise the procedural JellyfishView. A 35% black scrim sits on top of either.
 */
class WallpaperLayer(private val ctx: Context) : FrameLayout(ctx) {

    private var player: ExoPlayer? = null
    private var current: View? = null

    init {
        setBackgroundColor(Color.BLACK)
        // Scrim: #000000 @ 35% over the wallpaper keeps text crisp without lifting the blacks.
        val scrim = View(ctx).apply { setBackgroundColor(Color.argb(89, 0, 0, 0)) }
        addView(scrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun start() {
        stop()
        val file = File(VIDEO_PATH)
        if (file.exists() && file.canRead()) startVideo(file) else startFallback()
    }

    fun stop() {
        player?.release()
        player = null
        current?.let { removeView(it) }
        current = null
    }

    private fun startFallback() = setBackgroundView(JellyfishView(ctx))

    private fun startVideo(file: File) {
        val view = PlayerView(ctx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        val p = ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Bad/unsupported file -> never leave the user with a blank screen.
                    post { stop(); startFallback() }
                }
            })
            prepare()
            playWhenReady = true
        }
        view.player = p
        player = p
        setBackgroundView(view)
    }

    private fun setBackgroundView(v: View) {
        current?.let { removeView(it) }
        current = v
        addView(v, 0, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    companion object {
        const val VIDEO_PATH = "/sdcard/Movies/jellyfish_4k.mp4"
    }
}
