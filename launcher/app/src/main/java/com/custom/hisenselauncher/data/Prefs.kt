package com.custom.hisenselauncher.data

import android.content.Context

/** All launcher state lives here: favorites order, recently launched apps, weather cache, Trakt tokens. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    val hasFavorites: Boolean get() = sp.contains(KEY_FAVS)

    var favorites: List<String>
        get() = split(sp.getString(KEY_FAVS, null))
        set(v) { sp.edit().putString(KEY_FAVS, v.joinToString(",")).apply() }

    val recents: List<String> get() = split(sp.getString(KEY_RECENTS, null))

    fun pushRecent(pkg: String) {
        val list = (listOf(pkg) + recents.filter { it != pkg }).take(MAX_RECENTS)
        sp.edit().putString(KEY_RECENTS, list.joinToString(",")).apply()
    }

    var weatherJson: String?
        get() = sp.getString(KEY_WEATHER, null)
        set(v) { sp.edit().putString(KEY_WEATHER, v).apply() }

    var weatherFetchedAt: Long
        get() = sp.getLong(KEY_WEATHER_TS, 0L)
        set(v) { sp.edit().putLong(KEY_WEATHER_TS, v).apply() }

    var traktAccess: String?
        get() = sp.getString("trakt_access", null)
        set(v) { sp.edit().putString("trakt_access", v).apply() }

    var traktRefresh: String?
        get() = sp.getString("trakt_refresh", null)
        set(v) { sp.edit().putString("trakt_refresh", v).apply() }

    var traktExpires: Long
        get() = sp.getLong("trakt_expires", 0L)
        set(v) { sp.edit().putLong("trakt_expires", v).apply() }

    var screensaverTheme: String?
        get() = sp.getString("screensaver_theme", null)
        set(v) { sp.edit().putString("screensaver_theme", v).apply() }

    var nightMode: String?
        get() = sp.getString("night_mode", null)
        set(v) { sp.edit().putString("night_mode", v).apply() }

    var placeName: String?
        get() = sp.getString("place_name", null)
        set(v) { sp.edit().putString("place_name", v).apply() }

    var placeLat: String?
        get() = sp.getString("place_lat", null)
        set(v) { sp.edit().putString("place_lat", v).apply() }

    var placeLon: String?
        get() = sp.getString("place_lon", null)
        set(v) { sp.edit().putString("place_lon", v).apply() }

    var auroraJson: String?
        get() = sp.getString("aurora_json", null)
        set(v) { sp.edit().putString("aurora_json", v).apply() }

    /** Short text for the top-bar ski pill, e.g. "Bridger 6\" new". */
    var skiPill: String?
        get() = sp.getString("ski_pill", null)
        set(v) { sp.edit().putString("ski_pill", v).apply() }

    private fun split(s: String?) = s?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    companion object {
        private const val KEY_FAVS = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val KEY_WEATHER = "weather_json"
        private const val KEY_WEATHER_TS = "weather_ts"
        const val MAX_RECENTS = 12
    }
}
