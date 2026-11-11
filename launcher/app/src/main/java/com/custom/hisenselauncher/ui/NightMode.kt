package com.custom.hisenselauncher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.custom.hisenselauncher.data.Prefs
import java.util.Calendar

/**
 * Night mode: a warm, dim filter over the launcher's screens.
 * AUTO is on from [START_MIN] (8:30 PM) to [END_MIN] (5:00 AM).
 * The TV has no system-wide Night Light (color_display reports "Not available"),
 * so this covers the launcher, its screens and the screensaver, not other apps.
 */
enum class NightSetting(val label: String) {
    AUTO("Auto (8:30 PM – 5 AM)"),
    ON("Always on"),
    OFF("Off");

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

object NightMode {
    const val START_MIN = 20 * 60 + 30
    const val END_MIN = 5 * 60

    fun setting(ctx: Context) = NightSetting.from(Prefs(ctx).nightMode)

    fun isNight(ctx: Context, now: Calendar = Calendar.getInstance()): Boolean = when (setting(ctx)) {
        NightSetting.ON -> true
        NightSetting.OFF -> false
        NightSetting.AUTO -> {
            val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            m >= START_MIN || m < END_MIN
        }
    }

    /** Adds the filter on top of an activity's whole window. */
    fun attach(activity: Activity): NightOverlay {
        val decor = activity.window.decorView as ViewGroup
        val overlay = NightOverlay(activity)
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return overlay
    }
}

/**
 * Full-screen warm dimmer. Never takes focus or touches; checks the clock every
 * minute and fades in or out over a few seconds when night starts or ends.
 * Blend: out = 52% of the picture + 48% of deep amber, so white text becomes a soft
 * warm grey (~155,140,128) and blue light drops by about half.
 */
class NightOverlay(ctx: Context, private val strength: Float = 0.48f) : View(ctx) {

    private val tick = object : Runnable {
        override fun run() { refresh(animate = true); postDelayed(this, 60_000) }
    }

    init {
        setBackgroundColor(Color.rgb(48, 20, 0))
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = if (NightMode.isNight(ctx)) strength else 0f
        visibility = if (alpha > 0f) VISIBLE else GONE
    }

    fun refresh(animate: Boolean) {
        val target = if (NightMode.isNight(context)) strength else 0f
        if (target == alpha) return
        animate().cancel()
        if (target > 0f) visibility = VISIBLE
        if (!animate) { alpha = target; if (target == 0f) visibility = GONE; return }
        animate().alpha(target).setDuration(4_000).withEndAction { if (target == 0f) visibility = GONE }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh(animate = false)
        postDelayed(tick, 60_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
