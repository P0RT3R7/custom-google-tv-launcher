package com.custom.hisenselauncher.wallpaper

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Interleaved vertex list: x, y (px), r, g, b, a (premultiplied), u, v, soft. */
class VertexBuffer(private val maxVerts: Int) {
    val data = FloatArray(maxVerts * STRIDE)
    var count = 0
        private set

    fun clear() { count = 0 }

    fun v(x: Float, y: Float, r: Float, g: Float, b: Float, a: Float, u: Float = 0f, w: Float = 0f, soft: Float = 0f) {
        if (count >= maxVerts) return
        val i = count * STRIDE
        data[i] = x; data[i + 1] = y
        data[i + 2] = r * a; data[i + 3] = g * a; data[i + 4] = b * a; data[i + 5] = a
        data[i + 6] = u; data[i + 7] = w; data[i + 8] = soft
        count++
    }

    companion object { const val STRIDE = 9 }
}

/**
 * The jellyfish aquarium as pure geometry (no GL calls): every frame it fills [tris] and [lines]
 * in screen pixels. Rendered additively on black, so draw order never matters.
 *
 *  - Sea nettles: violet ribbed bells with a pink core, hair-fine tentacles, four ruffled oral arms,
 *    jet-propulsion pulses with tentacles trailing behind the motion.
 *  - Moon jellies: pale discs that slowly tumble in 3D with four-leaf gonads and a short fringe.
 *  - Depth: far animals are smaller, dimmer and slower.
 */
class JellyScene(private val brightness: Float = 1f) : GLScene {

    override val tris = VertexBuffer(48_000)
    override val lines = VertexBuffer(40_000)

    private enum class Kind { NETTLE, MOON }

    private class Jelly(
        val kind: Kind, var x: Float, var y: Float, val size: Float, val z: Float, val seed: Float, var tilt: Float = 0.6f,
    ) {
        var vx = 0f
        var vy = 0f
        var angle = 0f
        val period = if (kind == Kind.NETTLE) 3.4f + seed * 1.6f else 2.4f + seed * 1.2f
        val tentacleLen = FloatArray(18) { 3.0f + 1.6f * frac(sin((it + 1) * 7.13f + seed * 31f) * 437.5f) }
    }

    // palette (from the reference)
    private val lavender = Color.rgb(168, 140, 242)
    private val pink = Color.rgb(242, 156, 232)
    private val lilac = Color.rgb(222, 208, 255)
    private val violet = Color.rgb(130, 100, 225)
    private val moonBody = Color.rgb(190, 214, 255)
    private val moonRim = Color.rgb(226, 238, 255)
    private val gonad = Color.rgb(238, 206, 246)

    private val jellies = listOf(
        Jelly(Kind.NETTLE, 0.56f, 0.30f, 0.10f, 1.00f, 0.11f),
        Jelly(Kind.MOON, 0.58f, 0.12f, 0.026f, 0.45f, 0.08f, 0.7f),
        Jelly(Kind.MOON, 0.13f, 0.82f, 0.032f, 0.58f, 0.95f, 0.5f),
        Jelly(Kind.NETTLE, 0.17f, 0.44f, 0.066f, 0.82f, 0.47f),
        Jelly(Kind.NETTLE, 0.83f, 0.54f, 0.068f, 0.86f, 0.73f),
        Jelly(Kind.NETTLE, 0.36f, 0.26f, 0.040f, 0.55f, 0.29f),
        Jelly(Kind.NETTLE, 0.70f, 0.80f, 0.034f, 0.42f, 0.91f),
        Jelly(Kind.MOON, 0.08f, 0.30f, 0.036f, 0.62f, 0.18f, 0.45f),
        Jelly(Kind.MOON, 0.27f, 0.62f, 0.042f, 0.74f, 0.64f, 0.80f),
        Jelly(Kind.MOON, 0.67f, 0.56f, 0.040f, 0.78f, 0.36f, 0.55f),
        Jelly(Kind.MOON, 0.92f, 0.40f, 0.034f, 0.66f, 0.83f, 0.62f),
        Jelly(Kind.MOON, 0.46f, 0.86f, 0.028f, 0.48f, 0.55f, 0.85f),
    )

