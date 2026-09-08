package com.campfire.canvas.livingpainting

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// deterministic hash for stateless "hand-placed" nature
private fun hash01(i: Int): Float {
    var x = i.toLong()
    x = (x shl 13) xor x
    x = (x * (x * x * 15731L + 789221L) + 1376312589L) and 0x7fffffffL
    return (x / 1073741824f) * 0.5f // 0..1
}

// ---- CELESTIAL: huge soft radial sun/moon, 4% width core ----
fun DrawScope.drawCelestial(x: Float, y: Float, isDay: Boolean, light: Color, screenW: Float) {
    drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.45f), light.copy(alpha = 0.12f), Color.Transparent), radius = screenW * 0.55f), center = Offset(x, y))
    if (isDay) {
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFD700), Color.Transparent), radius = (screenW * 0.04f).coerceAtLeast(1f)), center = Offset(x, y))
    } else {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE0E8F0), Color(0x80E0E8F0), Color.Transparent), radius = (screenW * 0.03f).coerceAtLeast(1f)), center = Offset(x, y))
        drawCircle(Color(0xFFB0BEC5), radius = screenW * 0.011f, center = Offset(x - screenW * 0.006f, y - screenW * 0.004f))
    }
}

// ---- TWINKLING STARS: 46 hand-placed points breathing at individual rates ----
fun DrawScope.drawTwinklingStars(size: Size, time: Float, night: Float, horizonY: Float) {
    if (night < 0.05f) return
    for (i in 0 until 46) {
        val x = hash01(i * 3 + 11) * size.width
        val y = hash01(i * 7 + 29) * horizonY * 0.8f
        val rate = 0.8f + hash01(i * 13 + 5) * 2.4f
        val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * rate + i * 1.7f))
        drawCircle(Color(0xFFE0E8F0).copy(alpha = tw * night * 0.9f), radius = 0.8f + hash01(i + 71) * 1.6f, center = Offset(x, y))
    }
}

// ---- DRIFTING CLOUDS: five soft lobed masses crawling across the sky ----
fun DrawScope.drawClouds(size: Size, time: Float, horizonY: Float, tint: Color) {
    for (c in 0 until 5) {
        val speed = 5f + c * 3.5f
        val span = size.width + 500f
        var x = (hash01(c * 17 + 3) * span + time * speed) % span
        x -= 250f
        val y = horizonY * (0.10f + 0.20f * hash01(c + 91))
        val rx = (70f + 110f * hash01(c + 41))
        for (lobe in 0 until 3) {
            val lx = x + (lobe - 1) * rx * 0.75f
            val ly = y + (if (lobe == 1) -rx * 0.18f else rx * 0.06f)
            val lr = rx * (if (lobe == 1) 0.85f else 0.6f)
            drawOval(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.14f), tint.copy(alpha = 0.05f), Color.Transparent), center = Offset(lx, ly), radius = lr),
                topLeft = Offset(lx - lr, ly - lr * 0.42f),
                size = Size(lr * 2f, lr * 0.84f)
            )
        }
    }
}

