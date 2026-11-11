package com.custom.hisenselauncher.data

import com.custom.hisenselauncher.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tonight's aurora outlook for the saved place, from NOAA SWPC's planetary K-index forecast (free, no key). */
data class AuroraOutlook(
    val kpNow: Double,
    val kpTonight: Double,
    /** Kp at which aurora is usually visible low on the northern horizon at this latitude. */
    val kpNeeded: Int,
    val fetchedAt: Long,
) {
    enum class Level { NONE, SLIGHT, LIKELY, STRONG }

    val level: Level get() = when {
        kpTonight >= kpNeeded + 1 -> Level.STRONG
        kpTonight >= kpNeeded -> Level.LIKELY
        kpTonight >= kpNeeded - 1 -> Level.SLIGHT
        else -> Level.NONE
    }

    /** Same scale for right now (drives the glow on the Night Sky screensaver). */
    val nowLevel: Level get() = when {
        kpNow >= kpNeeded + 1 -> Level.STRONG
        kpNow >= kpNeeded -> Level.LIKELY
        kpNow >= kpNeeded - 1 -> Level.SLIGHT
        else -> Level.NONE
    }

    val headline: String get() = when (level) {
        Level.STRONG -> "Strong aurora possible tonight: may reach high in the sky"
        Level.LIKELY -> "Aurora possible tonight: look north after dark"
        Level.SLIGHT -> "Slight aurora chance tonight, low on the northern horizon"
        Level.NONE -> "Aurora unlikely tonight"
    }

    val detail: String get() = "Kp ${fmt(kpTonight)} forecast tonight · about Kp $kpNeeded needed here · Kp now ${fmt(kpNow)}"

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)

    fun toJson(): String = JSONObject().put("now", kpNow).put("tonight", kpTonight).put("need", kpNeeded).put("at", fetchedAt).toString()

    companion object {
        fun fromJson(s: String?): AuroraOutlook? = runCatching {
            val o = JSONObject(s!!)
            AuroraOutlook(o.getDouble("now"), o.getDouble("tonight"), o.getInt("need"), o.getLong("at"))
        }.getOrNull()

        /** Rough visibility threshold by latitude: about Kp 5 around 47°, one more Kp for every ~3° closer to the equator. */
        fun kpNeededFor(lat: Double): Int = (5 + (47 - abs(lat)) / 3.0).roundToInt().coerceIn(1, 9)
    }
}

class AuroraRepository(private val prefs: Prefs) {

    fun cached(): AuroraOutlook? = AuroraOutlook.fromJson(prefs.auroraJson)

    val isStale: Boolean get() = System.currentTimeMillis() - (cached()?.fetchedAt ?: 0L) > REFRESH_MS

    /** Blocking. Returns null on failure (the cache stays). */
    fun fetch(): AuroraOutlook? = runCatching {
        val arr = JSONArray(Http.get(URL))
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        data class Bin(val start: Long, val kp: Double, val kind: String)
        val bins = (0 until arr.length()).mapNotNull { i ->
            // current format: objects; older format: arrays with a header row
            when (val e = arr.get(i)) {
                is JSONObject -> runCatching { Bin(utc.parse(e.getString("time_tag"))!!.time, e.getDouble("kp"), e.optString("observed")) }.getOrNull()
                is JSONArray -> runCatching { Bin(utc.parse(e.getString(0).replace(' ', 'T'))!!.time, e.getString(1).toDouble(), e.getString(2)) }.getOrNull()
                else -> null
            }
        }
        val now = System.currentTimeMillis()
        val threeH = 3 * 3_600_000L
        val kpNow = bins.lastOrNull { it.start <= now && it.kind != "predicted" }?.kp
            ?: bins.lastOrNull { it.start <= now }?.kp ?: 0.0
        val (from, to) = tonight(now)
        val kpTonight = bins.filter { it.start + threeH > from && it.start < to }.maxOfOrNull { it.kp } ?: kpNow
        val place = Places.current(prefs)
        AuroraOutlook(kpNow, maxOf(kpTonight, kpNow), AuroraOutlook.kpNeededFor(place.lat), now).also { prefs.auroraJson = it.toJson() }
    }.getOrNull()

    /** Tonight's dark window in local time: 8 PM to 4 AM (or now until 4 AM if it's already night). */
    private fun tonight(now: Long): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val h = c.get(Calendar.HOUR_OF_DAY)
        fun at(dayOffset: Int, hour: Int) = Calendar.getInstance().apply {
            timeInMillis = now; add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            h < 4 -> now to at(0, 4)
            h >= 20 -> now to at(1, 4)
            else -> at(0, 20) to at(1, 4)
        }
    }

    companion object {
        const val URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"
        const val REFRESH_MS = 30 * 60 * 1000L
    }
}
