package com.campfire.canvas.livingpainting

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

// Deterministic 1D value noise -> fractal geology. Same seed = same mountains forever.
private fun hash1(i: Int): Float {
    var x = i.toLong()
    x = (x shl 13) xor x
    x = (x * (x * x * 15731L + 789221L) + 1376312589L) and 0x7fffffffL
    return x / 1073741824f - 1f
}
private fun smooth(t: Float) = t * t * (3f - 2f * t)
private fun valueNoise(x: Float, seed: Int): Float {
    val i = kotlin.math.floor(x).toInt()
    val f = x - i
    return hash1(i + seed) * (1f - smooth(f)) + hash1(i + 1 + seed) * smooth(f)
}
private fun fbm(x: Float, seed: Int, octaves: Int = 4): Float {
    var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
    for (o in 0 until octaves) {
        sum += valueNoise(x * freq, seed + o * 101) * amp
        norm += amp; amp *= 0.5f; freq *= 2.1f
    }
    return sum / norm
}
private fun ridged(x: Float, seed: Int): Float = 1f - abs(fbm(x, seed)) * 2f

/**
 * Paints Layers 1-4 of the doctrine into ONE cached ImageBitmap (Hardware Caching clause).
 * Runs on a worker thread; the software Canvas here safely uses BlurMaskFilter for true mist.
 */
