package com.custom.hisenselauncher.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.custom.hisenselauncher.data.AppEntry
import com.custom.hisenselauncher.data.ContinueItem

private fun TextView.px(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

/** App tile (banner or icon + label). Used by the dock, recent bar and All Apps grid. */
class AppAdapter(
    private val ui: Ui,
    private val cardWidthU: Float,
    private val onClick: (AppEntry) -> Unit,
    private val onMenu: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppEntry> = emptyList()

    class VH(val root: LinearLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    fun submit(list: List<AppEntry>) { items = list; notifyDataSetChanged() }
    fun itemAt(pos: Int) = items.getOrNull(pos)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val w = ui.px(cardWidthU)
        val imgH = (w * 9f / 16f).toInt()
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(ui.px(10), ui.px(8), ui.px(10), ui.px(8))
            }
            background = cardBackground(ui)
            roundedClip(ui.f(14f))
            applyFocusStyle(ui)
        }
        val image = ImageView(parent.context).apply { layoutParams = LinearLayout.LayoutParams(w, imgH) }
        val label = TextView(parent.context).apply {
            px(ui.f(22f)); setTextColor(Theme.TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(ui.px(8), ui.px(6), ui.px(8), ui.px(8))
        }
        root.addView(image); root.addView(label)
        return VH(root, image, label)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.label.text = e.label
        if (e.isAllAppsTile) {
            h.image.setImageDrawable(null)
            h.image.setBackgroundColor(Color.argb(70, 0, 229, 255))
            h.image.scaleType = ImageView.ScaleType.CENTER
            // glyph drawn via a tiny TextView-less approach: use label only + grid dots in the banner area
            h.image.setImageDrawable(GridGlyph(ui))
        } else {
            h.image.background = null
            h.image.setBackgroundColor(if (e.isBanner) Color.TRANSPARENT else Color.argb(60, 255, 255, 255))
            h.image.scaleType = if (e.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            h.image.setPadding(if (e.isBanner) 0 else ui.px(28), if (e.isBanner) 0 else ui.px(10), if (e.isBanner) 0 else ui.px(28), if (e.isBanner) 0 else ui.px(10))
            h.image.setImageDrawable(e.image)
        }
        h.root.setOnClickListener { onClick(e) }
        h.root.setOnLongClickListener { onMenu(e, it); true }
    }
}

/** 3x3 dot grid glyph for the "All Apps" tile. */
private class GridGlyph(private val ui: Ui) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Theme.CYAN }
    override fun draw(c: android.graphics.Canvas) {
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY(); val gap = ui.f(22f)
        for (i in -1..1) for (j in -1..1) c.drawCircle(cx + i * gap, cy + j * gap, ui.f(6f), p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Continue Watching card: poster, title, thin progress bar. */
class ContinueAdapter(
    private val ui: Ui,
    private val onClick: (ContinueItem) -> Unit,
) : RecyclerView.Adapter<ContinueAdapter.VH>() {

    private var items: List<ContinueItem> = emptyList()

    class VH(val root: FrameLayout, val poster: ImageView, val title: TextView, val sub: TextView, val bar: View, val barFill: View) :
        RecyclerView.ViewHolder(root)

    fun submit(list: List<ContinueItem>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val w = ui.px(340); val h = ui.px(191)
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(w, h).apply { setMargins(ui.px(10), ui.px(8), ui.px(10), ui.px(8)) }
            background = cardBackground(ui)
            roundedClip(ui.f(14f))
            applyFocusStyle(ui)
        }
        val poster = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(poster, FrameLayout.LayoutParams(w, h))

        val shade = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xE6000000.toInt()))
        }
        root.addView(shade, FrameLayout.LayoutParams(w, (h * 0.6f).toInt(), Gravity.BOTTOM))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(16), 0, ui.px(16), ui.px(14)) }
        val title = TextView(ctx).apply {
            px(ui.f(24f)); setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            px(ui.f(18f)); setTextColor(Theme.TEXT_DIM); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title); col.addView(sub)
        root.addView(col, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = ui.px(6) })

        val barH = ui.px(5)
        val bar = View(ctx).apply { setBackgroundColor(Color.argb(110, 255, 255, 255)) }
        val fill = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.CYAN, Theme.VIOLET))
        }
        root.addView(bar, FrameLayout.LayoutParams(w, barH, Gravity.BOTTOM))
        root.addView(fill, FrameLayout.LayoutParams(w, barH, Gravity.BOTTOM))
        return VH(root, poster, title, sub, bar, fill)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.sub.text = it.subtitle.orEmpty()
        h.sub.visibility = if (it.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        val hasProgress = it.progress >= 0f
        h.bar.visibility = if (hasProgress) View.VISIBLE else View.GONE
        h.barFill.visibility = if (hasProgress) View.VISIBLE else View.GONE
        if (hasProgress) h.barFill.layoutParams = h.barFill.layoutParams.apply { width = (ui.px(340) * it.progress).toInt().coerceAtLeast(ui.px(6)) }
        Glide.with(h.poster).load(it.posterUri).into(h.poster)
        h.root.setOnClickListener { _ -> onClick(it) }
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.poster).clear(h.poster) }
}
