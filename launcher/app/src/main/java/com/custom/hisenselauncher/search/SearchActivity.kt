package com.custom.hisenselauncher.search

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.custom.hisenselauncher.data.AppEntry
import com.custom.hisenselauncher.data.AppRepository
import com.custom.hisenselauncher.data.Cinemeta
import com.custom.hisenselauncher.data.PosterItem
import com.custom.hisenselauncher.data.Prefs
import com.custom.hisenselauncher.data.TitleLauncher
import com.custom.hisenselauncher.ui.AppAdapter
import com.custom.hisenselauncher.ui.MediaCardAdapter
import com.custom.hisenselauncher.ui.Theme
import com.custom.hisenselauncher.ui.Ui
import com.custom.hisenselauncher.ui.applyFocusStyle
import com.custom.hisenselauncher.ui.cardBackground
import com.custom.hisenselauncher.wallpaper.JellyfishView
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Search installed apps + movies/shows (Cinemeta). Titles open in Nuvio. Supports voice. */
class SearchActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var appsLabel: TextView
    private lateinit var appsRv: RecyclerView
    private lateinit var moviesLabel: TextView
    private lateinit var moviesRv: RecyclerView
    private lateinit var showsLabel: TextView
    private lateinit var showsRv: RecyclerView
    private lateinit var appAdapter: AppAdapter
    private lateinit var movieAdapter: MediaCardAdapter
    private lateinit var showAdapter: MediaCardAdapter

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private var pending: Future<*>? = null
    private val debounce = Runnable { runSearch(input.text.toString().trim()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        setContentView(build())
        com.custom.hisenselauncher.ui.NightMode.attach(this)
        io.execute { val list = AppRepository(this).loadAll(); main.post { apps = list } }
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) startVoice() else input.requestFocus()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(JellyfishView(this, brightness = 0.5f), FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(90), ui.px(50), ui.px(90), ui.px(30))
            clipChildren = false; clipToPadding = false
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        input = EditText(this).apply {
            hint = "Search movies, shows and apps"
            setHintTextColor(Theme.TEXT_DIM); setTextColor(Theme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(34f))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = cardBackground(ui, 40f)
            setPadding(ui.px(34), ui.px(18), ui.px(34), ui.px(18))
            applyFocusStyle(ui, 40f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { main.removeCallbacks(debounce); main.postDelayed(debounce, 450) }
            })
            setOnEditorActionListener { _, _, _ -> main.removeCallbacks(debounce); debounce.run(); false }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "🎤  Voice"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(26f)); setTextColor(Theme.TEXT)
            background = cardBackground(ui, 40f)
            setPadding(ui.px(30), ui.px(18), ui.px(30), ui.px(18))
            applyFocusStyle(ui, 40f)
            setOnClickListener { startVoice() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(20) })
        col.addView(bar)

        status = label("Type or say a title. Results open in Nuvio.", 22f, Theme.TEXT_DIM, bold = false)
        col.addView(status)

        appAdapter = AppAdapter(ui, 200f, { e -> e.launchIntent?.let { prefs.pushRecent(e.pkg); startActivity(it) } }, { _, _ -> })
        movieAdapter = MediaCardAdapter(ui, landscape = false, onClick = ::openTitle)
        showAdapter = MediaCardAdapter(ui, landscape = false, onClick = ::openTitle)
        appsLabel = label("Apps"); appsRv = rail(appAdapter, ui.px(180))
        moviesLabel = label("Movies"); moviesRv = rail(movieAdapter, ui.px(370))
        showsLabel = label("Shows"); showsRv = rail(showAdapter, ui.px(370))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        listOf(appsLabel, appsRv, moviesLabel, moviesRv, showsLabel, showsRv).forEach { results.addView(it); it.visibility = View.GONE }
        col.addView(ScrollView(this).apply { clipChildren = false; isVerticalScrollBarEnabled = false; addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun runSearch(q: String) {
        pending?.cancel(true)
        if (q.length < 2) { show(emptyList(), emptyList(), emptyList()); status.text = "Type or say a title. Results open in Nuvio."; return }
        status.text = "Searching “$q”…"
        val matchedApps = apps.filter { it.label.contains(q, ignoreCase = true) }
        pending = io.submit {
            val movies = runCatching { Cinemeta.search("movie", q) }.getOrDefault(emptyList())
            val shows = runCatching { Cinemeta.search("series", q) }.getOrDefault(emptyList())
            fun map(list: List<com.custom.hisenselauncher.data.Title>) = list.take(20).map {
                PosterItem(it.name, it.year, it.poster, imdb = it.imdb, type = it.type, rating = it.rating)
            }
            main.post {
                if (isFinishing || input.text.toString().trim() != q) return@post
                show(matchedApps, map(movies), map(shows))
                status.text = if (matchedApps.isEmpty() && movies.isEmpty() && shows.isEmpty()) "No results for “$q”." else "Results for “$q”"
            }
        }
    }

    private fun show(a: List<AppEntry>, m: List<PosterItem>, s: List<PosterItem>) {
        appAdapter.submit(a); movieAdapter.submit(m); showAdapter.submit(s)
        listOf(appsLabel to appsRv to a.isNotEmpty(), moviesLabel to moviesRv to m.isNotEmpty(), showsLabel to showsRv to s.isNotEmpty())
            .forEach { (views, visible) -> val v = if (visible) View.VISIBLE else View.GONE; views.first.visibility = v; views.second.visibility = v }
    }

    private fun openTitle(p: PosterItem) {
        val imdb = p.imdb ?: return
        if (!TitleLauncher.open(this, p.type ?: "movie", imdb)) Toast.makeText(this, "Nuvio isn't installed", Toast.LENGTH_SHORT).show()
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie, show or app")
        try { startActivityForResult(i, REQ_VOICE) } catch (e: Exception) {
            Toast.makeText(this, "Voice input isn't available", Toast.LENGTH_SHORT).show(); input.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !spoken.isNullOrBlank()) {
            input.setText(spoken); input.setSelection(spoken.length)
            main.removeCallbacks(debounce); runSearch(spoken)
            main.postDelayed({ (moviesRv.takeIf { it.isShown } ?: showsRv).findViewHolderForAdapterPosition(0)?.itemView?.let { v ->
                (v as? ViewGroup)?.getChildAt(0)?.requestFocus() ?: v.requestFocus()
            } }, 1500)
        } else input.requestFocus()
    }

    private fun label(s: String, size: Float = 26f, color: Int = Theme.TEXT, bold: Boolean = true) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.f(size)); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(ui.px(10), ui.px(22), 0, ui.px(4))
    }

    private fun rail(adapter: RecyclerView.Adapter<*>, height: Int) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@SearchActivity, LinearLayoutManager.HORIZONTAL, false)
        this.adapter = adapter
        clipChildren = false; clipToPadding = false; isFocusable = false; itemAnimator = null
        setPadding(0, ui.px(6), 0, ui.px(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    companion object {
        const val EXTRA_VOICE = "voice"
        private const val REQ_VOICE = 41
    }
}
