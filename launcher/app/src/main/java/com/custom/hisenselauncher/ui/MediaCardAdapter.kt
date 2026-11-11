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
import com.custom.hisenselauncher.data.PosterItem

/**
 * Title card. Landscape (16:9, title over a gradient, progress bar) for Continue/Recently Watched;
 * portrait poster (2:3, rating badge, caption below) for suggestions and search results.
 */
class MediaCardAdapter(
    private val ui: Ui,
    private val landscape: Boolean,
    private val onClick: (PosterItem) -> Unit,
    private val onMenu: ((PosterItem) -> Unit)? = null,
) : RecyclerView.Adapter<MediaCardAdapter.VH>() {

    private var items: List<PosterItem> = emptyList()

    class VH(
        val root: LinearLayout, val card: FrameLayout, val image: ImageView, val overlayTitle: TextView,
        val overlaySub: TextView, val badge: TextView, val bar: View, val barFill: View, val caption: TextView,
    ) : RecyclerView.ViewHolder(root)

    fun submit(list: List<PosterItem>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size

    private val cardW get() = ui.px(if (landscape) 340 else 196)
    private val cardH get() = ui.px(if (landscape) 191 else 294)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val w = cardW; val h = cardH
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ui.px(10), ui.px(8), ui.px(10), ui.px(8)) }
            clipChildren = false
        }
        val card = FrameLayout(ctx).apply {
            background = cardBackground(ui)
            roundedClip(ui.f(14f))
            applyFocusStyle(ui)
        }
        root.addView(card, LinearLayout.LayoutParams(w, h))
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(image, FrameLayout.LayoutParams(w, h))

        val shade = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xE6000000.toInt()))
            visibility = if (landscape) View.VISIBLE else View.GONE
        }
        card.addView(shade, FrameLayout.LayoutParams(w, (h * 0.62f).toInt(), Gravity.BOTTOM))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(16), 0, ui.px(16), ui.px(16)) }
        val title = text(ctx, 24f, Theme.TEXT, bold = true)
        val sub = text(ctx, 18f, Theme.TEXT_DIM)
        col.addView(title); col.addView(sub)
        col.visibility = if (landscape) View.VISIBLE else View.GONE
        card.addView(col, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val badge = text(ctx, 18f, Color.WHITE, bold = true).apply {
            background = GradientDrawable().apply { setColor(Color.argb(190, 0, 0, 0)); cornerRadius = ui.f(10f) }
            setPadding(ui.px(10), ui.px(4), ui.px(10), ui.px(4))
        }
        card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(0, ui.px(10), ui.px(10), 0) })

        val barH = ui.px(5)
        val bar = View(ctx).apply { setBackgroundColor(Color.argb(110, 255, 255, 255)) }
        val fill = View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.CYAN, Theme.VIOLET)) }
        card.addView(bar, FrameLayout.LayoutParams(w, barH, Gravity.BOTTOM))
        card.addView(fill, FrameLayout.LayoutParams(w, barH, Gravity.BOTTOM))

        val caption = text(ctx, 20f, Theme.TEXT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.px(4), ui.px(8), ui.px(4), 0)
            visibility = if (landscape) View.GONE else View.VISIBLE
        }
        root.addView(caption, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        return VH(root, card, image, title, sub, badge, bar, fill, caption)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.overlayTitle.text = it.title
        h.overlaySub.text = it.subtitle.orEmpty()
        h.overlaySub.visibility = if (it.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        h.caption.text = it.title
        h.badge.text = it.rating?.let { r -> "★ %.1f".format(r) } ?: ""
        h.badge.visibility = if (it.rating != null) View.VISIBLE else View.GONE
        val hasProgress = it.progress in 0.01f..1f
        h.bar.visibility = if (hasProgress) View.VISIBLE else View.GONE
        h.barFill.visibility = if (hasProgress) View.VISIBLE else View.GONE
        if (hasProgress) h.barFill.layoutParams = h.barFill.layoutParams.apply { width = (cardW * it.progress).toInt().coerceAtLeast(ui.px(6)) }
        if (it.image == null && it.pkg != null) {
            // app-level Resume card (Hulu, Netflix…): use the app's own TV banner
            Glide.with(h.image).clear(h.image)
            h.image.setImageDrawable(appBanner(h.image.context, it.pkg))
        } else {
            Glide.with(h.image).load(it.image).into(h.image)
        }
        h.card.setOnClickListener { _ -> onClick(it) }
        h.card.setOnLongClickListener { _ -> onMenu?.invoke(it); onMenu != null }
    }

    override fun onViewRecycled(h: VH) { Glide.with(h.image).clear(h.image) }

    private fun appBanner(ctx: android.content.Context, pkg: String): android.graphics.drawable.Drawable? {
        val pm = ctx.packageManager
        return runCatching { pm.getLeanbackLaunchIntentForPackage(pkg)?.component?.let { pm.getActivityBanner(it) } }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun text(ctx: android.content.Context, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(size)); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
}