// ---- WATER LIFE: moving specular field, glitter pillar, dashed shimmer, breathing foam ----
fun DrawScope.drawWaterLife(horizonY: Float, lakeBottom: Float, time: Float, wind: Float, light: Color, size: Size) {
    for (i in 0 until 26) {
        val pr = i / 26f
        val y = horizonY + (lakeBottom - horizonY) * pr
        val drift = sin(time * (1.2f + pr) + i * 1.7f) * (14f + pr * 46f) + wind * 10f * pr
        val w = 26f + pr * 130f
        val cx = size.width * 0.5f + drift
        drawLine(light.copy(alpha = (0.16f - pr * 0.10f).coerceAtLeast(0.02f)), Offset(cx - w / 2, y), Offset(cx + w / 2, y), strokeWidth = 1.5f + pr * 3f, cap = StrokeCap.Round)
    }
    val rx = size.width * 0.5f
    drawRect(Brush.verticalGradient(listOf(light.copy(alpha = 0.5f), light.copy(alpha = 0.06f), Color.Transparent), startY = horizonY, endY = lakeBottom), topLeft = Offset(rx - 70f, horizonY), size = Size(140f, lakeBottom - horizonY))
    val dash = Path()
    for (i in 0 until 6) {
        val y = horizonY + (lakeBottom - horizonY) * (i / 6f) + sin(time * 2f + i) * 2f
        dash.moveTo(rx - 55f - i * 6f, y)
        dash.lineTo(rx + 55f + i * 6f, y)
    }
    drawPath(dash, color = Color(0x66E0F7FA), style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(26f, 18f), time * 30f)))
    for (i in 0 until 14) {
        val tw = (sin(time * 3f + i * 2.4f) + 1f) * 0.5f
        val sx = size.width * (0.2f + 0.6f * hash01(i * 5 + 13))
        val sy = horizonY + (lakeBottom - horizonY) * hash01(i * 11 + 27)
        drawCircle(light.copy(alpha = 0.35f * tw * tw), radius = 1.5f + tw * 2f, center = Offset(sx, sy))
    }
    // breathing foam line at the shore
    val foam = Path()
    foam.moveTo(0f, lakeBottom - 2f)
    var fx = 0f
    while (fx <= size.width) {
        foam.lineTo(fx, lakeBottom - 2f + sin(fx * 0.045f + time * 2.2f) * 1.8f)
        fx += 18f
    }
    drawPath(foam, color = Color(0x40E0F7FA), style = Stroke(width = 2f))
}

// ---- SUMI-E BIRDS ----
data class SumiBirdProxy(val x: Float, val depth: Float, val phase: Float)
fun DrawScope.drawBirds(birds: List<SumiBirdProxy>, time: Float, size: Size) {
    birds.forEach { b ->
        val scale = 1f - b.depth * 0.6f
        val x = b.x * size.width
        val y = size.height * (0.10f + b.depth * 0.16f) + sin(time * 0.7f + b.phase) * 6f
        val flap = sin(time * (6f - b.depth * 2f) + b.phase) * 6f * scale
        val path = Path().apply {
            moveTo(x - 14f * scale, y)
            lineTo(x - 4f * scale, y - 7f * scale - flap)
            lineTo(x, y - 2f * scale)
            lineTo(x + 4f * scale, y - 7f * scale - flap)
            lineTo(x + 14f * scale, y)
        }
        drawPath(path, color = Color(0xE6101010), style = Stroke(width = 2.2f * scale, cap = StrokeCap.Round))
    }
}

// ---- SWAYING GRASS FRINGE: 230 hand-animated blades in three wind-combed passes ----
fun DrawScope.drawGrassFringe(size: Size, time: Float, wind: Float) {
    val groups = arrayOf(
        Triple(Color(0xFF1B5E20), 1.8f, 90),  // dark undergrowth
        Triple(Color(0xFF33691E), 2.4f, 80),  // sap body
        Triple(Color(0xFF7CB342), 3.0f, 60)   // lit tips
    )
    groups.forEachIndexed { g, grp ->
        val path = Path()
        for (i in 0 until grp.third) {
            val seed = i * 37 + g * 991
            val x = hash01(seed) * size.width
            val depth = hash01(seed + 1)
            val yBase = size.height * (0.88f + 0.12f * depth)
            val len = (14f + 24f * hash01(seed + 2)) * (0.7f + 0.6f * depth)
            val lean = (wind * 0.5f + sin(time * 1.5f + x * 0.02f) * 0.35f) * len * 0.55f
            path.moveTo(x, yBase)
            path.quadraticBezierTo(x + lean * 0.4f, yBase - len * 0.6f, x + lean, yBase - len)
            if (g == 2 && i % 6 == 0) {
                // wildflower heads riding the sway
                drawCircle(if (hash01(seed + 3) > 0.5f) Color(0xFFFFF59D) else Color(0xFFF48FB1), radius = 2.2f, center = Offset(x + lean, yBase - len))
            }
        }
        drawPath(path, color = grp.first.copy(alpha = 0.85f), style = Stroke(width = grp.second, cap = StrokeCap.Round))
    }
}

