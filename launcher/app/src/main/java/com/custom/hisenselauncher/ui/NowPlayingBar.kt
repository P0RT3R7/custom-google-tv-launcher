package com.custom.hisenselauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.custom.hisenselauncher.media.MediaSessionHub

/** Compact "what's playing" pill for the top bar: artwork, title, artist/app, prev / play-pause / next. */
class NowPlayingBar(ctx: Context, private val ui: Ui) : LinearLayout(ctx) {

    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = label(22f, Theme.TEXT, bold = true)
    private val sub = label(18f, Theme.TEXT_DIM)
    private val playPause = button("⏯") { MediaSessionHub.current?.let { np ->
        if (np.playing) np.controller.transportControls.pause() else np.controller.transportControls.play()
    } }
    private val listener: () -> Unit = { render() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBackground(ui, 40f)
        setPadding(ui.px(10), ui.px(8), ui.px(14), ui.px(8))
        clipChildren = false
        art.roundedClip(ui.f(10f))
        addView(art, LayoutParams(ui.px(58), ui.px(58)))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ui.px(14), 0, ui.px(10), 0) }
        col.addView(title); col.addView(sub)
        addView(col, LayoutParams(ui.px(300), LayoutParams.WRAP_CONTENT))
        addView(button("⏮") { MediaSessionHub.current?.controller?.transportControls?.skipToPrevious() })
        addView(playPause)
        addView(button("⏭") { MediaSessionHub.current?.controller?.transportControls?.skipToNext() })
        visibility = GONE
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); MediaSessionHub.addListener(listener); render() }
    override fun onDetachedFromWindow() { MediaSessionHub.removeListener(listener); super.onDetachedFromWindow() }

    fun render() {
        val np = MediaSessionHub.current
        if (np == null) { visibility = GONE; return }
        visibility = VISIBLE
        title.text = np.title
        val appName = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(np.pkg, 0)).toString() }.getOrNull()
        sub.text = if (np.titled) listOfNotNull(np.subtitle, appName).joinToString(" · ")
                   else if (np.playing) "Playing" else "Paused"
        playPause.text = if (np.playing) "⏸" else "▶"
        if (np.art != null) art.setImageBitmap(np.art)
        else art.setImageDrawable(runCatching { context.packageManager.getApplicationIcon(np.pkg) }.getOrNull())
    }

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(size)); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun button(glyph: String, onClick: () -> Unit) = TextView(context).apply {
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(26f)); setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = cardBackground(ui, 30f)
        applyFocusStyle(ui, 30f)
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(ui.px(58), ui.px(58)).apply { marginStart = ui.px(8) }
    }

    @Suppress("unused") private fun hide() { visibility = View.GONE }
}
