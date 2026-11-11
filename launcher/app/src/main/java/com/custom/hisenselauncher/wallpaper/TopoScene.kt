package com.custom.hisenselauncher.wallpaper

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * "Topo Glow": a slowly drifting relief map in full color.
 *  - hypsometric tints (valley teal -> forest -> meadow -> tan -> rust -> rock -> snow)
 *  - hillshading lit from the northwest, so the terrain reads in 3D
 *  - glowing contour lines with a brighter index contour every 5th line (like a USGS quad)
 * The terrain is a north-south ridge (Bridger Range style) plus noise; it scrolls north over time.
 */
class TopoScene : GLScene {

    override val tris = VertexBuffer(72_000)
    override val lines = VertexBuffer(64_000)

    private val cols = 112
    private val rows = 63
    private val stride = cols + 1
    private val field = FloatArray((cols + 1) * (rows + 1))
    private val cr = FloatArray(field.size)
    private val cg = FloatArray(field.size)
    private val cb = FloatArray(field.size)
    private var frameNo = 0

    // elevation color ramp: position, r, g, b
    private val ramp = arrayOf(
        floatArrayOf(0.00f, 0.04f, 0.18f, 0.24f),
        floatArrayOf(0.14f, 0.08f, 0.36f, 0.33f),
        floatArrayOf(0.30f, 0.18f, 0.50f, 0.27f),
        floatArrayOf(0.45f, 0.50f, 0.60f, 0.24f),
        floatArrayOf(0.58f, 0.77f, 0.60f, 0.32f),
        floatArrayOf(0.70f, 0.66f, 0.38f, 0.24f),
        floatArrayOf(0.82f, 0.49f, 0.31f, 0.48f),
        floatArrayOf(0.92f, 0.72f, 0.66f, 0.79f),
        floatArrayOf(1.00f, 0.96f, 0.97f, 1.00f),
    )

    override fun frame(w: Float, h: Float, t: Float, dt: Float) {
        // terrain changes slowly: rebuild geometry every other frame (30 Hz is plenty)
        if (frameNo++ % 2 != 0 && tris.count > 0) return
        tris.clear(); lines.clear()
        computeField(t)
        colorize()
        fill(w, h)
        contours(w, h)
    }

    private fun computeField(t: Float) {
        val pan = t * 0.0035f
        for (j in 0..rows) {
            val y = j / rows.toFloat() + pan
            for (i in 0..cols) {
                val x = i / cols.toFloat() * 1.78f
                val axis = 0.98f + 0.16f * sin(y * 2.1f) + 0.05f * sin(y * 5.3f)
                val d = (x - axis) * 2.3f
                val ridge = exp(-d * d) * (0.72f + 0.35f * fbm2(1.3f, y * 1.4f, 7))
                field[j * stride + i] = 0.52f * ridge + 0.5f * fbm2(x * 1.9f + t * 0.0015f, y * 1.9f, 3)
            }
        }
    }

    private fun colorize() {
        val rgb = FloatArray(3)
        for (j in 0..rows) for (i in 0..cols) {
            val k = j * stride + i
            val e = ((field[k] - 0.2f) / 0.78f).coerceIn(0f, 1f)
            rampColor(e, rgb)
            val dx = field[j * stride + (i + 1).coerceAtMost(cols)] - field[j * stride + (i - 1).coerceAtLeast(0)]
            val dy = field[(j + 1).coerceAtMost(rows) * stride + i] - field[(j - 1).coerceAtLeast(0) * stride + i]
            val shade = (0.8f + (dx + dy) * 7.5f).coerceIn(0.38f, 1.35f)
            cr[k] = (rgb[0] * shade).coerceAtMost(1f)
            cg[k] = (rgb[1] * shade).coerceAtMost(1f)
            cb[k] = (rgb[2] * shade).coerceAtMost(1f)
        }
    }

