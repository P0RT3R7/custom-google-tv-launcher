package com.custom.hisenselauncher.weather

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.custom.hisenselauncher.data.Prefs
import com.custom.hisenselauncher.data.SkiReport
import com.custom.hisenselauncher.data.SkiRepository
import com.custom.hisenselauncher.ui.NightMode
import com.custom.hisenselauncher.ui.Theme
import com.custom.hisenselauncher.ui.Ui
import com.custom.hisenselauncher.ui.applyFocusStyle
import com.custom.hisenselauncher.ui.cardBackground
import com.custom.hisenselauncher.wallpaper.JellyfishView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Snow & ski report for the nearest ski areas: new snow, base depth, 3-day forecast, summit weather, avalanche danger. */
class SkiActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var body: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(JellyfishView(this, brightness = 0.45f), FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipChildren = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(100), ui.px(56), ui.px(100), ui.px(60))
            clipChildren = false; clipToPadding = false
        }
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        NightMode.attach(this)
        body.addView(text("Loading snow report…", 30f, Theme.TEXT_DIM))
        load()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun load(): Unit = io.execute {
        val result = runCatching { SkiRepository(this, Prefs(this)).fetch() }
        main.post {
            if (isFinishing) return@post
            body.removeAllViews()
            val list = result.getOrNull()
            when {
                list == null -> body.addView(text("Couldn't load the snow report. Check the TV's connection.", 30f, Theme.TEXT))
                list.isEmpty() -> body.addView(text("No ski areas within 100 miles of your location (☰ → Location).", 30f, Theme.TEXT))
                else -> render(list)
            }
        }
    }

    private fun render(list: List<SkiReport>) {
        body.addView(text("⛷  Snow report", 44f, Theme.TEXT, bold = true))
        list.forEach { r -> body.addView(card(r), lp(top = 26)) }
        body.addView(text(
            "New snow and forecast: Open-Meteo, modeled at mid-mountain · Snow depth: NRCS SNOTEL when a station is nearby · " +
                "Avalanche danger: avalanche.org (backcountry, outside ski area boundaries) · Lift status isn't available.",
            18f, Theme.TEXT_DIM), lp(top = 26))
        body.getChildAt(1)?.requestFocus()
    }

    private fun card(r: SkiReport): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(ui)
            setPadding(ui.px(36), ui.px(26), ui.px(36), ui.px(28))
            applyFocusStyle(ui)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text(r.resort.name, 36f, Theme.TEXT, bold = true))
        head.addView(text("   ${"%,d".format(r.resort.baseFt)}–${"%,d".format(r.resort.summitFt)} ft", 22f, Theme.TEXT_DIM))
        card.addView(head)

        // new snow + base
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(stat("24 hours", inches(r.new24)))
        stats.addView(stat("72 hours", inches(r.new72)))
        stats.addView(stat("7 days", inches(r.new7d)))
        stats.addView(stat("Snow depth", r.depthIn?.let { "$it\"" } ?: "–", highlight = true))
        card.addView(stats, lp(top = 16))
        card.addView(text("Snow depth ${r.depthSource}", 18f, Theme.TEXT_DIM), lp(top = 6))

        // forecast chips
        val fc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fc.addView(text("Forecast", 22f, Theme.TEXT_DIM), LinearLayout.LayoutParams(ui.px(150), -2))
        r.forecast.forEach { (day, inch) ->
            val snowy = inch >= 0.5
            fc.addView(text("$day  ${if (snowy) "❄ " else ""}${inches(inch)}", 24f, if (snowy) Color.rgb(170, 220, 255) else Theme.TEXT).apply {
                background = GradientDrawable().apply { setColor(if (snowy) Color.argb(90, 60, 130, 220) else Color.argb(60, 255, 255, 255)); cornerRadius = ui.f(30f) }
                setPadding(ui.px(22), ui.px(8), ui.px(22), ui.px(8))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.px(14) })
        }
        card.addView(fc, lp(top = 18))

        // summit weather
        if (r.hiF != null && r.loF != null) {
            card.addView(text("Summit today  ${r.hiF}° / ${r.loF}°" + (r.windMph?.let { " · wind up to $it mph" } ?: ""), 24f, Theme.TEXT), lp(top = 16))
        }

        // avalanche danger
        r.avalanche?.let { a ->
            val (bg, fg) = when (a.level) {
                1 -> Color.rgb(80, 184, 72) to Color.BLACK
                2 -> Color.rgb(255, 242, 0) to Color.BLACK
                3 -> Color.rgb(247, 148, 30) to Color.BLACK
                4 -> Color.rgb(237, 28, 36) to Color.WHITE
                5 -> Color.rgb(35, 31, 32) to Color.WHITE
                else -> Color.argb(90, 160, 160, 170) to Theme.TEXT
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(a.danger, 24f, fg, bold = true).apply {
                background = GradientDrawable().apply { setColor(bg); cornerRadius = ui.f(10f) }
                setPadding(ui.px(18), ui.px(6), ui.px(18), ui.px(6))
            })
            row.addView(text("   Backcountry avalanche danger · ${a.zone} (${a.center})", 22f, Theme.TEXT_DIM))
            card.addView(row, lp(top = 16))
            if (a.level > 0) a.advice?.let { card.addView(text(it, 20f, Theme.TEXT_DIM), lp(top = 6)) }
        }
        return card
    }

    private fun stat(label: String, value: String, highlight: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, ui.px(56), 0)
        addView(text(value, 52f, if (highlight) Theme.CYAN else Theme.TEXT, light = true))
        addView(text(label, 20f, Theme.TEXT_DIM))
    }

    private fun inches(v: Double): String = when {
        v < 0.05 -> "0\""
        v < 1 -> "%.1f\"".format(v)
        else -> "${v.roundToInt()}\""
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, light: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(size))
        typeface = when { bold -> Typeface.DEFAULT_BOLD; light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.DEFAULT }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(top) }
}