fun paintStaticWorld(pxW: Int, pxH: Int, horizonFrac: Float, lakeEndFrac: Float, fireXFrac: Float, fireYFrac: Float): ImageBitmap {
    val cap = 2400
    val scale = minOf(cap.toFloat() / pxW, cap.toFloat() / pxH).coerceAtMost(2f).coerceAtLeast(0.5f)
    val w = (pxW * scale).toInt().coerceAtLeast(2)
    val h = (pxH * scale).toInt().coerceAtLeast(2)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint().apply { isAntiAlias = true }
    val rnd = Random(20240607)
    val W = w.toFloat(); val H = h.toFloat()
    val horizon = H * horizonFrac
    val lakeEnd = H * lakeEndFrac
    val fireX = W * fireXFrac
    val fireY = H * fireYFrac

    // ---- LAYER 1: CHROMATIC SKYDOME ----
    p.shader = LinearGradient(0f, 0f, 0f, horizon,
        intArrayOf(0xFF0B1021.toInt(), 0xFF1A237E.toInt(), 0xFF3949AB.toInt(), 0xFF8C9EFF.toInt()),
        floatArrayOf(0f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP) // zenith prussian -> periwinkle kiss
    c.drawRect(0f, 0f, W, horizon, p)
    p.shader = null

    // Stars: seeded magnitudes, a few cross-sparkles
    for (i in 0 until 260) {
        val x = rnd.nextFloat() * W
        val y = rnd.nextFloat() * horizon * 0.75f
        val m = rnd.nextFloat()
        p.color = 0xFFFFFFFF.toInt()
        p.alpha = (40 + m * 160).toInt()
        c.drawCircle(x, y, 0.6f + m * 1.4f * scale, p)
        if (m > 0.965f) {
            p.alpha = 90
            p.strokeWidth = 1f * scale
            c.drawLine(x - 4f * scale, y, x + 4f * scale, y, p)
            c.drawLine(x, y - 4f * scale, x, y + 4f * scale, p)
        }
    }

    // Clouds: true soft blobs via BlurMaskFilter (software canvas = safe)
    p.maskFilter = BlurMaskFilter(28f * scale, BlurMaskFilter.Blur.NORMAL)
    for (b in 0 until 7) {
        val cx = rnd.nextFloat() * W
        val cy = horizon * (0.15f + rnd.nextFloat() * 0.5f)
        val rx = (60f + rnd.nextFloat() * 140f) * scale
        val ry = rx * (0.22f + rnd.nextFloat() * 0.15f)
        p.color = 0xFFB0A6C9.toInt() // dusty violet cloud mass
        p.alpha = (26 + rnd.nextFloat() * 30).toInt()
        c.drawOval(android.graphics.RectF(cx - rx, cy - ry, cx + rx, cy + ry), p)
    }
    p.maskFilter = null

    // ---- LAYER 2: DISTANT PINES & FRACTAL MOUNTAINS (atmospheric perspective) ----
    val layerTint = intArrayOf(0xFF5C6B8A.toInt(), 0xFF33415C.toInt(), 0xFF12202C.toInt()) // far bleeds to sky, near goes dark
    for (layer in 0 until 3) {
        val base = horizon - (18 + layer * 26) * scale
        val amp = (34 + layer * 30) * scale
        val path = Path()
        path.moveTo(0f, horizon + 2)
        var x = 0f
        while (x <= W) {
            val y = base - ridged(x * (0.006f + layer * 0.002f) / scale, 900 + layer * 77) * amp
            path.lineTo(x, y)
            x += 6f * scale
        }
        path.lineTo(W, horizon + 2)
        path.close()
        p.color = layerTint[layer]
        p.alpha = if (layer == 0) 150 else 255
        c.drawPath(path, p)
    }

    // Mist band bleeding mountains into lake (color bleeding clause)
    p.maskFilter = BlurMaskFilter(18f * scale, BlurMaskFilter.Blur.NORMAL)
    p.color = 0xFF90A4AE.toInt(); p.alpha = 70
    c.drawRect(0f, horizon - 26f * scale, W, horizon + 10f * scale, p)
    p.maskFilter = null

    // Pines: recursive tier silhouettes on the far shore
    fun pine(x: Float, baseY: Float, ph: Float, color: Int, alpha: Int) {
        p.color = color; p.alpha = alpha
        c.drawRect(x - ph * 0.04f, baseY - ph * 0.18f, x + ph * 0.04f, baseY, p)
        for (t in 0 until 4) {
            val tw = ph * (0.34f - t * 0.07f)
            val ty = baseY - ph * (0.16f + t * 0.22f)
            val th = ph * 0.30f
            val tri = Path()
            tri.moveTo(x, ty - th)
            tri.lineTo(x - tw + rnd.nextFloat() * tw * 0.2f, ty)
            tri.lineTo(x + tw - rnd.nextFloat() * tw * 0.2f, ty)
            tri.close()
            c.drawPath(tri, p)
        }
    }
    for (i in 0 until 26) {
        val x = rnd.nextFloat() * W
        val ph = (26 + rnd.nextFloat() * 46) * scale
        pine(x, horizon + 4 * scale, ph, 0xFF1B2A38.toInt(), 200) // blue-grey receding pines
    }

    // ---- LAYER 3: BROKEN-COLOR LAKE ----
    p.shader = LinearGradient(0f, horizon, 0f, lakeEnd,
        intArrayOf(0xFF0277BD.toInt(), 0xFF0B1320.toInt()), null, Shader.TileMode.CLAMP) // cerulean -> abyss
    c.drawRect(0f, horizon, W, lakeEnd, p)
    p.shader = null

    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    val broken = intArrayOf(0xFF00838F.toInt(), 0xFFFFF59D.toInt(), 0xFFF48FB1.toInt(), 0xFF4DD0E1.toInt())
    var row = 0
    var y = horizon + 4 * scale
    while (y < lakeEnd) {
        val depth = (y - horizon) / (lakeEnd - horizon)
        p.strokeWidth = (1.5f + depth * 3.5f) * scale
        var x = -20f
        while (x < W) {
            val len = (14f + rnd.nextFloat() * 60f) * (1f + depth * 1.6f) * scale
            p.color = broken[(rnd.nextInt(4) + row) % 4]
            p.alpha = (34 + rnd.nextFloat() * 90).toInt()
            c.drawLine(x, y, x + len, y, p)
            x += len + (8f + rnd.nextFloat() * 30f) * scale
        }
        y += (4f + depth * 6f) * scale
        row++
    }
    p.style = Paint.Style.FILL

    // Wet shore + foam dashes where water meets hill
    p.color = 0xFF0A0F14.toInt(); p.alpha = 200
    c.drawRect(0f, lakeEnd - 5 * scale, W, lakeEnd + 2 * scale, p)
    p.color = 0xFFE0F7FA.toInt(); p.alpha = 90
    p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * scale
    var fx = 0f
    while (fx < W) {
        val len = (10 + rnd.nextFloat() * 40) * scale
        c.drawLine(fx, lakeEnd - 2 * scale, fx + len, lakeEnd - 2 * scale, p)
        fx += len + 24 * scale
    }
    p.style = Paint.Style.FILL

    // ---- LAYER 4: THE HIGH HILL + IMPASTO GRASS ----
    val hill = Path()
    hill.moveTo(0f, H)
    hill.lineTo(0f, H * 0.86f)
    hill.cubicTo(W * 0.18f, H * 0.76f, W * 0.34f, H * (fireYFrac + 0.015f), W * fireXFrac, H * fireYFrac + 26 * scale)
    hill.cubicTo(W * 0.66f, H * (fireYFrac + 0.015f), W * 0.82f, H * 0.90f, W, H * 0.86f)
    hill.lineTo(W, H)
    hill.close()
    p.shader = LinearGradient(0f, H * 0.74f, 0f, H,
        intArrayOf(0xFF2E4028.toInt(), 0xFF16241A.toInt(), 0xFF0A120B.toInt()), null, Shader.TileMode.CLAMP) // sap green -> loam
    c.drawPath(hill, p)
    p.shader = null

    // Dirt clearing under the hearth (soft, blurred)
    p.maskFilter = BlurMaskFilter(14f * scale, BlurMaskFilter.Blur.NORMAL)
    p.color = 0xFF241A12.toInt(); p.alpha = 150
    c.drawOval(android.graphics.RectF(fireX - 190 * scale, fireY - 46 * scale, fireX + 190 * scale, fireY + 60 * scale), p)
    p.maskFilter = null

    // 4000 impasto grass strokes in three depth passes
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    for (pass in 0 until 3) {
        val count = if (pass == 0) 1600 else if (pass == 1) 1500 else 900
        for (i in 0 until count) {
            val gx = rnd.nextFloat() * W
            val gy = H * (0.80f + rnd.nextFloat() * 0.20f)
            val depth = ((gy - H * 0.80f) / (H * 0.20f)).coerceIn(0f, 1f)
            val len = (5f + pass * 5f + depth * 14f + rnd.nextFloat() * 8f) * scale
            val lean = (rnd.nextFloat() - 0.5f) * len * 0.9f
            p.strokeWidth = (0.8f + pass * 0.9f + depth * 1.6f) * scale
            p.color = when (pass) {
                0 -> 0xFF1B5E20.toInt()   // dark sap undergrowth
                1 -> 0xFF33691E.toInt()   // mid sap body
                else -> 0xFF7CB342.toInt()// lit tips catching sky
            }
            p.alpha = (110 + rnd.nextFloat() * 145).toInt()
            c.drawLine(gx, gy, gx + lean, gy - len, p)
        }
    }
    // Grass clumps: quadratic fronds
    p.style = Paint.Style.STROKE
    for (i in 0 until 240) {
        val gx = rnd.nextFloat() * W
        val gy = H * (0.84f + rnd.nextFloat() * 0.16f)
        val len = (14f + rnd.nextFloat() * 18f) * scale
        val dir = if (rnd.nextBoolean()) 1f else -1f
        p.color = 0xFF2E7D32.toInt(); p.alpha = 190
        p.strokeWidth = 1.6f * scale
        val fr = Path()
        fr.moveTo(gx, gy)
        fr.quadTo(gx + dir * len * 0.25f, gy - len * 0.7f, gx + dir * len * 0.6f, gy - len)
        c.drawPath(fr, p)
    }
    // Sparse wildflowers: pale gold + rose pinpricks
    for (i in 0 until 70) {
        val gx = rnd.nextFloat() * W
        val gy = H * (0.83f + rnd.nextFloat() * 0.15f)
        p.color = if (rnd.nextBoolean()) 0xFFFFF59D.toInt() else 0xFFF48FB1.toInt()
        p.alpha = 170
        c.drawCircle(gx, gy, (1.1f + rnd.nextFloat() * 1.3f) * scale, p)
    }
    p.style = Paint.Style.FILL

    // ---- CANVAS TOOTH & VIGNETTE (the painter's varnish) ----
    for (i in 0 until 5000) {
        val x = rnd.nextFloat() * W
        val yy = rnd.nextFloat() * H
        p.color = if (rnd.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        p.alpha = 5 + rnd.nextInt(7)
        c.drawPoint(x, yy, p)
    }
    p.shader = RadialGradient(W / 2f, H / 2f, maxOf(W, H) * 0.72f,
        intArrayOf(0x00000000, 0x55000000), null, Shader.TileMode.CLAMP) // edge vignette
    c.drawRect(0f, 0f, W, H, p)
    p.shader = null

    return bmp.asImageBitmap()
}
