package com.custom.hisenselauncher.weather

import com.custom.hisenselauncher.data.WeatherRepository
import com.custom.hisenselauncher.net.Http
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** WMO weather code helpers shared by the widget and the detail screen. */
object WeatherCodes {
    fun icon(code: Int, isDay: Boolean = true): String = when (code) {
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

    fun text(code: Int): String = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 80..82 -> "Showers"
        in 71..77 -> "Snow"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> ""
    }

    fun compass(deg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return dirs[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    }
}

data class Hour(val time: LocalDateTime, val tempF: Int, val code: Int, val isDay: Boolean, val precip: Int)
data class Day(val date: LocalDateTime, val hiF: Int, val loF: Int, val code: Int, val precip: Int, val windMph: Int, val sunrise: String, val sunset: String)
data class Alert(val event: String, val headline: String, val severity: String, val ends: String?)

data class WeatherDetail(
    val tempF: Int, val feelsF: Int, val code: Int, val isDay: Boolean,
    val humidity: Int, val windMph: Int, val gustMph: Int, val windDir: String, val uv: Double,
    val hours: List<Hour>, val days: List<Day>, val alerts: List<Alert>,
)

/** Full forecast from Open-Meteo + active alerts from the US National Weather Service. Blocking. */
object WeatherDetailRepository {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val clock = DateTimeFormatter.ofPattern("h:mm a")

    fun fetch(place: com.custom.hisenselauncher.data.Place): WeatherDetail {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,wind_gusts_10m,wind_direction_10m,uv_index" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=7"
        val o = JSONObject(Http.get(url))
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val daily = o.getJSONObject("daily")

        val now = LocalDateTime.now(zone).withMinute(0).withSecond(0).withNano(0)
        val hTimes = hourly.getJSONArray("time")
        val hours = (0 until hTimes.length()).map { i ->
            Hour(
                LocalDateTime.parse(hTimes.getString(i)),
                Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)).toInt(),
                hourly.getJSONArray("weather_code").getInt(i),
                hourly.getJSONArray("is_day").optInt(i, 1) == 1,
                hourly.getJSONArray("precipitation_probability").optInt(i, 0),
            )
        }.filter { !it.time.isBefore(now) }.take(24)

        val dTimes = daily.getJSONArray("time")
        val days = (0 until dTimes.length()).map { i ->
            Day(
                java.time.LocalDate.parse(dTimes.getString(i)).atStartOfDay(),
                Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                daily.getJSONArray("weather_code").getInt(i),
                daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
                Math.round(daily.getJSONArray("wind_speed_10m_max").optDouble(i, 0.0)).toInt(),
                LocalDateTime.parse(daily.getJSONArray("sunrise").getString(i)).format(clock),
                LocalDateTime.parse(daily.getJSONArray("sunset").getString(i)).format(clock),
            )
        }

        return WeatherDetail(
            tempF = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feelsF = Math.round(cur.getDouble("apparent_temperature")).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            humidity = cur.optInt("relative_humidity_2m"),
            windMph = Math.round(cur.optDouble("wind_speed_10m")).toInt(),
            gustMph = Math.round(cur.optDouble("wind_gusts_10m")).toInt(),
            windDir = WeatherCodes.compass(cur.optDouble("wind_direction_10m")),
            uv = cur.optDouble("uv_index"),
            hours = hours, days = days, alerts = runCatching { alerts(place) }.getOrDefault(emptyList()),
        )
    }

    private fun alerts(place: com.custom.hisenselauncher.data.Place): List<Alert> {
        val body = Http.get(
            "https://api.weather.gov/alerts/active?point=${place.lat},${place.lon}",
            mapOf("Accept" to "application/geo+json"),
        )
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val p = features.optJSONObject(i)?.optJSONObject("properties") ?: return@mapNotNull null
            Alert(p.optString("event"), p.optString("headline"), p.optString("severity"), p.optString("ends").ifBlank { null })
        }
    }
}