// ---- FIREFLIES: wandering gamboge sparks, night only ----
fun DrawScope.drawFireflies(size: Size, time: Float, night: Float) {
    if (night < 0.15f) return
    for (i in 0 until 14) {
        val bx = size.width * hash01(i * 3 + 1) + sin(time * 0.31f + i * 2.1f) * 46f
        val by = size.height * (0.76f + 0.20f * hash01(i * 7 + 2)) + cos(time * 0.27f + i * 1.4f) * 26f
        val blink = max(0f, sin(time * 1.7f + i * 1.3f))
        val a = blink * blink * night
        drawCircle(Color(0xFFFFC107).copy(alpha = a * 0.22f), radius = 7f, center = Offset(bx, by)) // halo
        drawCircle(Color(0xFFFFF59D).copy(alpha = a * 0.85f), radius = 2.2f, center = Offset(bx, by)) // body
    }
}

// ---- CIRCADIAN GLAZES ----
fun DrawScope.drawGlazes(elevation: Float) {
    val nightI = ((1f - (elevation + 90f) / 180f).coerceIn(0f, 1f))
    if (nightI > 0.02f) drawRect(Color(0xFF0A1030).copy(alpha = nightI * 0.75f), blendMode = BlendMode.Multiply)
    val duskI = bell(elevation, 4f, 22f)
    if (duskI > 0.02f) drawRect(Color(0xFFFF7043).copy(alpha = duskI * 0.22f), blendMode = BlendMode.Screen)
}

// ---- 3D STONE ----
fun DrawScope.draw3DStone(center: Offset, radius: Float, fireCenter: Offset, fireLight: Color, flameOn: Boolean) {
    val d = sqrt((center.x - fireCenter.x) * (center.x - fireCenter.x) + (center.y - fireCenter.y) * (center.y - fireCenter.y))
    val li = if (flameOn) (1f / (1f + d * 0.0035f)).coerceIn(0f, 1f) else 0f
    drawOval(Color.Black.copy(alpha = 0.55f), topLeft = center + Offset(4f, 8f), size = Size(radius * 2.2f, radius * 1.35f))
    drawOval(Brush.radialGradient(listOf(Color(0xFF8D9BA6), Color(0xFF46545E), Color(0xFF141A1E)), center = center + Offset(-radius * 0.35f, -radius * 0.35f), radius = radius * 1.6f), topLeft = center - Offset(radius, radius * 0.78f), size = Size(radius * 2f, radius * 1.56f))
    if (li > 0.05f) drawOval(fireLight.copy(alpha = 0.45f * li), topLeft = center - Offset(radius, radius * 0.78f), size = Size(radius * 2f, radius * 1.56f))
}

// ---- 3D LOG with char/ash lifecycle ----
fun DrawScope.draw3DLog(start: Offset, end: Offset, thickness: Float, char: Float, fireCenter: Offset, fireLight: Color, time: Float) {
    val bark = lerpColor(Color(0xFF3E2723), Color(0xFF212121), char.coerceIn(0f, 0.6f))
    val lite = lerpColor(Color(0xFF5D4037), Color(0xFF424242), char.coerceIn(0f, 0.6f))
    val ashT = char.coerceIn(0f, 1f)
    drawLine(Color.Black.copy(alpha = 0.65f), start + Offset(5f, 12f), end + Offset(5f, 12f), strokeWidth = thickness, cap = StrokeCap.Round)
    drawLine(bark, start + Offset(0f, thickness / 4), end + Offset(0f, thickness / 4), strokeWidth = thickness / 2, cap = StrokeCap.Round)
    drawLine(lite, start, end, strokeWidth = thickness, cap = StrokeCap.Round)
    drawLine(lite.copy(alpha = 0.55f), start + Offset(0f, -thickness / 4), end + Offset(0f, -thickness / 4), strokeWidth = thickness / 3, cap = StrokeCap.Round)
    val ang = kotlin.math.atan2(end.y - start.y, end.x - start.x)
    for (g in 0 until 3) {
        val o = (g - 1) * thickness / 4
        val px = -sin(ang) * o; val py = cos(ang) * o
        drawLine(Color(0xFF241511).copy(alpha = 0.5f), start + Offset(px, py), end + Offset(px, py), strokeWidth = 1.6f, cap = StrokeCap.Round)
    }
    if (char < 0.5f) {
        val r = thickness / 2f
        drawOval(Color(0xFF8D6E63), topLeft = end - Offset(r, r), size = Size(r * 2, r * 2))
        drawOval(Color(0xFF5D4037), topLeft = end - Offset(r * 0.68f, r * 0.68f), size = Size(r * 1.36f, r * 1.36f))
        drawOval(Color(0xFF3E2723), topLeft = end - Offset(r * 0.36f, r * 0.36f), size = Size(r * 0.72f, r * 0.72f))
    } else {
        val pulse = 0.6f + 0.4f * sin(time * 7f + start.x)
        drawLine(fireLight.copy(alpha = 0.7f * pulse * ashT), start + Offset(thickness * 0.4f, 0f), end - Offset(thickness * 0.4f, 0f), strokeWidth = 2.5f, cap = StrokeCap.Round)
    }
    if (ashT > 0.55f) drawLine(Color(0xFF9E9E9E).copy(alpha = (ashT - 0.55f) * 1.6f), start, end, strokeWidth = thickness * 0.8f, cap = StrokeCap.Round)
    val d = sqrt(((start.x + end.x) / 2 - fireCenter.x) * ((start.x + end.x) / 2 - fireCenter.x) + ((start.y + end.y) / 2 - fireCenter.y) * ((start.y + end.y) / 2 - fireCenter.y))
    val li = (1f / (1f + d * 0.004f)).coerceIn(0f, 1f)
    if (li > 0.05f) drawLine(fireLight.copy(alpha = 0.28f * li), start, end, strokeWidth = thickness, cap = StrokeCap.Round)
}