    private val pCount = 46
    private val px = FloatArray(pCount) { rnd(it * 3 + 1) }
    private val py = FloatArray(pCount) { rnd(it * 3 + 2) }
    private val pz = FloatArray(pCount) { 0.25f + 0.75f * rnd(it * 3 + 3) }

    // scratch outline buffers (bell / disc perimeter)
    private val ox = FloatArray(128)
    private val oy = FloatArray(128)

    // current local->screen transform
    private var cx = 0f; private var cy = 0f; private var ca = 1f; private var sa = 0f; private var sc = 1f

    override fun frame(w: Float, h: Float, t: Float, dt: Float) {
        tris.clear(); lines.clear()
        snow(w, h, t)
        for (j in jellies) {
            step(j, t, dt)
            if (j.kind == Kind.NETTLE) nettle(j, w, h, t) else moon(j, w, h, t)
        }
    }

    // ------------------------------------------------------------------ motion

    private fun contraction(j: Jelly, t: Float): Float {
        val u = frac((t + j.seed * 10f) / j.period)
        return if (u < 0.28f) sin(u / 0.28f * HALF_PI) else cos((u - 0.28f) / 0.72f * HALF_PI)
    }

    private fun step(j: Jelly, t: Float, dt: Float) {
        val u = frac((t + j.seed * 10f) / j.period)
        val depthSpeed = 0.45f + 0.55f * j.z
        if (j.kind == Kind.NETTLE) {
            j.angle = 0.32f * sin(t * 0.045f + j.seed * 9f) + 0.12f * sin(t * 0.11f + j.seed * 3f)
            val thrust = if (u < 0.28f) 0.055f * depthSpeed else 0f
            j.vx += sin(j.angle) * thrust * dt
            j.vy -= cos(j.angle) * thrust * dt
            j.vx *= (1f - 0.9f * dt); j.vy *= (1f - 0.9f * dt)
            j.vy += 0.0016f * dt
        } else {
            j.angle = 1.1f * sin(t * 0.03f + j.seed * 12f)
            j.tilt = (0.62f + 0.3f * sin(t * 0.07f + j.seed * 20f)).coerceIn(0.25f, 0.95f)
            val thrust = if (u < 0.28f) 0.02f * depthSpeed else 0f
            j.vx += (sin(j.angle) * thrust + 0.002f * sin(t * 0.05f + j.seed * 4f)) * dt
            j.vy -= cos(j.angle) * thrust * dt
            j.vx *= (1f - 0.7f * dt); j.vy *= (1f - 0.7f * dt)
        }
        j.x += j.vx * dt * 0.56f
        j.y += j.vy * dt
        if (j.y < -0.12f - j.size) { j.y = 1.1f + j.size; j.x = 0.08f + 0.84f * rnd((t * 13).toInt() + (j.seed * 100).toInt()) }
        if (j.y > 1.3f) j.y = -0.1f
        if (j.x < -0.12f) j.x = 1.1f
        if (j.x > 1.12f) j.x = -0.1f
    }

    // ------------------------------------------------------------------ sea nettle

