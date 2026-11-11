package com.custom.hisenselauncher.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Weather(val tempF: Int, val code: Int, val isDay: Boolean, val hiF: Int, val loF: Int) {
    val icon: String
        get() = when (code) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "🌤️" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..57 -> "🌦️"
            in 61..67, in 80..82 -> "🌧️"
            in 71..77, 85, 86 -> "❄️"
            in 95..99 -> "⛈️"
            else -> "🌡️"
        }

    val description: String
        get() = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67, in 80..82 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Thunderstorm"
            else -> ""
        }
}

/** Open-Meteo (free, no API key) for the saved [Place]. Cached in prefs; refresh is driven by the caller (30 min). */
class WeatherRepository(private val prefs: Prefs) {

    fun cached(): Weather? = prefs.weatherJson?.let { runCatching { parse(it) }.getOrNull() }

    val isStale: Boolean get() = System.currentTimeMillis() - prefs.weatherFetchedAt > REFRESH_MS

    /** Blocking network call - run off the main thread. Returns null on failure (cache stays). */
    fun fetch(): Weather? = runCatching {
        val place = Places.current(prefs)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val w = parse(body)
            prefs.weatherJson = body
            prefs.weatherFetchedAt = System.currentTimeMillis()
            w
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun parse(json: String): Weather {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        return Weather(
            tempF = Math.round(cur.getDouble("temperature_2m")).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            hiF = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            loF = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
        )
    }

    companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
    }
}
