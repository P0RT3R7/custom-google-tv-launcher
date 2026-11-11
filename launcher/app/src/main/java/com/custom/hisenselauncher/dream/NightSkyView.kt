package com.custom.hisenselauncher.dream

import android.content.Context
import com.custom.hisenselauncher.data.Places
import com.custom.hisenselauncher.data.Prefs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The real sky over the saved place (default Bozeman, MT), as a slowly turning panorama:
 * constellation stick figures with names and "which way / how high" hints, the Milky Way,
 * the local mountain ranges on the horizon, and a "Look up" panel listing what's best placed.
 * At night it shows the sky right now; in daylight it shows tonight at 9:30 PM.
 */
class NightSkyView(ctx: Context) : View(ctx) {

    // ------------------------------------------------------------------ catalog
    private class Star(val ra: Double, val dec: Double, val mag: Float, val color: Int)
    private class Constellation(val name: String, val stars: List<Star>, val lines: IntArray)

    private val white = Color.rgb(232, 238, 255)
    private val orange = Color.rgb(255, 196, 140)
    private val red = Color.rgb(255, 140, 110)
    private val blue = Color.rgb(176, 202, 255)
    private val yellow = Color.rgb(255, 236, 170)

    private fun s(ra: Double, dec: Double, mag: Float, c: Int = white) = Star(ra * 15.0, dec, mag, c)