// ---- FLAME: three morphing bezier bodies ----
fun DrawScope.drawFlame(center: Offset, scale: Float, time: Float) {
    val n1 = sin(time * 9.3f) + 0.5f * sin(time * 14.7f + 1.3f)
    val n2 = cos(time * 7.1f + 0.7f) + 0.5f * sin(time * 11.9f)
    val h = 260f * scale
    val outer = Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 78f * scale, center.y - h * 0.25f, center.x - 42f * scale + n1 * 14f * scale, center.y - h * 0.85f, center.x + n2 * 8f * scale, center.y - h)
        cubicTo(center.x + 42f * scale - n1 * 14f * scale, center.y - h * 0.85f, center.x + 78f * scale, center.y - h * 0.25f, center.x, center.y)
        close()
    }
    drawPath(outer, Brush.radialGradient(listOf(Color(0xCCFF7043), Color(0x66FF5722), Color.Transparent), center = center, radius = h))
    val mid = Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 48f * scale, center.y - h * 0.22f, center.x - 24f * scale + n2 * 10f * scale, center.y - h * 0.62f, center.x + n1 * 6f * scale, center.y - h * 0.74f)
        cubicTo(center.x + 24f * scale - n2 * 10f * scale, center.y - h * 0.62f, center.x + 48f * scale, center.y - h * 0.22f, center.x, center.y)
        close()
    }
    drawPath(mid, Color(0xE6FFC107))
    val core = Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 22f * scale, center.y - h * 0.12f, center.x - 11f * scale + n1 * 5f * scale, center.y - h * 0.34f, center.x, center.y - h * 0.44f)
        cubicTo(center.x + 11f * scale - n1 * 5f * scale, center.y - h * 0.34f, center.x + 22f * scale, center.y - h * 0.12f, center.x, center.y)
        close()
    }
    drawPath(core, Color(0xFFFFF8E1))
}

// ---- SMOKE: wind-bent ribbons ----
fun DrawScope.drawSmoke(center: Offset, scale: Float, time: Float, wind: Float, flameScale: Float) {
    for (r in 0 until 3) {
        var px = center.x + (r - 1) * 14f * scale
        var py = center.y - 240f * scale * flameScale
        var alpha = 0.16f
        var width = 6f * scale
        for (s in 0 until 10) {
            val nx = px + sin(time * 1.3f + s * 0.7f + r) * 8f + wind * 6f * s * 0.4f
            val ny = py - 26f * scale
            drawLine(Color(0xFF9AA7B0).copy(alpha = alpha), Offset(px, py), Offset(nx, ny), strokeWidth = width, cap = StrokeCap.Round)
            px = nx; py = ny; alpha *= 0.78f; width *= 1.22f
        }
    }
}
