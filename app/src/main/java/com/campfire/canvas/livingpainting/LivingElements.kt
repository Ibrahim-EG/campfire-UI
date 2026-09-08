package com.campfire.canvas.livingpainting

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---- CELESTIAL BODIES: huge soft radial gradients, 4% width sun ----
fun DrawScope.drawCelestial(x: Float, y: Float, isDay: Boolean, light: Color, screenW: Float) {
    drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.45f), light.copy(alpha = 0.12f), Color.Transparent), radius = screenW * 0.55f), center = Offset(x, y))
    if (isDay) {
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFD700), Color.Transparent), radius = (screenW * 0.04f).coerceAtLeast(1f)), center = Offset(x, y))
    } else {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE0E8F0), Color(0x80E0E8F0), Color.Transparent), radius = (screenW * 0.03f).coerceAtLeast(1f)), center = Offset(x, y))
        drawCircle(Color(0xFFB0BEC5), radius = screenW * 0.012f, center = Offset(x - screenW * 0.006f, y - screenW * 0.004f)) // crater whisper
    }
}

// ---- WATER LIFE: strip displacement (if painting) or animated specular field ----
fun DrawScope.drawWaterLife(painting: ImageBitmap?, horizonY: Float, lakeBottom: Float, time: Float, wind: Float, light: Color, size: Size, tint: ColorFilter?) {
    if (painting != null) {
        val stripH = 6f
        var y = horizonY
        while (y < lakeBottom) {
            val pr = ((y - horizonY) / (lakeBottom - horizonY)).coerceIn(0f, 1f)
            val amp = sin(pr * PI.toFloat()) * (6f + wind * 2f) // edge-pinned: zero seam at shores
            val off = sin(time * 1.8f + y * 0.12f) * amp
            val srcY = ((y / size.height) * painting.height).toInt().coerceIn(0, painting.height - 1)
            val srcH = max(1, ((stripH / size.height) * painting.height).toInt()).coerceAtMost(painting.height - srcY)
            if (srcH > 0) drawImage(painting, srcOffset = IntOffset(0, srcY), srcSize = IntSize(painting.width, srcH), dstOffset = IntOffset(off.roundToInt(), y.roundToInt()), dstSize = IntSize(size.width.roundToInt(), stripH.roundToInt()), colorFilter = tint)
            y += stripH
        }
    } else {
        // Procedural specular field: moving bright dashes over the cached lake
        for (i in 0 until 26) {
            val pr = i / 26f
            val y = horizonY + (lakeBottom - horizonY) * pr
            val drift = sin(time * (1.2f + pr) + i * 1.7f) * (14f + pr * 46f) + wind * 10f * pr
            val w = (26f + pr * 130f)
            val cx = size.width * 0.5f + drift
            drawLine(light.copy(alpha = (0.16f - pr * 0.10f).coerceAtLeast(0.02f)), Offset(cx - w / 2, y), Offset(cx + w / 2, y), strokeWidth = 1.5f + pr * 3f, cap = StrokeCap.Round)
        }
    }

    // Reflection pillar + 6 dashed shimmer lines (Lake Reflection Rule)
    val rx = size.width * 0.5f + cos(time * 0.05f) * 0f // pillar tracks celestial x passed via light position elsewhere; keep centered glow
    drawRect(Brush.verticalGradient(listOf(light.copy(alpha = 0.5f), light.copy(alpha = 0.06f), Color.Transparent), startY = horizonY, endY = lakeBottom), topLeft = Offset(rx - 70f, horizonY), size = Size(140f, lakeBottom - horizonY))
    val dash = Path()
    for (i in 0 until 6) {
        val y = horizonY + (lakeBottom - horizonY) * (i / 6f) + sin(time * 2f + i) * 2f
        dash.moveTo(rx - 55f - i * 6f, y)
        dash.lineTo(rx + 55f + i * 6f, y)
    }
    drawPath(dash, color = Color(0x66E0F7FA), style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(26f, 18f), time * 30f)))

    // Twinkling sparkles
    for (i in 0 until 14) {
        val tw = (sin(time * 3f + i * 2.4f) + 1f) * 0.5f
        val sx = size.width * (0.2f + 0.6f * ((i * 0.618f) % 1f))
        val sy = horizonY + (lakeBottom - horizonY) * ((i * 0.377f) % 1f)
        drawCircle(light.copy(alpha = 0.35f * tw * tw), radius = 1.5f + tw * 2f, center = Offset(sx, sy))
    }
}

// ---- SUMI-E BIRDS: sharp angular V strokes, flap phase, depth scale ----
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
data class SumiBirdProxy(val x: Float, val depth: Float, val phase: Float)

