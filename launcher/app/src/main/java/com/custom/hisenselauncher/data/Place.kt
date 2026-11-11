package com.custom.hisenselauncher.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the TV is: drives weather, alerts and the night sky. */
data class Place(val name: String, val lat: Double, val lon: Double) {
    /** "Bozeman" from "Bozeman, Montana". */
    val shortName: String get() = name.substringBefore(',').trim()
}

/**
 * The saved location. Set it from ☰ → Location on the TV, or over adb:
 *   adb shell am start -n com.custom.hisenselauncher/.MainActivity \
 *     --es set_place "Denver, Colorado" --ef lat 39.7392 --ef lon -104.9903
 */
object Places {
    val DEFAULT = Place("Bozeman, Montana", 45.6770, -111.0429)

    fun current(prefs: Prefs): Place {
        val name = prefs.placeName ?: return DEFAULT
        val lat = prefs.placeLat?.toDoubleOrNull() ?: return DEFAULT
        val lon = prefs.placeLon?.toDoubleOrNull() ?: return DEFAULT
        return Place(name, lat, lon)
    }

    fun save(prefs: Prefs, p: Place) {
        prefs.placeName = p.name
        prefs.placeLat = p.lat.toString()
        prefs.placeLon = p.lon.toString()
        prefs.weatherFetchedAt = 0L   // refetch weather for the new place
    }

    /** Open-Meteo geocoding (free, no key). Blocking. */
    fun search(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val conn = URL("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        return try {
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country_code"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                Place(label, o.getDouble("latitude"), o.getDouble("longitude"))
            }
        } finally { conn.disconnect() }
    }

    fun distanceKm(a: Place, b: Place): Double {
        val r = Math.toRadians(1.0)
        val dLat = (b.lat - a.lat) * r; val dLon = (b.lon - a.lon) * r
        val h = sin(dLat / 2).let { it * it } + cos(a.lat * r) * cos(b.lat * r) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(h))
    }
}
