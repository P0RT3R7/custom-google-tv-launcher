package com.custom.hisenselauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.custom.hisenselauncher.data.AppEntry
import com.custom.hisenselauncher.data.AppRepository
import com.custom.hisenselauncher.data.Feed
import com.custom.hisenselauncher.data.HomeFeed
import com.custom.hisenselauncher.data.Place
import com.custom.hisenselauncher.data.Places
import com.custom.hisenselauncher.data.PosterItem
import com.custom.hisenselauncher.data.Prefs
import com.custom.hisenselauncher.data.TitleLauncher
import com.custom.hisenselauncher.data.Trakt
import com.custom.hisenselauncher.data.UsageRepository
import com.custom.hisenselauncher.data.WatchHistoryStore
import com.custom.hisenselauncher.data.Weather
import com.custom.hisenselauncher.data.WeatherRepository
import com.custom.hisenselauncher.dream.ScreensaverPreviewActivity
import com.custom.hisenselauncher.dream.ScreensaverTheme
import com.custom.hisenselauncher.media.MediaSessionHub
import com.custom.hisenselauncher.search.SearchActivity
import com.custom.hisenselauncher.ui.AppAdapter
import com.custom.hisenselauncher.ui.MediaCardAdapter
import com.custom.hisenselauncher.ui.NightMode
import com.custom.hisenselauncher.ui.NightOverlay
import com.custom.hisenselauncher.ui.NightSetting
import com.custom.hisenselauncher.ui.NowPlayingBar
import com.custom.hisenselauncher.ui.Theme
import com.custom.hisenselauncher.ui.Ui
import com.custom.hisenselauncher.ui.applyFocusStyle
import com.custom.hisenselauncher.ui.cardBackground
import com.custom.hisenselauncher.wallpaper.WallpaperLayer
import com.custom.hisenselauncher.weather.WeatherActivity
import com.custom.hisenselauncher.weather.SkiActivity
import com.custom.hisenselauncher.data.AuroraOutlook
import com.custom.hisenselauncher.data.AuroraRepository
import com.custom.hisenselauncher.data.SkiRepository
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var appRepo: AppRepository
    private lateinit var usageRepo: UsageRepository
    private lateinit var weatherRepo: WeatherRepository
    private lateinit var feedBuilder: HomeFeed
    private lateinit var trakt: Trakt
    private lateinit var history: WatchHistoryStore

    private lateinit var wallpaper: WallpaperLayer
    private lateinit var night: NightOverlay
    private lateinit var content: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var allAppsPane: LinearLayout
    private lateinit var nowPlaying: NowPlayingBar
    private lateinit var weatherIcon: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherHiLo: TextView
    private lateinit var skiPill: TextView
    private lateinit var auroraPill: TextView
    private var lastOutdoorFetch = 0L

    private lateinit var continueRow: Row
    private lateinit var favRow: Row
    private lateinit var recentRow: Row
    private lateinit var appsLatelyRow: Row
    private lateinit var suggestionBox: LinearLayout
    private lateinit var hint: TextView

    private lateinit var continueAdapter: MediaCardAdapter
    private lateinit var recentAdapter: MediaCardAdapter
    private lateinit var favAdapter: AppAdapter
    private lateinit var appsLatelyAdapter: AppAdapter
    private lateinit var allAdapter: AppAdapter
    private lateinit var allRv: RecyclerView

    private val io = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private var allApps: List<AppEntry> = emptyList()
    private var allAppsMode = false
    private var firstFocusDone = false
    private var lastFeedAt = 0L
    private var feedLoading = false

    /** A titled horizontal rail. */
    private class Row(val label: TextView, val rv: RecyclerView) {
        fun show(visible: Boolean) { val v = if (visible) View.VISIBLE else View.GONE; label.visibility = v; rv.visibility = v }
    }

    private val weatherTick = object : Runnable {
        override fun run() { fetchWeather(); handler.postDelayed(this, WeatherRepository.REFRESH_MS) }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = reloadApps()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        appRepo = AppRepository(this)
        usageRepo = UsageRepository(this)
        weatherRepo = WeatherRepository(prefs)
        feedBuilder = HomeFeed(this, prefs)
        trakt = Trakt(prefs)
        history = WatchHistoryStore(this)
        setContentView(buildUi())
        night = NightMode.attach(this)
        applyPlaceExtra(intent)

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        weatherRepo.cached()?.let(::showWeather)
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        wallpaper.start()
        night.refresh(animate = false)
        if (MediaSessionHub.hasAccess(this)) MediaSessionHub.attach(this)
        nowPlaying.render()
        rebuildAppRows()
        if (System.currentTimeMillis() - lastFeedAt > 10_000L) loadFeed()   // network parts are cached, so this is cheap
        handler.removeCallbacks(weatherTick)
        if (weatherRepo.isStale) handler.post(weatherTick) else handler.postDelayed(weatherTick, WeatherRepository.REFRESH_MS)
        renderOutdoor()
        if (System.currentTimeMillis() - lastOutdoorFetch > 30 * 60_000L) fetchOutdoor()
    }

    override fun onPause() {
        handler.removeCallbacks(weatherTick)
        wallpaper.stop()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        io.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyPlaceExtra(intent)
        if (intent?.action == Intent.ACTION_MAIN) {
            if (allAppsMode) showAllApps(false)
            homeScroll.smoothScrollTo(0, 0)
            focusFirstFavorite()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            allAppsMode -> showAllApps(false)
            homeScroll.scrollY > 0 -> { homeScroll.smoothScrollTo(0, 0); focusFirstFavorite() }
        }
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaper = WallpaperLayer(this)
        root.addView(wallpaper, match())

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(80), ui.px(36), ui.px(80), 0)
            clipChildren = false; clipToPadding = false
        }
        content.addView(buildTopBar())

        // ---- home rows (scrolling)
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, 0, 0, ui.px(120))
        }
        nowPlaying = NowPlayingBar(this, ui)
        rows.addView(nowPlaying, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(18) })

        continueAdapter = MediaCardAdapter(ui, landscape = true, onClick = ::openPoster, onMenu = ::showPosterMenu)
        continueRow = row(rows, "Continue Watching", continueAdapter, ui.px(225))

        favAdapter = AppAdapter(ui, 230f, ::onFavClick, ::showAppMenu)
        favRow = row(rows, "Favorites", favAdapter, ui.px(205))

        recentAdapter = MediaCardAdapter(ui, landscape = true, onClick = ::openPoster, onMenu = ::showPosterMenu)
        recentRow = row(rows, "Recently Watched", recentAdapter, ui.px(225))

        suggestionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        rows.addView(suggestionBox)

        appsLatelyAdapter = AppAdapter(ui, 190f, ::launchApp, ::showAppMenu)
        appsLatelyRow = row(rows, "Your Apps Lately", appsLatelyAdapter, ui.px(190))

        hint = TextView(this).apply { px(ui.f(20f)); setTextColor(Theme.TEXT_DIM); setPadding(ui.px(10), ui.px(20), 0, 0); visibility = View.GONE }
        rows.addView(hint)

        continueRow.show(false); recentRow.show(false); appsLatelyRow.show(false)

        homeScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            isSmoothScrollingEnabled = true
            setFadingEdgeLength(ui.px(60)); isVerticalFadingEdgeEnabled = true
            addView(rows)
        }
        content.addView(homeScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- all apps
        allAppsPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; clipChildren = false }
        allAdapter = AppAdapter(ui, 230f, ::launchApp, ::showAppMenu)
        allRv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 6)
            adapter = allAdapter
            clipChildren = false; clipToPadding = false
            setPadding(0, ui.px(14), 0, ui.px(40))
            isFocusable = false
        }
        allAppsPane.addView(label("All Apps"))
        allAppsPane.addView(allRv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(allAppsPane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(content, match())
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            px(ui.f(72f)); setTextColor(Theme.TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        })
        left.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
            px(ui.f(26f)); setTextColor(Theme.TEXT_DIM)
        })
        bar.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // weather (focusable -> full forecast)
        val weather = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(18), ui.px(8), ui.px(22), ui.px(8))
            background = cardBackground(ui, 40f).apply { setColor(Color.argb(60, 8, 12, 18)) }
            applyFocusStyle(ui, 40f)
            setOnClickListener { startActivity(Intent(this@MainActivity, WeatherActivity::class.java)) }
        }
        weatherIcon = TextView(this).apply { px(ui.f(46f)) }
        weatherTemp = TextView(this).apply { px(ui.f(44f)); setTextColor(Theme.TEXT); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); includeFontPadding = false }
        weatherHiLo = TextView(this).apply { px(ui.f(20f)); setTextColor(Theme.TEXT_DIM) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(14), 0, 0, 0) }
        col.addView(weatherTemp); col.addView(weatherHiLo)
        weather.addView(weatherIcon); weather.addView(col)
        bar.addView(weather)

        auroraPill = pill("🌌 Aurora tonight", ::showAurora).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = ui.f(40f); setColor(Color.argb(170, 20, 150, 90)) }
            visibility = View.GONE
        }
        bar.addView(auroraPill, pillLp())
        skiPill = pill("⛷ Ski report") { startActivity(Intent(this, SkiActivity::class.java)) }.apply { visibility = View.GONE }
        bar.addView(skiPill, pillLp())
        bar.addView(pill("🔍 Search") { startActivity(Intent(this, SearchActivity::class.java)) }, pillLp())
        bar.addView(pill("HDMI Inputs", ::showHdmiInputs), pillLp())
        bar.addView(pill("Settings", ::openSettings), pillLp())
        bar.addView(pill("☰", ::showLauncherMenu), pillLp())
        return bar
    }

    private fun pillLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(18) }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        px(ui.f(22f)); setTextColor(Theme.TEXT); gravity = Gravity.CENTER
        setPadding(ui.px(28), ui.px(14), ui.px(28), ui.px(14))
        background = cardBackground(ui, 40f)
        applyFocusStyle(ui, 40f)
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        px(ui.f(26f)); setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD
        setPadding(ui.px(10), ui.px(20), 0, ui.px(2))
    }

    private fun row(parent: LinearLayout, title: String, adapter: RecyclerView.Adapter<*>, height: Int): Row {
        val l = label(title)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            clipChildren = false; clipToPadding = false
            setPadding(0, ui.px(6), ui.px(40), ui.px(6))
            isFocusable = false
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
        parent.addView(l); parent.addView(rv)
        return Row(l, rv)
    }

    private fun TextView.px(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // ---------------------------------------------------------------- data

    private fun reloadApps() {
        io.execute {
            val list = appRepo.loadAll()
            handler.post {
                allApps = list
                if (!prefs.hasFavorites && list.isNotEmpty()) prefs.favorites = appRepo.defaultFavorites(list)
                rebuildAppRows()
            }
        }
    }

    private fun rebuildAppRows() {
        if (!::favAdapter.isInitialized || allApps.isEmpty()) return
        val byPkg = allApps.associateBy { it.pkg }
        favAdapter.submit(prefs.favorites.mapNotNull { byPkg[it] } + AppEntry("", "All Apps", null, false, null, isAllAppsTile = true))
        allAdapter.submit(allApps)
        io.execute {
            val usage = usageRepo.recentPackages()
            handler.post {
                val pkgs = (usage.ifEmpty { prefs.recents }).filter { it in byPkg }.take(14)
                appsLatelyAdapter.submit(pkgs.mapNotNull { byPkg[it] })
                appsLatelyRow.show(pkgs.isNotEmpty())
            }
        }
        if (!firstFocusDone) { firstFocusDone = true; focusFirstFavorite() }
    }

    private fun loadFeed() {
        if (feedLoading) return
        feedLoading = true
        io.execute {
            val feed = runCatching { feedBuilder.build() }.getOrNull()
            handler.post {
                feedLoading = false
                lastFeedAt = System.currentTimeMillis()
                feed?.let(::renderFeed)
            }
        }
    }

    private fun renderFeed(feed: Feed) {
        continueAdapter.submit(feed.continueWatching)
        continueRow.show(feed.continueWatching.isNotEmpty())
        recentAdapter.submit(feed.recentlyWatched)
        recentRow.show(feed.recentlyWatched.isNotEmpty())

        suggestionBox.removeAllViews()
        feed.suggestions.forEach { r ->
            val adapter = MediaCardAdapter(ui, landscape = r.landscape, onClick = ::openPoster, onMenu = ::showPosterMenu)
            row(suggestionBox, r.title, adapter, ui.px(if (r.landscape) 225 else 370))
            adapter.submit(r.items)
        }

        val missing = setupMissing()
        hint.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        hint.text = "Setup: " + missing.joinToString(" · ") + "  (☰ menu → Setup status)"
    }

    private fun setupMissing(): List<String> = buildList {
        if (!MediaSessionHub.hasAccess(this@MainActivity)) add("media access off — Recently Watched can't record titles")
        if (!usageRepo.hasAccess()) add("usage access off — Your Apps Lately uses launcher history only")
    }

    /** Aurora outlook (every 30 min) and ski pill text (refreshed with it). */
    private fun fetchOutdoor() {
        lastOutdoorFetch = System.currentTimeMillis()
        io.execute {
            AuroraRepository(prefs).fetch()
            runCatching { if (SkiRepository(this, prefs).nearby().isNotEmpty()) SkiRepository(this, prefs).fetch() else prefs.skiPill = null }
            handler.post { renderOutdoor() }
        }
    }

    private fun renderOutdoor() {
        val ski = SkiRepository(this, prefs).nearby().isNotEmpty()
        skiPill.visibility = if (ski) View.VISIBLE else View.GONE
        skiPill.text = "⛷ " + (prefs.skiPill ?: "Ski report")
        val a = AuroraRepository(prefs).cached()
        val lvl = a?.level ?: AuroraOutlook.Level.NONE
        auroraPill.visibility = if (lvl == AuroraOutlook.Level.NONE) View.GONE else View.VISIBLE
        auroraPill.text = when (lvl) {
            AuroraOutlook.Level.STRONG -> "🌌 Strong aurora tonight"
            AuroraOutlook.Level.LIKELY -> "🌌 Aurora tonight"
            else -> "🌌 Aurora: slight chance"
        }
        (auroraPill.background as? android.graphics.drawable.GradientDrawable)?.setColor(
            if (lvl == AuroraOutlook.Level.SLIGHT) Color.argb(110, 20, 80, 60) else Color.argb(170, 20, 150, 90))
    }

    private fun showAurora() {
        val a = AuroraRepository(prefs).cached() ?: return
        darkDialog().setTitle(a.headline)
            .setMessage(a.detail + "\n\nBest chance: a dark spot away from town lights, facing north, between about 10 PM and 2 AM.")
            .setPositiveButton("Show the sky") { _, _ -> previewScreensaver(ScreensaverTheme.NIGHT_SKY) }
            .setNegativeButton("Close", null).show()
    }

    private fun fetchWeather() {
        io.execute { weatherRepo.fetch()?.let { w -> handler.post { showWeather(w) } } }
    }

    /** adb: am start -n com.custom.hisenselauncher/.MainActivity --es set_place "City, State" --ef lat 0.0 --ef lon 0.0 */
    private fun applyPlaceExtra(i: Intent?) {
        val name = i?.getStringExtra("set_place") ?: return
        if (!i.hasExtra("lat") || !i.hasExtra("lon")) return
        setPlace(Place(name, i.getFloatExtra("lat", 0f).toDouble(), i.getFloatExtra("lon", 0f).toDouble()))
        i.removeExtra("set_place")
    }

    private fun setPlace(p: Place) {
        Places.save(prefs, p)
        fetchWeather()
        toast("Location set to ${p.name}")
    }

    /** ☰ → Location: type a city, pick from Open-Meteo geocoding results. */
    private fun pickLocation() {
        val input = android.widget.EditText(this).apply {
            hint = "City name, e.g. Denver"
            setSingleLine()
            setText(Places.current(prefs).shortName)
            selectAll()
        }
        darkDialog().setTitle("Location").setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString()
                if (q.isBlank()) return@setPositiveButton
                io.execute {
                    val results = runCatching { Places.search(q) }.getOrDefault(emptyList())
                    handler.post {
                        if (results.isEmpty()) { toast("No places found for \"$q\""); return@post }
                        darkDialog().setTitle("Pick your location")
                            .setItems(results.map { it.name }.toTypedArray()) { _, w -> setPlace(results[w]) }.show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showWeather(w: Weather) {
        weatherIcon.text = w.icon
        weatherTemp.text = "${w.tempF}°F"
        weatherHiLo.text = "H ${w.hiF}°  L ${w.loF}°  ${w.description}"
    }

    // ---------------------------------------------------------------- actions

    private fun onFavClick(e: AppEntry) { if (e.isAllAppsTile) showAllApps(true) else launchApp(e) }

    private fun launchApp(e: AppEntry) {
        val intent = e.launchIntent ?: packageManager.getLaunchIntentForPackage(e.pkg) ?: return
        prefs.pushRecent(e.pkg)
        try { startActivity(intent) } catch (ex: Exception) { toast("Can't open ${e.label}") }
    }

    private fun openPoster(p: PosterItem) {
        try {
            when {
                p.intent != null -> startActivity(p.intent)
                p.imdb != null -> if (!TitleLauncher.open(this, p.type ?: "movie", p.imdb, p.season, p.episode)) toast("Nuvio isn't installed")
                p.pkg != null -> packageManager.getLaunchIntentForPackage(p.pkg)?.let(::startActivity)
            }
        } catch (ex: Exception) {
            p.pkg?.let { packageManager.getLaunchIntentForPackage(it) }?.let(::startActivity) ?: toast("Can't open ${p.title}")
        }
    }

    private fun showPosterMenu(p: PosterItem) {
        val labels = ArrayList<String>(); val actions = ArrayList<() -> Unit>()
        if (p.imdb != null) {
            labels += "Open in Nuvio"; actions += { TitleLauncher.open(this, p.type ?: "movie", p.imdb, p.season, p.episode, TitleLauncher.NUVIO) }
            labels += "Open in Stremio"; actions += { TitleLauncher.open(this, p.type ?: "movie", p.imdb, p.season, p.episode, TitleLauncher.STREMIO) }
        }
        p.pkg?.let { pkg ->
            val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            labels += "Open $name"; actions += { packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) }
        }
        p.historyKey?.let { key ->
            labels += "Remove from history"; actions += { io.execute { history.remove(key); handler.post { lastFeedAt = 0; loadFeed() } } }
        }
        if (labels.isEmpty()) return
        darkDialog().setTitle(p.title).setItems(labels.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun showAllApps(show: Boolean) {
        allAppsMode = show
        homeScroll.visibility = if (show) View.GONE else View.VISIBLE
        allAppsPane.visibility = if (show) View.VISIBLE else View.GONE
        if (show) allRv.post { allRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() } else focusFirstFavorite()
    }

    private fun focusFirstFavorite() {
        favRow.rv.scrollToPosition(0)
        favRow.rv.post { favRow.rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun openSettings() {
        try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) {
            startActivity(Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun showHdmiInputs() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val inputs: List<TvInputInfo> = tim.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(this) }
        if (inputs.isEmpty()) {
            toast("No HDMI inputs reported - opening Live TV")
            packageManager.getLaunchIntentForPackage("com.vt.tv.tvcenter")?.let(::startActivity)
            return
        }
        val names = inputs.map { info -> (info.loadCustomLabel(this) ?: info.loadLabel(this))?.toString().orEmpty().ifBlank { "HDMI" } }
        darkDialog().setTitle("HDMI Inputs").setItems(names.toTypedArray()) { _, which ->
            val uri: Uri = TvContract.buildChannelUriForPassthroughInput(inputs[which].id)
            try { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { toast("Couldn't switch input") }
        }.show()
    }

    /** ☰ menu: Trakt, refresh, screensaver, history, setup status. */
    private fun showLauncherMenu() {
        val labels = ArrayList<String>(); val actions = ArrayList<() -> Unit>()
        if (trakt.linked) { labels += "Trakt: linked — Unlink"; actions += { trakt.unlink(); toast("Trakt unlinked"); lastFeedAt = 0; loadFeed() } }
        else { labels += "Link Trakt account"; actions += ::linkTrakt }
        labels += "Location: ${Places.current(prefs).name}"; actions += ::pickLocation
        labels += "Refresh suggestions"; actions += { lastFeedAt = 0; loadFeed(); toast("Refreshing…") }
        labels += "Screensaver theme: ${ScreensaverTheme.from(prefs.screensaverTheme).label}"; actions += ::pickScreensaver
        labels += "Night mode: ${NightMode.setting(this).label}"; actions += ::pickNightMode
        labels += "Preview screensaver"; actions += { previewScreensaver(ScreensaverTheme.from(prefs.screensaverTheme)) }
        labels += "Clear watch history"; actions += {
            darkDialog().setTitle("Clear local watch history?").setPositiveButton("Clear") { _, _ ->
                io.execute { history.all().forEach { history.remove(it.key) }; handler.post { lastFeedAt = 0; loadFeed() } }
            }.setNegativeButton("Cancel", null).show()
        }
        labels += "Setup status"; actions += ::showSetupStatus
        darkDialog().setTitle("Launcher").setItems(labels.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun showSetupStatus() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val msg = buildString {
            appendLine("${mark(MediaSessionHub.hasAccess(this@MainActivity))} Media access (Now Playing + Recently Watched)")
            appendLine("${mark(usageRepo.hasAccess())} Usage access (Your Apps Lately)")
            val readerOn = (android.provider.Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: "").contains("TitleReaderService")
            appendLine("${mark(readerOn)} Title reader (Hulu, Netflix… titles in Continue Watching)")
            appendLine("${mark(trakt.configured)} Trakt client ID built in")
            appendLine("${mark(trakt.linked)} Trakt account linked")
            appendLine()
            append("Access is granted from the PC with adb — see launcher/README.md.")
        }
        darkDialog().setTitle("Setup status").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun pickScreensaver() {
        val themes = ScreensaverTheme.entries
        val current = themes.indexOf(ScreensaverTheme.from(prefs.screensaverTheme))
        darkDialog().setTitle("Screensaver theme")
            .setSingleChoiceItems(themes.map { it.label }.toTypedArray(), current) { d, which ->
                prefs.screensaverTheme = themes[which].name
                d.dismiss()
                previewScreensaver(themes[which])
            }.show()
    }

    private fun pickNightMode() {
        val options = NightSetting.entries
        darkDialog().setTitle("Night mode")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(NightMode.setting(this))) { d, which ->
                prefs.nightMode = options[which].name
                night.refresh(animate = true)
                d.dismiss()
            }.show()
    }

    private fun previewScreensaver(theme: ScreensaverTheme) {
        startActivity(Intent(this, ScreensaverPreviewActivity::class.java).putExtra(ScreensaverPreviewActivity.EXTRA_THEME, theme.name))
    }

    private fun linkTrakt() {
        if (!trakt.configured) {
            darkDialog().setTitle("Trakt isn't set up yet")
                .setMessage("Create a free API app at trakt.tv/oauth/applications (redirect URI urn:ietf:wg:oauth:2.0:oob), put its Client ID in launcher/trakt.properties, and rebuild.")
                .setPositiveButton("OK", null).show()
            return
        }
        val dialog = darkDialog().setTitle("Link Trakt").setMessage("Getting a code…").setNegativeButton("Cancel", null).create()
        var cancelled = false
        dialog.setOnDismissListener { cancelled = true }
        dialog.show()
        io.execute {
            val code = runCatching { trakt.startDeviceAuth() }.getOrNull()
            if (code == null) { handler.post { dialog.setMessage("Couldn't reach Trakt. Try again later.") }; return@execute }
            handler.post { dialog.setMessage("On your phone, open\n\n${code.url}\n\nand enter the code\n\n${code.userCode}") }
            var interval = code.interval * 1000L
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            while (!cancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                when (trakt.pollToken(code.deviceCode)) {
                    Trakt.Poll.SUCCESS -> { handler.post { dialog.dismiss(); toast("Trakt linked!"); lastFeedAt = 0; loadFeed() }; return@execute }
                    Trakt.Poll.SLOW_DOWN -> interval += 1000
                    Trakt.Poll.EXPIRED, Trakt.Poll.DENIED -> { handler.post { dialog.setMessage("Code expired or was denied. Try again.") }; return@execute }
                    else -> Unit
                }
            }
        }
    }

    /** Long-press (D-pad center hold) menu for app tiles: pin/unpin, reorder, app info. */
    private fun showAppMenu(e: AppEntry, anchor: View) {
        if (e.isAllAppsTile) return
        val favs = prefs.favorites.toMutableList()
        val pinned = e.pkg in favs
        val labels = ArrayList<String>(); val actions = ArrayList<() -> Unit>()
        labels += "Open"; actions += { launchApp(e) }
        if (pinned) {
            labels += "Unpin from Favorites"; actions += { favs.remove(e.pkg); saveFavs(favs) }
            val idx = favs.indexOf(e.pkg)
            if (idx > 0) { labels += "Move left"; actions += { favs.swap(idx, idx - 1); saveFavs(favs); refocusFavorite(e.pkg) } }
            if (idx < favs.size - 1) { labels += "Move right"; actions += { favs.swap(idx, idx + 1); saveFavs(favs); refocusFavorite(e.pkg) } }
        } else {
            labels += "Pin to Favorites"; actions += { favs.add(e.pkg); saveFavs(favs) }
        }
        labels += "App info"; actions += {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (ex: Exception) { toast("App info unavailable") }
        }
        darkDialog().setTitle(e.label).setItems(labels.toTypedArray()) { _, which -> actions[which]() }.show()
    }

    private fun MutableList<String>.swap(a: Int, b: Int) { val t = this[a]; this[a] = this[b]; this[b] = t }
    private fun saveFavs(list: List<String>) { prefs.favorites = list; rebuildAppRows() }

    private fun refocusFavorite(pkg: String) {
        val pos = prefs.favorites.indexOf(pkg)
        if (pos < 0) return
        favRow.rv.scrollToPosition(pos)
        favRow.rv.post { favRow.rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun darkDialog() = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
}