// ---- 3D STONE: ambient occlusion + radial volume + fire bleed ----
fun DrawScope.draw3DStone(center: Offset, radius: Float, fireCenter: Offset, fireLight: Color, flameOn: Boolean) {
    val d = sqrt((center.x - fireCenter.x) * (center.x - fireCenter.x) + (center.y - fireCenter.y) * (center.y - fireCenter.y))
    val li = if (flameOn) (1f / (1f + d * 0.0035f)).coerceIn(0f, 1f) else 0f
    drawOval(Color.Black.copy(alpha = 0.55f), topLeft = center + Offset(4f, 8f), size = Size(radius * 2.2f, radius * 1.35f))
    drawOval(Brush.radialGradient(listOf(Color(0xFF8D9BA6), Color(0xFF46545E), Color(0xFF141A1E)), center = center + Offset(-radius * 0.35f, -radius * 0.35f), radius = radius * 1.6f), topLeft = center - Offset(radius, radius * 0.78f), size = Size(radius * 2f, radius * 1.56f))
    if (li > 0.05f) drawOval(fireLight.copy(alpha = 0.45f * li), topLeft = center - Offset(radius, radius * 0.78f), size = Size(radius * 2f, radius * 1.56f))
}

// ---- 3D LOG: shadow, cylinder shading, bark grain, end-grain rings, ember cracks ----
fun DrawScope.draw3DLog(start: Offset, end: Offset, thickness: Float, char: Float, fireCenter: Offset, fireLight: Color, time: Float) {
    // char: 0 = fresh fuel, 1 = ash
    val bark = lerpColorSafe(Color(0xFF3E2723), Color(0xFF212121), char.coerceIn(0f, 0.6f))
    val lite = lerpColorSafe(Color(0xFF5D4037), Color(0xFF424242), char.coerceIn(0f, 0.6f))
    val ashT = char.coerceIn(0f, 1f)
    drawLine(Color.Black.copy(alpha = 0.65f), start + Offset(5f, 12f), end + Offset(5f, 12f), strokeWidth = thickness, cap = StrokeCap.Round)
    drawLine(bark, start + Offset(0f, thickness / 4), end + Offset(0f, thickness / 4), strokeWidth = thickness / 2, cap = StrokeCap.Round)
    drawLine(lite, start, end, strokeWidth = thickness, cap = StrokeCap.Round)
    drawLine(lite.copy(alpha = 0.55f), start + Offset(0f, -thickness / 4), end + Offset(0f, -thickness / 4), strokeWidth = thickness / 3, cap = StrokeCap.Round)
    // bark grain
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
        // glowing ember cracks pulsing with time
        val pulse = 0.6f + 0.4f * sin(time * 7f + start.x)
        drawLine(fireLight.copy(alpha = 0.7f * pulse * ashT), start + Offset(thickness * 0.4f, 0f), end - Offset(thickness * 0.4f, 0f), strokeWidth = 2.5f, cap = StrokeCap.Round)
    }
    // ash whitening at full consumption
    if (ashT > 0.55f) drawLine(Color(0xFF9E9E9E).copy(alpha = (ashT - 0.55f) * 1.6f), start, end, strokeWidth = thickness * 0.8f, cap = StrokeCap.Round)
    val d = sqrt(((start.x + end.x) / 2 - fireCenter.x) * ((start.x + end.x) / 2 - fireCenter.x) + ((start.y + end.y) / 2 - fireCenter.y) * ((start.y + end.y) / 2 - fireCenter.y))
    val li = (1f / (1f + d * 0.004f)).coerceIn(0f, 1f)
    if (li > 0.05f) drawLine(fireLight.copy(alpha = 0.28f * li), start, end, strokeWidth = thickness, cap = StrokeCap.Round)
}
private fun lerpColorSafe(a: Color, b: Color, t: Float) = lerpColor(a, b, t)

// ---- FLAME: three morphing bezier bodies + white core ----
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
    drawPath(mid, Color(0xE6FFC107)) // gamboge body
    val core = Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 22f * scale, center.y - h * 0.12f, center.x - 11f * scale + n1 * 5f * scale, center.y - h * 0.34f, center.x, center.y - h * 0.44f)
        cubicTo(center.x + 11f * scale - n1 * 5f * scale, center.y - h * 0.34f, center.x + 22f * scale, center.y - h * 0.12f, center.x, center.y)
        close()
    }
    drawPath(core, Color(0xFFFFF8E1)) // warm white heart
}

// ---- SMOKE: three wind-bent ribbons ----
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

// ---- CIRCADIAN GLAZES: multiply night, screen dusk warmth ----
fun DrawScope.drawGlazes(elevation: Float) {
    val nightI = ((1f - (elevation + 90f) / 180f).coerceIn(0f, 1f))
    if (nightI > 0.02f) drawRect(PaletteNight.copy(alpha = nightI * 0.75f), blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
    val duskI = bell(elevation, 4f, 22f)
    if (duskI > 0.02f) drawRect(PaletteDusk.copy(alpha = duskI * 0.22f), blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
}
private val PaletteNight = Color(0xFF0A1030)
private val PaletteDusk = Color(0xFFFF7043)
