package com.custom.hisenselauncher.data

import android.content.Context
import com.custom.hisenselauncher.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** A ski area. Elevations in feet. */
data class Resort(val name: String, val lat: Double, val lon: Double, val baseFt: Int, val summitFt: Int) {
    val shortName: String get() = name.substringBefore(" Resort").substringBefore(" Mountain").substringBefore(" Bowl").substringBefore(" Ski")
    val place get() = Place(name, lat, lon)
}

data class Avalanche(val level: Int, val danger: String, val zone: String, val center: String, val advice: String?)

data class SkiReport(
    val resort: Resort,
    val new24: Double, val new72: Double, val new7d: Double,        // inches, modeled at mid-mountain
    val depthIn: Int?, val depthSource: String,                       // measured (SNOTEL) or modeled
    val forecast: List<Pair<String, Double>>,                         // next days: label to inches
    val hiF: Int?, val loF: Int?, val windMph: Int?,
    val avalanche: Avalanche?,
)

/**
 * Snow report for ski areas near the saved place (free, key-less sources):
 *  - Open-Meteo: new snow (past 24 h / 72 h / 7 days) and the 3-day snowfall forecast at mid-mountain elevation
 *  - NRCS SNOTEL: measured snow depth at the nearest station (within 25 km), e.g. Lone Mountain at Big Sky
 *  - avalanche.org: today's backcountry avalanche danger for the forecast zone the ski area sits in
 * Lift and run status isn't published in an open format, so it isn't shown.
 */
class SkiRepository(private val ctx: Context, private val prefs: Prefs) {

    fun nearby(max: Int = 3, radiusKm: Double = 160.0): List<Resort> {
        val here = Places.current(prefs)
        return RESORTS.map { it to Places.distanceKm(here, it.place) }.filter { it.second <= radiusKm }
            .sortedBy { it.second }.take(max).map { it.first }
    }

    /** Blocking. Reports for nearby resorts (empty when none are close). Also refreshes the top-bar pill text. */
    fun fetch(): List<SkiReport> {
        val resorts = nearby()
        if (resorts.isEmpty()) { prefs.skiPill = null; return emptyList() }
        val avy = runCatching { avalancheZones() }.getOrNull()
        val stations = runCatching { snotelStations() }.getOrDefault(emptyList())
        val reports = resorts.mapNotNull { r -> runCatching { report(r, avy, stations) }.getOrNull() }
        reports.firstOrNull()?.let { prefs.skiPill = pillText(it) }
        return reports
    }

    private fun pillText(r: SkiReport): String = when {
        r.new24 >= 0.5 -> "${r.resort.shortName} ${r.new24.roundToInt()}\" new"
        (r.forecast.firstOrNull()?.second ?: 0.0) >= 1.0 -> "${r.resort.shortName} ${r.forecast.first().second.roundToInt()}\" coming"
        r.depthIn != null && r.depthIn > 0 -> "${r.resort.shortName} ${r.depthIn}\" base"
        else -> "Ski report"
    }

    private fun report(r: Resort, avy: JSONObject?, stations: List<Station>): SkiReport {
        val midM = ((r.baseFt + r.summitFt) / 2 * 0.3048).roundToInt()
        val summitM = (r.summitFt * 0.3048).roundToInt()
        val o = JSONObject(Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${r.lat}&longitude=${r.lon}&elevation=$midM" +
                "&hourly=snowfall,snow_depth&daily=snowfall_sum&past_days=7&forecast_days=4&timezone=auto&timeformat=unixtime" +
                "&precipitation_unit=inch"
        ))
        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time"); val snow = h.getJSONArray("snowfall"); val depth = h.getJSONArray("snow_depth")
        val depthUnit = o.optJSONObject("hourly_units")?.optString("snow_depth") ?: "m"
        val now = System.currentTimeMillis() / 1000
        fun sumSince(hours: Int): Double {
            var s = 0.0
            for (i in 0 until times.length()) { val t = times.getLong(i); if (t in (now - hours * 3600) until now) s += snow.optDouble(i, 0.0).takeIf { !it.isNaN() } ?: 0.0 }
            return s
        }
        var modelDepth: Double? = null
        for (i in 0 until times.length()) if (times.getLong(i) <= now) depth.optDouble(i, Double.NaN).takeIf { !it.isNaN() }?.let { modelDepth = it }
        val modelDepthIn = modelDepth?.let { if (depthUnit.startsWith("ft")) it * 12 else it * 39.37 }

        val d = o.getJSONObject("daily")
        val dTimes = d.getJSONArray("time"); val dSnow = d.getJSONArray("snowfall_sum")
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val nowMs = System.currentTimeMillis()
        // daily times are local midnights: drop days that are over, label today, then weekday names
        val forecast = (0 until dTimes.length()).mapNotNull { i ->
            val dayStart = dTimes.getLong(i) * 1000
            if (dayStart + 86_400_000L <= nowMs) return@mapNotNull null
            (if (dayStart <= nowMs) "Today" else dayFmt.format(dayStart)) to (dSnow.optDouble(i, 0.0).takeIf { !it.isNaN() } ?: 0.0)
        }.take(4)

