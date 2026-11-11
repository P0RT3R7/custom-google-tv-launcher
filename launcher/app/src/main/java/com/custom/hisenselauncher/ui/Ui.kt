package com.custom.hisenselauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator

object Theme {
    val CYAN = Color.rgb(0, 229, 255)
    val VIOLET = Color.rgb(138, 92, 255)
    val TEXT = Color.rgb(240, 246, 250)
    val TEXT_DIM = Color.argb(170, 220, 232, 240)
    /** Card fill: near-black, translucent so the wallpaper still breathes through. */
    val CARD = Color.argb(150, 8, 12, 18)
    const val FOCUS_SCALE = 1.08f
}

/** Design unit: 1 u == 1 pixel on a 1920x1080 canvas, scaled to whatever the TV reports. */
class Ui(ctx: Context) {
    private val k: Float = with(ctx.resources.displayMetrics) { maxOf(widthPixels, heightPixels) / 1920f }
    fun px(u: Float): Int = (u * k).toInt()
    fun px(u: Int): Int = (u * k).toInt()
    fun f(u: Float): Float = u * k
}

/** Cyan->violet bioluminescent focus ring: a crisp edge plus soft inner falloff rings. */
class GlowDrawable(private val corner: Float, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val rect = RectF()
    private val alphas = intArrayOf(255, 120, 70, 38, 16)

    override fun onBoundsChange(b: android.graphics.Rect) {
        paint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            Theme.CYAN, Theme.VIOLET, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(c: Canvas) {
        for (i in alphas.indices) {
            val inset = stroke / 2f + i * stroke * 0.85f
            rect.set(bounds)
            rect.inset(inset, inset)
            paint.alpha = alphas[i]
            c.drawRoundRect(rect, (corner - inset).coerceAtLeast(0f), (corner - inset).coerceAtLeast(0f), paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 1.08x scale-up, raised z, and the glow ring while focused. */
fun View.applyFocusStyle(ui: Ui, corner: Float = 14f) {
    val glow = GlowDrawable(ui.f(corner), ui.f(3f))
    isFocusable = true
    isClickable = true
    isLongClickable = true
    setOnFocusChangeListener { v, hasFocus ->
        v.animate().cancel()
        v.animate()
            .scaleX(if (hasFocus) Theme.FOCUS_SCALE else 1f)
            .scaleY(if (hasFocus) Theme.FOCUS_SCALE else 1f)
            .translationZ(if (hasFocus) ui.f(12f) else 0f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .start()
        v.foreground = if (hasFocus) glow else null
    }
}

fun View.roundedClip(radiusPx: Float) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
}

fun cardBackground(ui: Ui, corner: Float = 14f) = GradientDrawable().apply {
    setColor(Theme.CARD)
    cornerRadius = ui.f(corner)
}
