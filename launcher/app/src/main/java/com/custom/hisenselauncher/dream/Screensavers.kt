package com.custom.hisenselauncher.dream

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.custom.hisenselauncher.data.Prefs
import com.custom.hisenselauncher.data.WeatherRepository
import com.custom.hisenselauncher.ui.NightOverlay
import com.custom.hisenselauncher.ui.Ui
import com.custom.hisenselauncher.wallpaper.GLSceneView
import com.custom.hisenselauncher.wallpaper.JellyfishView
import com.custom.hisenselauncher.wallpaper.TopoScene
import kotlin.random.Random

/** Screensaver themes. ROTATE switches theme every [ScreensaverHost.ROTATE_MS]. */
enum class ScreensaverTheme(val label: String) {
    ROTATE("Rotate all"),
    NIGHT_SKY("Night Sky"),
    TOPO("Topo Glow"),
    JELLYFISH("Jellyfish Deep");

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: ROTATE
        val cycle = listOf(NIGHT_SKY, TOPO, JELLYFISH)
    }
}

/**
 * Hosts the screensaver: the theme's scene plus a softly drifting clock (hidden on the
 * night sky, whose own panel shows the time). Used by the Dream and by the preview screen.
 */
class ScreensaverHost(private val ctx: Context, private val theme: ScreensaverTheme) : FrameLayout(ctx) {

    private val ui = Ui(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private var current: ScreensaverTheme = if (theme == ScreensaverTheme.ROTATE) pickStart() else theme
    private var scene: View? = null
    private val clock: LinearLayout
    private val legend: TextView
    private val night = NightOverlay(ctx)

    private val drift = object : Runnable {
        override fun run() {
            clock.animate().alpha(0f).setDuration(1200).withEndAction {
                val maxX = (width - clock.width).coerceAtLeast(1)
                val maxY = (height - clock.height).coerceAtLeast(1)
                clock.translationX = Random.nextInt(maxX).toFloat()
                clock.translationY = Random.nextInt(maxY).toFloat()
                if (current != ScreensaverTheme.NIGHT_SKY) clock.animate().alpha(0.75f).setDuration(1200).start()
            }.start()
            handler.postDelayed(this, 60_000)
        }
    }

    private val rotate = object : Runnable {
        override fun run() {
            val list = ScreensaverTheme.cycle
            show(list[(list.indexOf(current) + 1) % list.size])
            handler.postDelayed(this, ROTATE_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        clock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; alpha = 0.75f }
        clock.addView(TextClock(ctx).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(110f)); setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setShadowLayer(ui.f(18f), 0f, 0f, Color.argb(160, 0, 0, 0))
        })
        val w = WeatherRepository(Prefs(ctx)).cached()
        clock.addView(TextView(ctx).apply {
            text = listOfNotNull(
                java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date()),
                w?.let { "${it.icon} ${it.tempF}°F" },
            ).joinToString("   ")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(30f)); setTextColor(Color.rgb(230, 232, 245))
            setShadowLayer(ui.f(12f), 0f, 0f, Color.argb(160, 0, 0, 0))
        })
        legend = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(20f)); setTextColor(Color.argb(200, 240, 240, 250))
            setShadowLayer(ui.f(8f), 0f, 0f, Color.BLACK)
            gravity = Gravity.START
        }
        show(current)
    }

    private fun pickStart(): ScreensaverTheme {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour >= 19 || hour < 6) ScreensaverTheme.NIGHT_SKY else ScreensaverTheme.TOPO
    }

    private fun show(t: ScreensaverTheme) {
        current = t
        scene?.let { removeView(it) }
        removeView(clock); removeView(legend)
        val v: View = when (t) {
            ScreensaverTheme.NIGHT_SKY -> NightSkyView(ctx)
            ScreensaverTheme.TOPO -> GLSceneView(ctx, TopoScene())
            else -> JellyfishView(ctx, brightness = 0.9f)
        }
        scene = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        clock.alpha = if (t == ScreensaverTheme.NIGHT_SKY) 0f else 0.75f
        legend.text = if (t == ScreensaverTheme.TOPO) "Bridger Range style relief · contour interval 200 ft · bold index every 1,000 ft" else ""
        addView(legend, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(ui.px(60), 0, 0, ui.px(40))
        })
        removeView(night)
        addView(night, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.postDelayed(drift, 2_000)
        if (theme == ScreensaverTheme.ROTATE) handler.postDelayed(rotate, ROTATE_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object { const val ROTATE_MS = 10 * 60_000L }
}

/** Full-screen preview of a screensaver theme; any key closes it. */
class ScreensaverPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = ScreensaverTheme.from(intent.getStringExtra(EXTRA_THEME))
        setContentView(ScreensaverHost(this, theme))
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP) finish()
        return true
    }

    companion object { const val EXTRA_THEME = "theme" }
}