        // summit weather today
        val w = runCatching {
            JSONObject(Http.get(
                "https://api.open-meteo.com/v1/forecast?latitude=${r.lat}&longitude=${r.lon}&elevation=$summitM" +
                    "&daily=temperature_2m_max,temperature_2m_min,wind_speed_10m_max&forecast_days=1&timezone=auto" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph"
            )).getJSONObject("daily")
        }.getOrNull()

        // measured depth from the nearest SNOTEL station
        val st = stations.map { it to Places.distanceKm(r.place, Place(it.name, it.lat, it.lon)) }
            .filter { it.second <= 25.0 }.minByOrNull { it.second }?.first
        val measured = st?.let { runCatching { snotelDepth(it.triplet) }.getOrNull() }

        return SkiReport(
            resort = r, new24 = sumSince(24), new72 = sumSince(72), new7d = sumSince(168),
            depthIn = measured ?: modelDepthIn?.roundToInt(),
            depthSource = if (measured != null && st != null) "measured at ${st.name} snow station, ${"%,d".format(st.elevFt)} ft" else "modeled",
            forecast = forecast,
            hiF = w?.getJSONArray("temperature_2m_max")?.optDouble(0)?.roundToInt(),
            loF = w?.getJSONArray("temperature_2m_min")?.optDouble(0)?.roundToInt(),
            windMph = w?.getJSONArray("wind_speed_10m_max")?.optDouble(0)?.roundToInt(),
            avalanche = avy?.let { zoneFor(it, r.lat, r.lon) },
        )
    }

    // ------------------------------------------------------------------ SNOTEL
    private class Station(val triplet: String, val name: String, val lat: Double, val lon: Double, val elevFt: Int)

    private fun snotelStations(): List<Station> {
        val f = File(ctx.cacheDir, "snotel_stations.json")
        val json = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) f.readText()
        else Http.get("https://wcc.sc.egov.usda.gov/awdbRestApi/services/v1/stations?networkCds=SNTL&activeOnly=true" +
            "&returnForecastPointMetadata=false&returnReservoirMetadata=false&returnStationElements=false").also { body ->
            // keep only SNOTEL sites, slimmed down
            val a = JSONArray(body); val out = JSONArray()
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i); val t = s.optString("stationTriplet")
                if (t.endsWith(":SNTL")) out.put(JSONObject().put("t", t).put("n", s.optString("name")).put("la", s.optDouble("latitude"))
                    .put("lo", s.optDouble("longitude")).put("e", s.optDouble("elevation")))
            }
            f.writeText(out.toString())
        }.let { f.readText() }
        val a = JSONArray(json)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { Station(it.getString("t"), it.getString("n"), it.getDouble("la"), it.getDouble("lo"), it.optDouble("e").roundToInt()) } }
    }

    private fun snotelDepth(triplet: String): Int? {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val end = fmt.format(System.currentTimeMillis()); val begin = fmt.format(System.currentTimeMillis() - 4 * 86_400_000L)
        val a = JSONArray(Http.get("https://wcc.sc.egov.usda.gov/awdbRestApi/services/v1/data?stationTriplets=$triplet&elements=SNWD&duration=DAILY&beginDate=$begin&endDate=$end"))
        val values = a.getJSONObject(0).getJSONArray("data").getJSONObject(0).getJSONArray("values")
        for (i in values.length() - 1 downTo 0) { val v = values.getJSONObject(i); if (v.has("value")) return v.getDouble("value").roundToInt() }
        return null
    }

    // ------------------------------------------------------------------ avalanche.org
    private fun avalancheZones(): JSONObject {
        val f = File(ctx.cacheDir, "avalanche_map.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3 * 3_600_000L) return JSONObject(f.readText())
        val body = Http.get("https://api.avalanche.org/v2/public/products/map-layer")
        f.writeText(body)
        return JSONObject(body)
    }

    private fun zoneFor(map: JSONObject, lat: Double, lon: Double): Avalanche? {
        val feats = map.optJSONArray("features") ?: return null
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val g = f.optJSONObject("geometry") ?: continue
            val polys: List<JSONArray> = when (g.optString("type")) {
                "Polygon" -> listOf(g.getJSONArray("coordinates"))
                "MultiPolygon" -> g.getJSONArray("coordinates").let { mp -> (0 until mp.length()).map { mp.getJSONArray(it) } }
                else -> emptyList()
            }
            if (polys.none { inPolygon(it.getJSONArray(0), lon, lat) }) continue
            val p = f.getJSONObject("properties")
            val level = p.optInt("danger_level", -1)
            return Avalanche(
                level = level,
                danger = if (level <= 0) "No rating yet" else p.optString("danger").replaceFirstChar { it.uppercase() },
                zone = p.optString("name"), center = p.optString("center_id"),
                advice = p.optString("travel_advice").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return null
    }

    private fun inPolygon(ring: JSONArray, x: Double, y: Double): Boolean {
        var inside = false
        var j = ring.length() - 1
        for (i in 0 until ring.length()) {
            val xi = ring.getJSONArray(i).getDouble(0); val yi = ring.getJSONArray(i).getDouble(1)
            val xj = ring.getJSONArray(j).getDouble(0); val yj = ring.getJSONArray(j).getDouble(1)
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        /** Ski areas the report knows about; the nearest ones within 160 km are shown. */
        val RESORTS = listOf(
            // Montana
            Resort("Bridger Bowl", 45.8174, -110.8967, 6100, 8700),
            Resort("Big Sky Resort", 45.2858, -111.4013, 6800, 11166),
            Resort("Red Lodge Mountain", 45.1906, -109.3394, 7016, 9416),
            Resort("Showdown Montana", 46.8370, -110.7031, 6800, 8200),
            Resort("Great Divide", 46.7550, -112.3133, 5780, 7330),
            Resort("Discovery Ski Area", 46.2475, -113.2340, 6850, 8150),
            Resort("Maverick Mountain", 45.4340, -113.1330, 6500, 8620),
            Resort("Lost Trail Powder Mountain", 45.6927, -113.9510, 6400, 8200),
            Resort("Montana Snowbowl", 47.0154, -114.0029, 4977, 7600),
            Resort("Whitefish Mountain Resort", 48.4836, -114.3550, 4464, 6817),
            Resort("Blacktail Mountain", 48.0136, -114.3567, 5236, 6780),
            // Wyoming / Idaho
            Resort("Jackson Hole Mountain Resort", 43.5875, -110.8279, 6311, 10450),
            Resort("Grand Targhee Resort", 43.7903, -110.9580, 7851, 10230),
            Resort("Sun Valley", 43.6704, -114.4053, 5750, 9150),
            Resort("Bogus Basin", 43.7640, -116.1027, 5800, 7582),
            Resort("Schweitzer", 48.3678, -116.6229, 4000, 6400),
            // Utah
            Resort("Park City Mountain", 40.6514, -111.5080, 6800, 10000),
            Resort("Deer Valley Resort", 40.6375, -111.4783, 6570, 9570),
            Resort("Alta Ski Area", 40.5884, -111.6386, 8530, 10550),
            Resort("Snowbird", 40.5830, -111.6508, 7760, 11000),
            Resort("Brighton Resort", 40.5980, -111.5832, 8755, 10500),
            Resort("Solitude Mountain Resort", 40.6199, -111.5919, 7988, 10488),
            Resort("Snowbasin Resort", 41.2160, -111.8569, 6391, 9350),
            Resort("Powder Mountain", 41.3790, -111.7807, 6900, 9422),
            // Colorado
            Resort("Vail", 39.6061, -106.3550, 8120, 11570),
            Resort("Breckenridge Ski Resort", 39.4817, -106.0384, 9600, 12998),
            Resort("Keystone Resort", 39.6045, -105.9440, 9280, 12408),
            Resort("Arapahoe Basin", 39.6426, -105.8719, 10520, 13050),
            Resort("Copper Mountain", 39.5022, -106.1497, 9712, 12313),
            Resort("Loveland Ski Area", 39.6800, -105.8979, 10800, 13010),
            Resort("Winter Park Resort", 39.8868, -105.7625, 9000, 12060),
            Resort("Eldora Mountain", 39.9372, -105.5827, 9200, 10800),
            Resort("Steamboat Resort", 40.4572, -106.8045, 6900, 10568),
            Resort("Aspen Snowmass", 39.2084, -106.9490, 8104, 12510),
            Resort("Telluride Ski Resort", 37.9375, -107.8123, 8725, 13150),
            // New Mexico / California / Nevada
            Resort("Taos Ski Valley", 36.5960, -105.4545, 9207, 12481),
            Resort("Palisades Tahoe", 39.1970, -120.2358, 6200, 9050),
            Resort("Heavenly Mountain Resort", 38.9353, -119.9400, 6540, 10067),
            Resort("Northstar California", 39.2746, -120.1210, 6330, 8610),
            Resort("Kirkwood Mountain Resort", 38.6850, -120.0650, 7800, 9800),
            Resort("Mammoth Mountain", 37.6308, -119.0326, 7953, 11053),
            // Pacific Northwest
            Resort("Mt. Bachelor", 43.9792, -121.6886, 5700, 9065),
            Resort("Mt. Hood Meadows", 45.3311, -121.6650, 4523, 7300),
            Resort("Crystal Mountain", 46.9282, -121.5045, 4400, 7012),
            Resort("Stevens Pass", 47.7448, -121.0890, 4061, 5845),
            Resort("Mt. Baker Ski Area", 48.8570, -121.6650, 3500, 5089),
            // Northeast
            Resort("Killington Resort", 43.6045, -72.8201, 1165, 4241),
            Resort("Stowe Mountain Resort", 44.5303, -72.7814, 1559, 3719),
            Resort("Sugarloaf", 45.0314, -70.3131, 1417, 4237),
            Resort("Mount Snow", 42.9602, -72.9204, 1900, 3600),
            Resort("Whiteface Mountain", 44.3659, -73.9026, 1220, 4650),
        )
    }
}