    private fun rampColor(e: Float, out: FloatArray) {
        for (s in 0 until ramp.size - 1) {
            val a = ramp[s]; val b = ramp[s + 1]
            if (e <= b[0]) {
                val f = ((e - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
                out[0] = a[1] + (b[1] - a[1]) * f; out[1] = a[2] + (b[2] - a[2]) * f; out[2] = a[3] + (b[3] - a[3]) * f
                return
            }
        }
        val l = ramp.last(); out[0] = l[1]; out[1] = l[2]; out[2] = l[3]
    }

    private fun fill(w: Float, h: Float) {
        val cw = w / cols; val ch = h / rows
        for (j in 0 until rows) for (i in 0 until cols) {
            val a = j * stride + i; val b = a + 1; val c = a + stride + 1; val d = a + stride
            val x0 = i * cw; val y0 = j * ch; val x1 = x0 + cw; val y1 = y0 + ch
            tris.v(x0, y0, cr[a], cg[a], cb[a], 1f); tris.v(x1, y0, cr[b], cg[b], cb[b], 1f); tris.v(x1, y1, cr[c], cg[c], cb[c], 1f)
            tris.v(x0, y0, cr[a], cg[a], cb[a], 1f); tris.v(x1, y1, cr[c], cg[c], cb[c], 1f); tris.v(x0, y1, cr[d], cg[d], cb[d], 1f)
        }
    }

    private fun contours(w: Float, h: Float) {
        val cw = w / cols; val ch = h / rows
        val levels = 24
        for (lv in 1 until levels) {
            val iso = 0.2f + 0.78f * lv / levels
            val index = lv % 5 == 0
            val alpha = if (index) 0.5f else 0.17f
            for (j in 0 until rows) for (i in 0 until cols) {
                val a = field[j * stride + i]; val b = field[j * stride + i + 1]
                val c = field[(j + 1) * stride + i + 1]; val d = field[(j + 1) * stride + i]
                val idx = (if (a > iso) 1 else 0) or (if (b > iso) 2 else 0) or (if (c > iso) 4 else 0) or (if (d > iso) 8 else 0)
                if (idx == 0 || idx == 15) continue
                val x0 = i * cw; val y0 = j * ch
                // edge crossings
                val tx = x0 + cw * (iso - a) / (b - a); val ty = y0
                val rx = x0 + cw; val ry = y0 + ch * (iso - b) / (c - b)
                val bx = x0 + cw * (iso - d) / (c - d); val by = y0 + ch
                val lx = x0; val ly = y0 + ch * (iso - a) / (d - a)
                when (idx) {
                    1, 14 -> seg(lx, ly, tx, ty, alpha)
                    2, 13 -> seg(tx, ty, rx, ry, alpha)
                    3, 12 -> seg(lx, ly, rx, ry, alpha)
                    4, 11 -> seg(rx, ry, bx, by, alpha)
                    5 -> { seg(lx, ly, tx, ty, alpha); seg(rx, ry, bx, by, alpha) }
                    6, 9 -> seg(tx, ty, bx, by, alpha)
                    7, 8 -> seg(lx, ly, bx, by, alpha)
                    10 -> { seg(tx, ty, rx, ry, alpha); seg(lx, ly, bx, by, alpha) }
                }
            }
        }
    }

    private fun seg(x0: Float, y0: Float, x1: Float, y1: Float, a: Float) {
        lines.v(x0, y0, 1f, 0.97f, 0.9f, a)
        lines.v(x1, y1, 1f, 0.97f, 0.9f, a)
    }

    // ---- fast integer-hash value noise
    private fun h2(x: Int, y: Int, s: Int): Float {
        var n = x * 374761393 + y * 668265263 + s * 1442695041
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777215f
    }

    private fun noise2(x: Float, y: Float, s: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = x - xi; val yf = y - yi
        val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf)
        val a = h2(xi, yi, s); val b = h2(xi + 1, yi, s); val c = h2(xi, yi + 1, s); val d = h2(xi + 1, yi + 1, s)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    private fun fbm2(x: Float, y: Float, s: Int): Float {
        var sum = 0f; var amp = 0.5f; var fr = 1f
        for (o in 0 until 4) { sum += amp * noise2(x * fr, y * fr, s + o * 31); fr *= 2.02f; amp *= 0.5f }
        return sum
    }
}