    private fun nettle(j: Jelly, w: Float, h: Float, t: Float) {
        val k = contraction(j, t)
        val r = j.size * h
        val d = (0.38f + 0.62f * j.z) * brightness
        val bw = 1f - 0.13f * k
        val hb = 0.82f + 0.16f * k
        xf(j.x * w, j.y * h, j.angle, r)
        val trailX = -(j.vx * cos(j.angle) + j.vy * sin(j.angle)) * 40f

        halo(0f, -0.3f, 2.0f, 2.0f, Color.rgb(150, 110, 240), 0.22f * d)

        // hair-fine tentacles
        for (i in 0 until 16) {
            val u = i / 15f * 2f - 1f
            val bx = u * bw * 0.93f
            val by = marginY(bx, bw) - 0.02f
            val len = j.tentacleLen[i] * (1f + 0.12f * (1f - k))
            var lx = bx; var ly = by
            val segs = 24
            for (s in 1..segs) {
                val f = s / segs.toFloat()
                val sway = sin(t * 0.85f + i * 0.83f + j.seed * 7f - f * (4.2f + (i % 3))) * 0.24f * f.pow(1.15f)
                val nx = bx + sway + bx * 0.28f * f * (1f - k * 0.6f) + trailX * f * f
                val ny = by + len * f
                line(lx, ly, nx, ny, lilac, 0.36f * d * (1f - 0.55f * f))
                lx = nx; ly = ny
            }
        }

        // four ruffled oral arms
        for (a in 0 until 4) oralArm(j, a, t, k, d, trailX)

        // bell: two-ring gradient fan (pink core -> lavender -> faint edge)
        val n = bellOutline(bw, hb)
        val ccx = 0f; val ccy = -0.45f * hb
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            val mx0 = ccx + (ox[i] - ccx) * 0.55f; val my0 = ccy + (oy[i] - ccy) * 0.55f
            val mx1 = ccx + (ox[i2] - ccx) * 0.55f; val my1 = ccy + (oy[i2] - ccy) * 0.55f
            tri(ccx, ccy, pink, 0.72f * d, mx0, my0, lavender, 0.42f * d, mx1, my1, lavender, 0.42f * d)
            tri(mx0, my0, lavender, 0.42f * d, ox[i], oy[i], lilac, 0.22f * d, ox[i2], oy[i2], lilac, 0.22f * d)
            tri(mx0, my0, lavender, 0.42f * d, ox[i2], oy[i2], lilac, 0.22f * d, mx1, my1, lavender, 0.42f * d)
        }
        // lighter cap at the top of the dome
        halo(0f, -0.72f * hb, 0.7f, 0.32f, lilac, 0.28f * d)

        // radial ridges
        for (i in 0 until 15) {
            val a = PI.toFloat() * (i + 0.5f) / 15f
            val ex = -cos(a) * bw * 0.97f
            val ey = marginY(ex, bw) - 0.01f
            val qx = -cos(a) * bw * 0.78f; val qy = -hb * 0.62f * sin(a) - 0.05f
            val sx = -cos(a) * 0.08f; val sy = -hb * 0.97f
            var lx = sx; var ly = sy
            for (s in 1..8) {
                val f = s / 8f; val g = 1f - f
                val nx = g * g * sx + 2f * g * f * qx + f * f * ex
                val ny = g * g * sy + 2f * g * f * qy + f * f * ey
                line(lx, ly, nx, ny, lilac, 0.22f * d)
                lx = nx; ly = ny
            }
        }

