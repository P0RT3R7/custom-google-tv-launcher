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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.custom.hisenselauncher.ui.Theme
import com.custom.hisenselauncher.ui.Ui
import com.custom.hisenselauncher.ui.applyFocusStyle
import com.custom.hisenselauncher.ui.cardBackground
import com.custom.hisenselauncher.wallpaper.JellyfishView
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Full-screen forecast for the saved place: now, next 24 h, 7 days, sunrise/sunset, NWS alerts. */
class WeatherActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var body: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val place by lazy { com.custom.hisenselauncher.data.Places.current(com.custom.hisenselauncher.data.Prefs(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(JellyfishView(this, brightness = 0.55f), FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(140, 0, 0, 0)) }, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipChildren = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(100), ui.px(60), ui.px(100), ui.px(60))
            clipChildren = false; clipToPadding = false
        }
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        com.custom.hisenselauncher.ui.NightMode.attach(this)
        body.addView(text("Loading ${place.shortName} forecast…", 30f, Theme.TEXT_DIM))
        load()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun load(): Unit = io.execute {
        val result = runCatching { WeatherDetailRepository.fetch(place) }
        main.post {
            if (isFinishing) return@post
            body.removeAllViews()
            result.onSuccess(::render).onFailure {
                body.addView(text("Couldn't load the forecast. Check the TV's connection.", 30f, Theme.TEXT))
                body.addView(pill("Retry") { body.removeAllViews(); body.addView(text("Loading…", 30f, Theme.TEXT_DIM)); load() })
            }
        }
    }

    private fun render(w: WeatherDetail) {
        // header
        body.addView(text(place.name, 30f, Theme.TEXT_DIM))
        val now = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        now.addView(text(WeatherCodes.icon(w.code, w.isDay), 96f, Theme.TEXT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(24), 0, 0, 0) }
        col.addView(text("${w.tempF}°F", 110f, Theme.TEXT, light = true))
        col.addView(text("${WeatherCodes.text(w.code)} · Feels like ${w.feelsF}°", 30f, Theme.TEXT))
        now.addView(col)
        val today = w.days.firstOrNull()
        val facts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(80), 0, 0, 0) }
        today?.let { facts.addView(text("High ${it.hiF}°   Low ${it.loF}°", 28f, Theme.TEXT)) }
        facts.addView(text("Wind ${w.windDir} ${w.windMph} mph · gusts ${w.gustMph}", 26f, Theme.TEXT_DIM))
        facts.addView(text("Humidity ${w.humidity}% · UV ${"%.0f".format(w.uv)}", 26f, Theme.TEXT_DIM))
        today?.let { facts.addView(text("Sunrise ${it.sunrise} · Sunset ${it.sunset}", 26f, Theme.TEXT_DIM)) }
        now.addView(facts)
        body.addView(now)

        // NWS alerts
        w.alerts.forEach { a ->
            val color = when (a.severity) { "Extreme", "Severe" -> Color.argb(150, 200, 40, 40); else -> Color.argb(130, 200, 120, 20) }
            body.addView(text("⚠ ${a.event}", 30f, Theme.TEXT).apply {
                background = GradientDrawable().apply { setColor(color); cornerRadius = ui.f(14f) }
                setPadding(ui.px(24), ui.px(14), ui.px(24), ui.px(14))
            }, lp(top = 24))
            body.addView(text(a.headline, 22f, Theme.TEXT_DIM), lp(top = 6))
        }

        // next 24 hours
        body.addView(section("Next 24 hours"))
        val hourFmt = DateTimeFormatter.ofPattern("h a")
        val hourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(ui.px(6), ui.px(14), ui.px(6), ui.px(14)) }
        w.hours.forEachIndexed { i, h ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = cardBackground(ui)
                setPadding(ui.px(18), ui.px(14), ui.px(18), ui.px(14))
                applyFocusStyle(ui)
            }
            cell.addView(text(if (i == 0) "Now" else h.time.format(hourFmt), 22f, Theme.TEXT_DIM))
            cell.addView(text(WeatherCodes.icon(h.code, h.isDay), 40f, Theme.TEXT))
            cell.addView(text("${h.tempF}°", 32f, Theme.TEXT))
            cell.addView(text(if (h.precip > 0) "💧${h.precip}%" else " ", 18f, Color.rgb(150, 200, 255)))
            hourRow.addView(cell, LinearLayout.LayoutParams(ui.px(132), -2).apply { marginEnd = ui.px(14) })
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(hourRow) })

        // 7 days
        body.addView(section("7-day forecast"))
        val dayFmt = DateTimeFormatter.ofPattern("EEEE")
        val lo = w.days.minOfOrNull { it.loF } ?: 0
        val hi = w.days.maxOfOrNull { it.hiF } ?: 1
        w.days.forEachIndexed { i, d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBackground(ui)
                setPadding(ui.px(28), ui.px(12), ui.px(28), ui.px(12))
                applyFocusStyle(ui)
            }
            row.addView(text(if (i == 0) "Today" else d.date.format(dayFmt), 28f, Theme.TEXT), LinearLayout.LayoutParams(ui.px(220), -2))
            row.addView(text(WeatherCodes.icon(d.code), 38f, Theme.TEXT), LinearLayout.LayoutParams(ui.px(80), -2))
            row.addView(text(WeatherCodes.text(d.code), 24f, Theme.TEXT_DIM), LinearLayout.LayoutParams(ui.px(260), -2))
            row.addView(text(if (d.precip > 0) "💧${d.precip}%" else "", 22f, Color.rgb(150, 200, 255)), LinearLayout.LayoutParams(ui.px(120), -2))
            row.addView(text("${d.loF}°", 26f, Theme.TEXT_DIM).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(ui.px(80), -2))
            row.addView(rangeBar(d.loF, d.hiF, lo, hi), LinearLayout.LayoutParams(ui.px(360), ui.px(10)).apply { setMargins(ui.px(18), 0, ui.px(18), 0) })
            row.addView(text("${d.hiF}°", 26f, Theme.TEXT), LinearLayout.LayoutParams(ui.px(80), -2))
            row.addView(text("💨 ${d.windMph} mph", 22f, Theme.TEXT_DIM), LinearLayout.LayoutParams(ui.px(160), -2))
            body.addView(row, lp(top = 12))
        }
        body.addView(text("Data: Open-Meteo · Alerts: National Weather Service", 18f, Theme.TEXT_DIM), lp(top = 30))
        hourRow.getChildAt(0)?.requestFocus()
    }

    private fun rangeBar(lo: Int, hi: Int, min: Int, max: Int): View {
        val span = (max - min).coerceAtLeast(1).toFloat()
        val track = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(Color.argb(50, 255, 255, 255)); cornerRadius = ui.f(5f) } }
        val fill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(120, 180, 255), Color.rgb(255, 180, 90))).apply { cornerRadius = ui.f(5f) }
        }
        val total = ui.px(360)
        track.addView(fill, FrameLayout.LayoutParams(((hi - lo) / span * total).toInt().coerceAtLeast(ui.px(10)), -1).apply {
            leftMargin = ((lo - min) / span * total).toInt()
        })
        return track
    }

    private fun section(s: String) = text(s, 30f, Theme.TEXT, bold = true).apply { setPadding(0, ui.px(36), 0, ui.px(4)) }

    private fun pill(s: String, onClick: () -> Unit) = text(s, 26f, Theme.TEXT).apply {
        background = cardBackground(ui, 40f); setPadding(ui.px(30), ui.px(14), ui.px(30), ui.px(14))
        applyFocusStyle(ui, 40f); setOnClickListener { onClick() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, light: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(size))
        setTextColor(color)
        typeface = when { bold -> Typeface.DEFAULT_BOLD; light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.DEFAULT }
        includeFontPadding = false
        setPadding(0, ui.px(4), 0, ui.px(4))
    }
}