    private val constellations = listOf(
        Constellation("Big Dipper", listOf(s(11.062, 61.75, 1.8f), s(11.031, 56.38, 2.4f), s(11.897, 53.69, 2.4f), s(12.257, 57.03, 3.3f),
            s(12.900, 55.96, 1.8f), s(13.399, 54.93, 2.2f), s(13.792, 49.31, 1.9f)), intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 3, 4, 4, 5, 5, 6)),
        Constellation("Little Dipper", listOf(s(2.530, 89.26, 2.0f, yellow), s(17.537, 86.59, 4.4f), s(16.766, 82.04, 4.2f), s(15.734, 77.79, 4.3f),
            s(14.845, 74.16, 2.1f, orange), s(15.345, 71.83, 3.0f), s(16.292, 75.76, 5.0f)), intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 3)),
        Constellation("Cassiopeia", listOf(s(0.153, 59.15, 2.3f), s(0.675, 56.54, 2.2f, orange), s(0.945, 60.72, 2.2f, blue), s(1.430, 60.24, 2.7f),
            s(1.907, 63.67, 3.4f)), intArrayOf(0, 1, 1, 2, 2, 3, 3, 4)),
        Constellation("Orion", listOf(s(5.919, 7.41, 0.5f, orange), s(5.242, -8.20, 0.1f, blue), s(5.419, 6.35, 1.6f, blue), s(5.533, -0.30, 2.2f, blue),
            s(5.604, -1.20, 1.7f, blue), s(5.679, -1.94, 1.8f, blue), s(5.796, -9.67, 2.1f, blue), s(5.585, 9.93, 3.4f)),
            intArrayOf(7, 0, 7, 2, 0, 5, 2, 3, 3, 4, 4, 5, 5, 6, 3, 1)),
        Constellation("Taurus", listOf(s(4.599, 16.51, 0.9f, orange), s(5.438, 28.61, 1.7f, blue), s(5.627, 21.14, 3.0f), s(4.477, 19.18, 3.5f, orange),
            s(4.330, 15.63, 3.6f)), intArrayOf(4, 0, 0, 2, 4, 3, 3, 1)),
        Constellation("Pleiades", listOf(s(3.791, 24.105, 2.9f, blue), s(3.819, 24.053, 3.6f, blue), s(3.747, 24.113, 3.7f, blue),
            s(3.763, 24.368, 3.9f, blue), s(3.772, 23.948, 4.2f, blue), s(3.754, 24.467, 4.3f, blue)), intArrayOf()),
        Constellation("Gemini", listOf(s(7.577, 31.89, 1.6f), s(7.755, 28.03, 1.1f, orange), s(6.629, 16.40, 1.9f), s(6.383, 25.13, 3.0f, orange)),
            intArrayOf(0, 1, 0, 3, 1, 2)),
        Constellation("Canis Major", listOf(s(6.752, -16.72, -1.5f, blue), s(6.378, -17.96, 2.0f, blue), s(7.140, -26.39, 1.8f, yellow),
            s(6.977, -28.97, 1.5f, blue), s(7.402, -29.30, 2.4f, blue)), intArrayOf(1, 0, 0, 2, 2, 4, 2, 3)),
        Constellation("Auriga", listOf(s(5.278, 46.00, 0.1f, yellow), s(5.992, 44.95, 1.9f), s(5.995, 37.21, 2.6f), s(4.950, 33.17, 2.7f, orange),
            s(5.032, 43.82, 3.0f)), intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 0)),
        Constellation("Leo", listOf(s(10.139, 11.97, 1.4f, blue), s(10.333, 19.84, 2.0f, orange), s(10.278, 23.42, 3.4f), s(9.764, 23.77, 3.0f),
            s(11.235, 20.52, 2.6f), s(11.818, 14.57, 2.1f), s(11.237, 15.43, 3.3f)), intArrayOf(0, 1, 1, 2, 2, 3, 1, 4, 4, 5, 5, 6, 6, 0)),
        Constellation("Boötes", listOf(s(14.261, 19.18, -0.05f, orange), s(14.750, 27.07, 2.4f, orange), s(13.911, 18.40, 2.7f), s(14.535, 38.31, 3.0f),
            s(15.032, 40.39, 3.5f)), intArrayOf(0, 1, 1, 4, 4, 3, 3, 0, 0, 2)),
        Constellation("Cygnus", listOf(s(20.690, 45.28, 1.25f), s(20.370, 40.26, 2.2f, yellow), s(19.512, 27.96, 3.1f, orange), s(20.770, 33.97, 2.5f, orange),
            s(19.750, 45.13, 2.9f, blue)), intArrayOf(0, 1, 1, 2, 3, 1, 1, 4)),
        Constellation("Lyra", listOf(s(18.616, 38.78, 0.03f, blue), s(18.746, 37.61, 4.3f), s(18.835, 33.36, 3.5f), s(18.982, 32.69, 3.3f, blue),
            s(18.908, 36.90, 4.3f)), intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 1)),
        Constellation("Aquila", listOf(s(19.846, 8.87, 0.8f), s(19.771, 10.61, 2.7f, orange), s(19.922, 6.41, 3.7f)), intArrayOf(1, 0, 0, 2)),
        Constellation("Scorpius", listOf(s(16.490, -26.43, 1.0f, red), s(16.091, -19.81, 2.6f, blue), s(16.006, -22.62, 2.3f, blue), s(15.981, -26.11, 2.9f, blue),
            s(16.836, -34.29, 2.3f, orange), s(16.864, -38.05, 3.0f, blue), s(17.560, -37.10, 1.6f, blue), s(17.622, -43.00, 1.9f)),
            intArrayOf(1, 2, 2, 3, 2, 0, 0, 4, 4, 5, 5, 7, 7, 6)),
        Constellation("Sagittarius", listOf(s(18.403, -34.38, 1.8f, blue), s(18.921, -26.30, 2.0f, blue), s(19.044, -29.88, 2.6f), s(18.350, -29.83, 2.7f, orange),
            s(18.466, -25.42, 2.8f, orange), s(18.761, -26.99, 3.2f, blue), s(18.097, -30.42, 3.0f, orange), s(19.116, -27.67, 3.3f, orange)),
            intArrayOf(6, 3, 6, 0, 3, 0, 3, 4, 4, 5, 5, 2, 2, 0, 5, 1, 1, 7, 7, 2)),
        Constellation("Pegasus", listOf(s(23.079, 15.21, 2.5f), s(23.063, 28.08, 2.4f, orange), s(0.140, 29.09, 2.1f, blue), s(0.220, 15.18, 2.8f, blue)),
            intArrayOf(0, 1, 1, 2, 2, 3, 3, 0)),
    )

    /** Horizon ridges: azimuth center, half-width (deg), height (deg). Named ranges only near Bozeman. */
    private class Range(val name: String, val az: Float, val half: Float, val height: Float)
    private val place = Places.current(Prefs(ctx))
    private val nearBozeman = Places.distanceKm(place, Places.DEFAULT) < 80.0
    private val ranges = if (!nearBozeman) listOf(
        Range("", 30f, 40f, 1.6f), Range("", 150f, 30f, 1.1f), Range("", 230f, 35f, 1.9f), Range("", 320f, 25f, 1.2f),
    ) else listOf(
        Range("Big Belt Mountains", 350f, 22f, 1.4f),
        Range("Bridger Range", 40f, 30f, 4.2f),
        Range("Gallatin Range", 172f, 34f, 3.1f),
        Range("Spanish Peaks", 214f, 16f, 3.4f),
        Range("Tobacco Roots", 268f, 26f, 2.0f),
    )

    // faint background stars fixed on the celestial sphere (deterministic)
    private val bgCount = 650
    private val bgRa = DoubleArray(bgCount) { frac(sin(it * 12.9898) * 43758.5453) * 360.0 }
    private val bgDec = DoubleArray(bgCount) { Math.toDegrees(asin(frac(sin(it * 78.233) * 12543.1234) * 2.0 - 1.0)) }
    private val bgMag = FloatArray(bgCount) { (frac(sin(it * 3.7) * 9137.13) ).toFloat() }

    // Milky Way: points along the galactic plane (b = -5, 0, +5), with brightness toward the core / Cygnus
    private val mwRa: DoubleArray
    private val mwDec: DoubleArray
    private val mwI: FloatArray

    init {
        val ra = ArrayList<Double>(); val dec = ArrayList<Double>(); val inten = ArrayList<Float>()
        val agp = Math.toRadians(192.85948); val dgp = Math.toRadians(27.12825); val lncp = Math.toRadians(122.93192)
        var l = 0.0
        while (l < 360.0) {
            for (b in doubleArrayOf(-5.0, 0.0, 5.0)) {
                val lr = Math.toRadians(l); val br = Math.toRadians(b)
                val sinD = sin(dgp) * sin(br) + cos(dgp) * cos(br) * cos(lncp - lr)
                val d = asin(sinD)
                val a = agp + atan2(cos(br) * sin(lncp - lr), cos(dgp) * sin(br) - sin(dgp) * cos(br) * cos(lncp - lr))
                ra += (Math.toDegrees(a) + 360.0) % 360.0; dec += Math.toDegrees(d)
                val core = exp(-((if (l > 180) l - 360 else l) / 45.0).pow(2)).toFloat()
                val cyg = exp(-((l - 80.0) / 25.0).pow(2)).toFloat()
                inten += (0.35f + 0.65f * core + 0.4f * cyg) * (if (b == 0.0) 1f else 0.6f)
            }
            l += 3.0
        }
        mwRa = ra.toDoubleArray(); mwDec = dec.toDoubleArray(); mwI = inten.toFloatArray()
    }

    // ------------------------------------------------------------------ drawing state
    private val zone = ZoneId.systemDefault()
    private val clockFmt = DateTimeFormatter.ofPattern("h:mm a")
    private val start = SystemClock.uptimeMillis()
    private var running = false

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Color.argb(120, 140, 200, 255) }
    private val bgPaint = Paint().apply { strokeCap = Paint.Cap.ROUND }
    private val mwPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(110, 150, 140, 220) }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 240, 255); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 170, 200, 255); typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 200, 190, 255); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 8, 12, 34) }
    private val path = Path()
    private val rect = RectF()
    private val keepOut = RectF()
    private var skyShader: Shader? = null
    private var groundShader: Shader? = null
    private var glow: Bitmap? = null
    private var lineBuf = FloatArray(512)
    private val bgBuckets = Array(3) { FloatArray(bgCount * 2) }
    private val bgCounts = IntArray(3)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val hy = h * HORIZON
        skyShader = LinearGradient(0f, 0f, 0f, hy, intArrayOf(Color.rgb(4, 7, 26), Color.rgb(12, 20, 58), Color.rgb(44, 32, 86), Color.rgb(92, 52, 104)),
            floatArrayOf(0f, 0.55f, 0.88f, 1f), Shader.TileMode.CLAMP)
        groundShader = LinearGradient(0f, hy, 0f, h.toFloat(), Color.rgb(10, 10, 24), Color.rgb(3, 3, 9), Shader.TileMode.CLAMP)
        val g = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(g).drawCircle(32f, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(32f, 32f, 32f, intArrayOf(Color.argb(255, 190, 200, 255), Color.argb(90, 150, 140, 230), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        glow = g
    }

    // ------------------------------------------------------------------ astronomy
    private var latR = Math.toRadians(place.lat)
    private val lonDeg = place.lon

    private fun lstDeg(epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0)
        return ((gmst + lonDeg) % 360.0 + 360.0) % 360.0
    }

    /** Writes altitude, azimuth (deg) of RA/Dec into out[0], out[1]. */
    private fun altAz(raDeg: Double, decDeg: Double, lst: Double, out: DoubleArray) {
        val ha = Math.toRadians(lst - raDeg); val dec = Math.toRadians(decDeg)
        val sinAlt = sin(dec) * sin(latR) + cos(dec) * cos(latR) * cos(ha)
        val alt = asin(sinAlt)
        val az = atan2(-cos(dec) * sin(ha), sin(dec) * cos(latR) - cos(dec) * sin(latR) * cos(ha))
        out[0] = Math.toDegrees(alt); out[1] = (Math.toDegrees(az) + 360.0) % 360.0
    }

    private fun sunAlt(epochMs: Long): Double {
        val n = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val L = Math.toRadians((280.46 + 0.9856474 * n) % 360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        val lam = L + Math.toRadians(1.915) * sin(g) + Math.toRadians(0.02) * sin(2 * g)
        val eps = Math.toRadians(23.439)
        val ra = Math.toDegrees(atan2(cos(eps) * sin(lam), cos(lam)))
        val dec = Math.toDegrees(asin(sin(eps) * sin(lam)))
        val o = DoubleArray(2); altAz(ra, dec, lstDeg(epochMs), o); return o[0]
    }

    /** Sky time: now if it's dark, otherwise tonight at 9:30 PM. */
    private fun skyTime(): Pair<Long, String> {
        val now = ZonedDateTime.now(zone)
        val ms = now.toInstant().toEpochMilli()
        if (sunAlt(ms) < -8.0) return ms to "Now · ${now.format(clockFmt)}"
        var tonight = now.withHour(21).withMinute(30).withSecond(0).withNano(0)
        if (tonight.isBefore(now)) tonight = tonight.plusDays(1)
        return tonight.toInstant().toEpochMilli() to "Tonight at 9:30 PM"
    }

    // ------------------------------------------------------------------ projection
    private var viewAz = 0.0
    private var w = 1f; private var h = 1f

    private fun px(az: Double): Float {
        var d = az - viewAz
        while (d > 180) d -= 360.0
        while (d < -180) d += 360.0
        return (w / 2 + d / FOV * w).toFloat()
    }

    private fun py(alt: Double): Float {
        val hy = h * HORIZON
        return (hy - (alt.coerceAtLeast(-5.0) / 90.0).let { if (it >= 0) it.pow(0.82) else it } * hy * 0.96).toFloat()
    }

    private fun visibleAz(az: Double): Boolean {
        var d = az - viewAz
        while (d > 180) d -= 360.0
        while (d < -180) d += 360.0
        return abs(d) < FOV / 2 + 8
    }

    private fun dir8(az: Double) = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((az + 22.5) % 360) / 45).toInt()]

    private fun ridgeAlt(az: Double): Double {
        var hgt = 0.55 + 0.35 * sin(Math.toRadians(az * 7.0)) * sin(Math.toRadians(az * 3.0))
        for (r in ranges) {
            var d = az - r.az
            while (d > 180) d -= 360.0
            while (d < -180) d += 360.0
            val k = exp(-(d / r.half).pow(2))
            hgt += r.height * k * (0.75 + 0.25 * sin(Math.toRadians(az * 23.0)) + 0.15 * sin(Math.toRadians(az * 61.0)))
        }
        return hgt
    }

    // ------------------------------------------------------------------ draw
    override fun onDraw(c: Canvas) {
        w = width.toFloat(); h = height.toFloat()
        val scale = h / 1080f
        val t = (SystemClock.uptimeMillis() - start) / 1000.0
        viewAz = (20.0 + t * PAN_DEG_PER_S) % 360.0
        val (epoch, timeLabel) = skyTime()
        val lst = lstDeg(epoch)
        val o = DoubleArray(2)

        // sky
        starPaint.shader = skyShader; c.drawRect(0f, 0f, w, h * HORIZON + 2, starPaint); starPaint.shader = null

        // keep the "Look up" panel area clear (sized for its max 6 rows) so nothing shows through it
        keepOut.set(54f * scale, 44f * scale, 606f * scale, 494f * scale)
        c.save()
        c.clipOutRect(keepOut)

        // Milky Way glow
        glow?.let { g ->
            for (i in mwRa.indices) {
                altAz(mwRa[i], mwDec[i], lst, o)
                if (o[0] < -4 || !visibleAz(o[1])) continue
                val x = px(o[1]); val y = py(o[0])
                val r = 70f * scale * (0.8f + 0.4f * mwI[i])
                mwPaint.alpha = (34 * mwI[i]).toInt().coerceIn(0, 255)
                rect.set(x - r, y - r, x + r, y + r)
                c.drawBitmap(g, null, rect, mwPaint)
            }
        }

        // faint background stars, bucketed by brightness
        bgCounts.fill(0)
        for (i in 0 until bgCount) {
            altAz(bgRa[i], bgDec[i], lst, o)
            if (o[0] < 0 || !visibleAz(o[1])) continue
            val b = if (bgMag[i] > 0.85f) 2 else if (bgMag[i] > 0.5f) 1 else 0
            val arr = bgBuckets[b]; val n = bgCounts[b]
            arr[n] = px(o[1]); arr[n + 1] = py(o[0]); bgCounts[b] = n + 2
        }
        for (b in 0..2) {
            bgPaint.color = Color.argb(intArrayOf(70, 120, 190)[b], 220, 228, 255)
            bgPaint.strokeWidth = floatArrayOf(1.4f, 1.9f, 2.6f)[b] * scale
            c.drawPoints(bgBuckets[b], 0, bgCounts[b], bgPaint)
        }

        // constellations
        val visible = ArrayList<Triple<Constellation, Double, Double>>() // name, centroid alt, az
        namePaint.textSize = 30f * scale; hintPaint.textSize = 22f * scale
        linePaint.strokeWidth = 2f * scale
        for (con in constellations) {
            val xs = FloatArray(con.stars.size); val ys = FloatArray(con.stars.size)
            var sumAlt = 0.0; var sx = 0.0; var sy = 0.0; var up = 0
            for ((k, st) in con.stars.withIndex()) {
                altAz(st.ra, st.dec, lst, o)
                xs[k] = px(o[1]); ys[k] = py(o[0])
                sumAlt += o[0]
                sx += cos(Math.toRadians(o[1])); sy += sin(Math.toRadians(o[1]))
                if (o[0] > 0) up++
            }
            val alt = sumAlt / con.stars.size
            val az = (Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0
            if (up == 0) continue
            visible += Triple(con, alt, az)
            if (!visibleAz(az)) continue
            // lines
            if (con.lines.size > lineBuf.size * 2) lineBuf = FloatArray(con.lines.size * 2)
            var n = 0
            var i = 0
            while (i < con.lines.size) {
                val a = con.lines[i]; val b = con.lines[i + 1]
                if (abs(xs[a] - xs[b]) < w * 0.5f) { lineBuf[n++] = xs[a]; lineBuf[n++] = ys[a]; lineBuf[n++] = xs[b]; lineBuf[n++] = ys[b] }
                i += 2
            }
            c.drawLines(lineBuf, 0, n, linePaint)
            // stars (brighter = bigger, with soft glow)
            for ((k, st) in con.stars.withIndex()) {
                val r = ((4.6f - st.mag) * 1.6f).coerceIn(1.6f, 7.5f) * scale
                val tw = 0.85f + 0.15f * sin((t * 2.1 + k * 1.7 + st.ra).toFloat())
                starPaint.color = st.color
                starPaint.alpha = (90 * tw).toInt()
                c.drawCircle(xs[k], ys[k], r * 2.4f, starPaint)
                starPaint.alpha = (255 * tw).toInt()
                c.drawCircle(xs[k], ys[k], r, starPaint)
            }
            // label below the figure
            val lx = xs.average().toFloat()
            val ly = ys.maxOrNull()!! + 44f * scale
            if (ly < h * HORIZON - 10f * scale && !RectF.intersects(keepOut, RectF(lx - 130f * scale, ly - 34f * scale, lx + 130f * scale, ly + 36f * scale))) {
                namePaint.textAlign = Paint.Align.CENTER; hintPaint.textAlign = Paint.Align.CENTER
                c.drawText(con.name, lx, ly, namePaint)
                c.drawText("${dir8(az)} · ${alt.toInt().coerceAtLeast(0)}° up", lx, ly + 28f * scale, hintPaint)
            }
        }

        c.restore()

        // horizon: mountains + ground + compass + range names
        val hy = h * HORIZON
        path.reset(); path.moveTo(0f, h)
        var x = 0f
        val step = 6f * scale
        while (x <= w + step) {
            val az = viewAz + (x - w / 2) / w * FOV
            path.lineTo(x, py(ridgeAlt(az)))
            x += step
        }
        path.lineTo(w, h); path.close()
        groundPaint.shader = groundShader; c.drawPath(path, groundPaint); groundPaint.shader = null
        rimPaint.strokeWidth = 1.6f * scale
        c.drawPath(path, rimPaint)

        compassPaint.textSize = 34f * scale
        for (k in 0 until 8) {
            val az = k * 45.0
            if (!visibleAz(az)) continue
            c.drawText(dir8(az), px(az), hy + 90f * scale, compassPaint)
        }
        hintPaint.textAlign = Paint.Align.CENTER
        for (r in ranges) if (r.name.isNotEmpty() && visibleAz(r.az.toDouble())) {
            c.drawText(r.name, px(r.az.toDouble()), hy + 140f * scale, hintPaint)
        }

        drawPanel(c, scale, timeLabel, visible)
        if (running) postInvalidateOnAnimation()
    }

    /** "Look up" panel: the best-placed constellations with where to face. */
    private fun drawPanel(c: Canvas, scale: Float, timeLabel: String, visible: List<Triple<Constellation, Double, Double>>) {
        val best = visible.filter { it.second > 15 && it.first.name != "Pleiades" }.sortedByDescending { it.second }.take(6)
        val pad = 28f * scale
        val rowH = 46f * scale
        val pw = 520f * scale
        val ph = pad * 2 + 86f * scale + rowH * best.size
        val x0 = 70f * scale; val y0 = 60f * scale
        rect.set(x0, y0, x0 + pw, y0 + ph)
        c.drawRoundRect(rect, 22f * scale, 22f * scale, panelPaint)
        namePaint.textAlign = Paint.Align.LEFT; hintPaint.textAlign = Paint.Align.LEFT
        namePaint.textSize = 34f * scale
        c.drawText("Look up · ${place.shortName}", x0 + pad, y0 + pad + 30f * scale, namePaint)
        hintPaint.textSize = 24f * scale
        c.drawText(timeLabel, x0 + pad, y0 + pad + 66f * scale, hintPaint)
        namePaint.textSize = 28f * scale
        best.forEachIndexed { i, (con, alt, az) ->
            val y = y0 + pad + 86f * scale + rowH * (i + 1) - 12f * scale
            c.drawText(con.name, x0 + pad, y, namePaint)
            hintPaint.textAlign = Paint.Align.RIGHT
            c.drawText("face ${dir8(az)} · ${alt.toInt()}° up", x0 + pw - pad, y, hintPaint)
            hintPaint.textAlign = Paint.Align.LEFT
        }
    }

    companion object {
        private const val HORIZON = 0.78f
        private const val FOV = 120.0
        private const val PAN_DEG_PER_S = 0.4
        private fun frac(x: Double) = x - floor(x)
    }
}