        // inner pink glow + bright scalloped rim
        halo(0f, -0.38f, 0.62f, 0.55f, pink, 0.5f * d * (0.75f + 0.25f * k))
        var lx = -bw; var ly = marginY(-bw, bw)
        for (i in 1..36) {
            val x = -bw + 2f * bw * i / 36f
            val y = marginY(x, bw) + if (i % 2 == 1) 0.045f else 0f
            line(lx, ly, x, y, lilac, 0.75f * d)
            lx = x; ly = y
        }
        halo(0f, 0.02f, bw * 1.05f, 0.16f, pink, 0.2f * d)
    }

    private fun oralArm(j: Jelly, a: Int, t: Float, k: Float, d: Float, trailX: Float) {
        val x0 = (a - 1.5f) * 0.11f
        val len = (2.4f + 0.5f * frac(j.seed * 13f + a * 0.37f)) * (1f + 0.06f * (1f - k))
        val segs = 30
        val col = if (a % 2 == 0) lavender else violet
        var pxl = 0f; var pxr = 0f; var py0 = 0f; var pa = 0f
        for (s in 0..segs) {
            val f = s / segs.toFloat()
            val cxa = x0 * (1f + 0.6f * f) + sin(t * 0.7f + a * 1.7f + j.seed * 5f - f * 4.6f) * 0.2f * f + trailX * 0.7f * f * f
            val wd = 0.13f * (1f - 0.7f * f) * (0.72f + 0.38f * abs(sin(f * 22f + t * 1.4f + a * 2.1f)))
            val y = 0.06f + len * f
            // mottled texture: alpha ripples along the arm
            val al = (0.30f + 0.16f * sin(f * 37f + t * 1.1f + a)) * d * (1f - 0.45f * f)
            if (s > 0) {
                tri(pxl, py0, col, pa, pxr, py0, col, pa, cxa - wd, y, col, al)
                tri(pxr, py0, col, pa, cxa + wd, y, col, al, cxa - wd, y, col, al)
                line(pxl, py0, cxa - wd, y, lilac, 0.22f * d)
                line(pxr, py0, cxa + wd, y, lilac, 0.22f * d)
                val cmx = (pxl + pxr) / 2f
                line(cmx, py0, cxa + wd * 0.3f * sin(f * 40f + t * 2f), y, lilac, 0.12f * d)
            }
            pxl = cxa - wd; pxr = cxa + wd; py0 = y; pa = al
        }
    }

    /** Dome + scalloped margin into ox/oy; returns point count. */
    private fun bellOutline(bw: Float, hb: Float): Int {
        var n = 0
        // left half of dome: cubic from (-bw,0) to (0,-hb)
        n = cubic(n, -bw, 0f, -bw * 1.03f, -hb * 0.78f, -bw * 0.52f, -hb * 1.02f, 0f, -hb, 14, includeFirst = true)
        n = cubic(n, 0f, -hb, bw * 0.52f, -hb * 1.02f, bw * 1.03f, -hb * 0.78f, bw, 0f, 14, includeFirst = false)
        for (i in 1 until 36) {
            val x = bw - 2f * bw * i / 36f
            ox[n] = x; oy[n] = marginY(x, bw) + if (i % 2 == 1) 0.045f else 0f; n++
        }
        return n
    }

    private fun cubic(start: Int, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, segs: Int, includeFirst: Boolean): Int {
        var n = start
        for (s in (if (includeFirst) 0 else 1)..segs) {
            val f = s / segs.toFloat(); val g = 1f - f
            ox[n] = g * g * g * x0 + 3 * g * g * f * x1 + 3 * g * f * f * x2 + f * f * f * x3
            oy[n] = g * g * g * y0 + 3 * g * g * f * y1 + 3 * g * f * f * y2 + f * f * f * y3
            n++
        }
        return n
    }

    private fun marginY(x: Float, bw: Float): Float { val q = x / bw; return 0.09f * bw * (1f - q * q) }

    // ------------------------------------------------------------------ moon jelly

    private fun moon(j: Jelly, w: Float, h: Float, t: Float) {
        val k = contraction(j, t)
        val r = j.size * h
        val d = (0.38f + 0.62f * j.z) * brightness
        val ry = j.tilt * (1f - 0.06f * k)
        val rx = 1f - 0.08f * k
        val lift = (1f - j.tilt) * 0.35f
        xf(j.x * w, j.y * h, j.angle, r)

        halo(0f, -lift, 1.6f, 1.6f * (0.5f + 0.5f * j.tilt), Color.rgb(170, 205, 255), 0.18f * d)

        // translucent disc
        val n = 48
        for (i in 0 until n) {
            val a = 2f * PI.toFloat() * i / n
            ox[i] = cos(a) * rx; oy[i] = sin(a) * ry - lift
        }
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            tri(0f, -lift, moonRim, 0.34f * d, ox[i], oy[i], moonBody, 0.14f * d, ox[i2], oy[i2], moonBody, 0.14f * d)
        }
        // radial canals
        for (i in 0 until 16) {
            val a = 2f * PI.toFloat() * i / 16
            line(cos(a) * 0.12f, sin(a) * 0.12f * ry - lift, cos(a) * rx * 0.96f, sin(a) * ry * 0.96f - lift, moonRim, 0.16f * d)
        }
        // four-leaf gonads: double rings + soft glow
        for (g in 0 until 4) {
            val a = PI.toFloat() / 4f + g * HALF_PI
            val gx = cos(a) * 0.3f * rx
            val gy = sin(a) * 0.3f * ry - lift
            halo(gx, gy, 0.22f, 0.22f * ry, Color.rgb(210, 160, 235), 0.35f * d)
            ring(gx, gy, 0.17f, 0.17f * ry, gonad, 0.8f * d, 20)
            ring(gx, gy, 0.14f, 0.14f * ry, gonad, 0.45f * d, 20)
        }
        // rim + fringe of short tentacles
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            line(ox[i], oy[i], ox[i2], oy[i2], moonRim, 0.95f * d)
        }
        for (i in 0 until 64) {
            val a = 2f * PI.toFloat() * i / 64
            val ex = cos(a) * rx; val ey = sin(a) * ry - lift
            val l = 0.09f + 0.05f * sin(t * 2f + i * 0.9f + j.seed * 6f) + 0.05f * k
            line(ex, ey, ex + cos(a) * l, ey + sin(a) * l * ry + 0.03f, moonBody, 0.34f * d)
        }
    }

    // ------------------------------------------------------------------ particles

    private fun snow(w: Float, h: Float, t: Float) {
        xf(0f, 0f, 0f, 1f)
        for (i in 0 until pCount) {
            val z = pz[i]
            val x = frac(px[i] + 0.02f * sin(t * 0.05f * z + i)) * w
            val y = frac(py[i] - t * 0.006f * z + 1f) * h
            val rr = (1.4f + 2.2f * z) * (h / 1080f)
            halo(x, y, rr, rr, Color.rgb(200, 205, 255), (0.08f + 0.2f * z) * (0.6f + 0.4f * sin(t * 0.7f + i)) * brightness)
        }
    }

    // ------------------------------------------------------------------ primitives (local coords)

    private fun xf(x: Float, y: Float, angle: Float, scale: Float) { cx = x; cy = y; ca = cos(angle); sa = sin(angle); sc = scale }
    private fun tx(lx: Float, ly: Float) = cx + (lx * ca - ly * sa) * sc
    private fun ty(lx: Float, ly: Float) = cy + (lx * sa + ly * ca) * sc

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, c: Int, a: Float) {
        val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
        lines.v(tx(x0, y0), ty(x0, y0), r, g, b, a)
        lines.v(tx(x1, y1), ty(x1, y1), r, g, b, a)
    }

    private fun tri(x0: Float, y0: Float, c0: Int, a0: Float, x1: Float, y1: Float, c1: Int, a1: Float, x2: Float, y2: Float, c2: Int, a2: Float) {
        tris.v(tx(x0, y0), ty(x0, y0), Color.red(c0) / 255f, Color.green(c0) / 255f, Color.blue(c0) / 255f, a0)
        tris.v(tx(x1, y1), ty(x1, y1), Color.red(c1) / 255f, Color.green(c1) / 255f, Color.blue(c1) / 255f, a1)
        tris.v(tx(x2, y2), ty(x2, y2), Color.red(c2) / 255f, Color.green(c2) / 255f, Color.blue(c2) / 255f, a2)
    }

    /** Soft radial glow (ellipse radii in local units). */
    private fun halo(lx: Float, ly: Float, rx: Float, ry: Float, c: Int, a: Float) {
        val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
        fun corner(ux: Float, uy: Float) = tris.v(tx(lx + ux * rx, ly + uy * ry), ty(lx + ux * rx, ly + uy * ry), r, g, b, a, ux, uy, 1f)
        corner(-1f, -1f); corner(1f, -1f); corner(1f, 1f)
        corner(-1f, -1f); corner(1f, 1f); corner(-1f, 1f)
    }

    private fun ring(lx: Float, ly: Float, rx: Float, ry: Float, c: Int, a: Float, segs: Int) {
        var px0 = lx + rx; var py0 = ly
        for (s in 1..segs) {
            val ang = 2f * PI.toFloat() * s / segs
            val nx = lx + cos(ang) * rx; val ny = ly + sin(ang) * ry
            line(px0, py0, nx, ny, c, a)
            px0 = nx; py0 = ny
        }
    }

    companion object {
        private const val HALF_PI = (PI / 2).toFloat()
        private fun frac(x: Float) = x - floor(x)
        private fun rnd(i: Int): Float = frac(sin(i * 12.9898f) * 43758.5453f)
    }
}
