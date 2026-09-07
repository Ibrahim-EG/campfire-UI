package com.campfire.canvas.livingpainting

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The painter's palette, delivered by you, animated by me
data class PaintingAssets(
    val sky: ImageBitmap? = null,
    val water: ImageBitmap? = null,
    val fore: ImageBitmap? = null,
    val glow: ImageBitmap? = null,
    val base: ImageBitmap? = null
) {
    val hasLayers get() = sky != null && water != null && fore != null
    val hasAny get() = hasLayers || base != null
}

class MainActivity : ComponentActivity() {
    private val viewModel: PaintingViewModel by viewModels()
    private val audioEngine = CozyAudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashBlackBox()
        super.onCreate(savedInstanceState)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
        setContent {
            DisposableEffect(Unit) {
                audioEngine.start()
                onDispose { audioEngine.stop() }
            }
            CampfireCanvasApp(viewModel, audioEngine)
        }
    }

    private fun installCrashBlackBox() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = "Thread: ${thread.name}\n" + Log.getStackTraceString(error)
                getSharedPreferences("blackbox", MODE_PRIVATE).edit().putString("last_crash", trace).apply()
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "campfire_crash_${System.currentTimeMillis()}.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CampfireCanvas")
                }
                contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                    ?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(trace.toByteArray()) } }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onPause() { super.onPause(); audioEngine.pause(); viewModel.isPaused.value = true }
    override fun onResume() { super.onResume(); audioEngine.resume(); viewModel.isPaused.value = false }
}

@Composable
fun CampfireCanvasApp(viewModel: PaintingViewModel, audioEngine: CozyAudioEngine) {
    val context = LocalContext.current

    val targetElevation by viewModel.sunElevation
    // Cubic easing for the 90-second sunset bleed
    val effectiveElevation by animateFloatAsState(
        targetValue = targetElevation,
        animationSpec = tween(durationMillis = 90_000, easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)),
        label = "CircadianElevation"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (constraints.maxWidth == 0 || constraints.maxHeight == 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            return@BoxWithConstraints
        }

        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val horizonY = size.height * 0.55f
        val lakeBottom = size.height * 0.75f
        val fireCenter = Offset(size.width * 0.5f, size.height * 0.82f)

        // Load your painted layers at runtime by name. Missing = graceful procedural fallback.
        var assets by remember { mutableStateOf(PaintingAssets()) }
        LaunchedEffect(Unit) {
            assets = withContext(Dispatchers.Default) {
                val res = context.resources
                fun load(name: String): ImageBitmap? = runCatching {
                    val id = res.getIdentifier(name, "drawable", context.packageName)
                    if (id == 0) null else BitmapFactory.decodeResource(res, id)?.asImageBitmap()
                }.getOrNull()
                PaintingAssets(
                    sky = load("painting_sky"),
                    water = load("painting_water"),
                    fore = load("painting_fore"),
                    glow = load("painting_glow"),
                    base = load("painting_base")
                )
            }
        }

        val woodStack = remember {
            mutableStateListOf(
                Offset(size.width * 0.12f, size.height * 0.88f),
                Offset(size.width * 0.18f, size.height * 0.92f),
                Offset(size.width * 0.25f, size.height * 0.89f)
            )
        }
        var draggingLogIndex by remember { mutableStateOf(-1) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        val isDrawerOpen = remember { mutableStateOf(false) }
        val logsBurning = viewModel.logsBurning
        val flameScale = (logsBurning.value / 5f).coerceAtLeast(0.05f)
        val time = viewModel.cinematicTime.value / 1_000_000_000f

        LaunchedEffect(logsBurning.value) { audioEngine.updateFireIntensity(logsBurning.value / 5f) }

        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        woodStack.forEachIndexed { index, pos ->
                            if (offset.x > pos.x && offset.x < pos.x + 100 && offset.y > pos.y && offset.y < pos.y + 40) {
                                draggingLogIndex = index; dragOffset = Offset.Zero
                            }
                        }
                    },
                    onDrag = { change, dragAmount -> if (draggingLogIndex != -1) { dragOffset += dragAmount; change.consume() } },
                    onDragEnd = {
                        if (draggingLogIndex != -1) {
                            val dropPos = woodStack[draggingLogIndex] + dragOffset
                            if (sqrt((dropPos.x - fireCenter.x) * (dropPos.x - fireCenter.x) + (dropPos.y - fireCenter.y) * (dropPos.y - fireCenter.y)) < 150f && logsBurning.value < 5) {
                                logsBurning.value++; audioEngine.playThump()
                            }
                        }
                        draggingLogIndex = -1; dragOffset = Offset.Zero
                    }
                )
            }
        ) {
            try {
                val isDay = effectiveElevation > 0
                val sunAngle = (effectiveElevation / 90f) * PI.toFloat()
                val sunX = size.width * (0.5f + 0.4f * cos(sunAngle))
                val sunY = horizonY - (size.height * 0.45f * sin(sunAngle))
                val moonY = horizonY - (size.height * 0.35f * sin(sunAngle + PI.toFloat()))
                val celestialX = sunX
                val celestialY = if (isDay) sunY else moonY

                val globalLightColor = when {
                    effectiveElevation > 30 -> Color(0xFFFFD54F)
                    effectiveElevation > 0 -> Color(0xFFFF7043)
                    effectiveElevation > -30 -> Color(0xFFBA68C8)
                    else -> Color(0xFF90A4AE)
                }

                // TECHNIQUE 3: Circadian ColorMatrix — your painting changes mood with the sun
                val circadian = ColorFilter.colorMatrix(ColorMatrix(getCircadianMatrix(effectiveElevation)))

                // ---------- THE WORLD ----------
                if (assets.hasLayers) {
                    // Layered mode: your art, my physics
                    drawImage(assets.sky!!, topLeft = Offset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = circadian)

                    // TECHNIQUE 2: Strip-displaced water — painted ripples physically sway
                    drawDisplacedWater(assets.water!!, horizonY, lakeBottom, time, size, circadian)

                    // Sun/moon glitter pillar tracks the celestial body across YOUR water
                    drawRect(
                        Brush.verticalGradient(listOf(globalLightColor.copy(alpha = 0.55f), globalLightColor.copy(alpha = 0.08f), Color.Transparent), startY = horizonY, endY = lakeBottom),
                        topLeft = Offset(celestialX - 80f, horizonY),
                        size = Size(160f, lakeBottom - horizonY),
                        blendMode = BlendMode.Screen
                    )
                    for (i in 0 until 24) {
                        val progress = i / 24f
                        val y = horizonY + (lakeBottom - horizonY) * progress
                        val wave = sin(time * 2f + i * 0.8f) * (8f + progress * 30f)
                        val w = 30f + progress * 150f
                        drawLine(
                            globalLightColor.copy(alpha = (0.6f - progress * 0.5f).coerceAtLeast(0f)),
                            Offset(celestialX - w / 2 + wave, y), Offset(celestialX + w / 2 + wave, y),
                            strokeWidth = 2f + progress * 3f, cap = StrokeCap.Round, blendMode = BlendMode.Screen
                        )
                    }

                    drawImage(assets.fore!!, topLeft = Offset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = circadian)
                } else if (assets.base != null) {
                    // Single-image mode: your flat painting, lit and glittered
                    drawImage(assets.base!!, topLeft = Offset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = circadian)
                    drawRect(
                        Brush.verticalGradient(listOf(globalLightColor.copy(alpha = 0.45f), Color.Transparent), startY = horizonY, endY = lakeBottom),
                        topLeft = Offset(celestialX - 80f, horizonY), size = Size(160f, lakeBottom - horizonY), blendMode = BlendMode.Screen
                    )
                    for (i in 0 until 20) {
                        val progress = i / 20f
                        val y = horizonY + (lakeBottom - horizonY) * progress
                        val wave = sin(time * 2f + i) * (8f + progress * 25f)
                        val w = 30f + progress * 130f
                        drawLine(globalLightColor.copy(alpha = (0.5f - progress * 0.4f).coerceAtLeast(0f)), Offset(celestialX - w / 2 + wave, y), Offset(celestialX + w / 2 + wave, y), strokeWidth = 2f + progress * 3f, cap = StrokeCap.Round, blendMode = BlendMode.Screen)
                    }
                } else {
                    // Procedural fallback: the forged masterpiece until your art arrives
                    drawProceduralWorld(size, horizonY, lakeBottom, time, sunX, sunY, globalLightColor, isDay)
                }

                // Celestial aura + disc (code owns the light sources)
                drawCircle(Brush.radialGradient(listOf(globalLightColor.copy(alpha = 0.4f), Color.Transparent), radius = size.width * 0.5f), center = Offset(celestialX, celestialY), blendMode = BlendMode.Screen)
                if (isDay) drawCircle(Brush.radialGradient(listOf(Color.White, globalLightColor, Color.Transparent), radius = size.width * 0.05f), center = Offset(celestialX, celestialY))
                else drawCircle(Brush.radialGradient(listOf(globalLightColor, globalLightColor.copy(alpha = 0.4f), Color.Transparent), radius = size.width * 0.04f), center = Offset(celestialX, celestialY))

                // ---------- TECHNIQUE 4: THE FIRE'S CHIAROSCURO ----------
                // Warm light bleeding onto your painted grass and stones
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFFF6D3A).copy(alpha = 0.55f * flameScale), Color(0xFFE64A19).copy(alpha = 0.22f * flameScale), Color.Transparent), center = fireCenter, radius = 700f * flameScale),
                    blendMode = BlendMode.Screen
                )
                // Your hand-painted glow layer, pulsing with the fire
                assets.glow?.let {
                    drawImage(it, topLeft = Offset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = (0.85f * flameScale).coerceIn(0f, 1f), blendMode = BlendMode.Screen)
                }

                // Hearth stones (procedural mode only; in layered mode your painting owns them)
                if (!assets.hasLayers) {
                    for (i in 0 until 12) {
                        val a = i * (2f * PI.toFloat() / 12f)
                        draw3DStone(Offset(fireCenter.x + 140f * cos(a), fireCenter.y + 45f * sin(a)), 18f, fireCenter, Color(0xFFFF5722))
                    }
                }

                // Fire logs + flame + embers (always code-owned: they must burn and be dragged)
                draw3DLog(Offset(fireCenter.x - 70f, fireCenter.y + 10f), Offset(fireCenter.x + 70f, fireCenter.y - 5f), 22f, true, fireCenter, Color(0xFFFF5722))
                draw3DLog(Offset(fireCenter.x - 50f, fireCenter.y - 15f), Offset(fireCenter.x + 60f, fireCenter.y + 15f), 20f, true, fireCenter, Color(0xFFFF5722))
                if (logsBurning.value > 0) drawMasterpieceFlame(fireCenter, flameScale, time)
                viewModel.embers.forEach { e ->
                    drawCircle(if (e.life > 0.6f) Color(0xFFFFEB3B) else Color(0xFFFF5722), radius = e.size * e.life.coerceIn(0f, 1f), center = Offset(fireCenter.x + e.x, fireCenter.y + e.y), blendMode = BlendMode.Screen)
                }

                // Wood stack (draggable fuel)
                woodStack.forEachIndexed { index, pos ->
                    if (index != draggingLogIndex) draw3DLog(pos, Offset(pos.x + 100f, pos.y - 15f), 28f, false, fireCenter, Color(0xFFFF5722))
                }
                if (draggingLogIndex != -1) {
                    val p = woodStack[draggingLogIndex] + dragOffset
                    draw3DLog(p, Offset(p.x + 100f, p.y - 15f), 28f, false, fireCenter, Color(0xFFFF5722))
                }

                // Night glaze: Multiply sinks indigo into every shadow of your brushwork
                val nightIntensity = (1f - (effectiveElevation + 90f) / 180f).coerceIn(0f, 0.7f)
                if (nightIntensity > 0.01f) drawRect(Color(0xFF0A1030).copy(alpha = nightIntensity * 0.8f), blendMode = BlendMode.Multiply)
            } catch (e: Exception) { }
        }

        // ---------- UI OVERLAYS ----------
        val blackboxTrace = remember { context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).getString("last_crash", null) }
        var showBlackbox by remember { mutableStateOf(blackboxTrace != null) }
        if (showBlackbox && blackboxTrace != null) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 24.dp, end = 24.dp).fillMaxWidth().heightIn(max = 300.dp).background(Color(0xE6212121), RoundedCornerShape(16.dp)).padding(16.dp)) {
                Column {
                    Text("🕯️ The Black Box recovered a crash:", color = Color(0xFFFFCC80), fontSize = 13.sp)
                    Text(text = blackboxTrace, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                    Text("TAP HERE TO DISMISS", color = Color(0xFF80CBC4), fontSize = 11.sp, modifier = Modifier.clickable { context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).edit().remove("last_crash").apply(); showBlackbox = false })
                }
            }
        }
        Box(modifier = Modifier.align(Alignment.TopStart).padding(20.dp).size(36.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape).clickable { runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, contentAlignment = Alignment.Center) { Text("⚙", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp) }
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text("Preview Mode — your usual launcher is still in charge", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp) }
        LeatherSatchel(context, isDrawerOpen, Modifier.align(Alignment.BottomEnd))
    }
}

// ---------- WATER PHYSICS: strip displacement ----------
fun DrawScope.drawDisplacedWater(img: ImageBitmap, top: Float, bottom: Float, time: Float, size: Size, tint: ColorFilter) {
    val stripH = 6f
    var y = top
    while (y < bottom) {
        val progress = ((y - top) / (bottom - top)).coerceIn(0f, 1f)
        val amplitude = 1f + progress * 7f // Ripples grow toward the viewer: perspective
        val offset = sin(time * 1.8f + y * 0.12f) * amplitude
        val srcY = ((y / size.height) * img.height).toInt().coerceIn(0, img.height - 1)
        val srcH = max(1, ((stripH / size.height) * img.height).toInt()).coerceAtMost(img.height - srcY)
        if (srcH > 0) {
            drawImage(
                image = img,
                srcOffset = IntOffset(0, srcY),
                srcSize = IntSize(img.width, srcH),
                dstOffset = IntOffset(offset.roundToInt(), y.roundToInt()),
                dstSize = IntSize(size.width.roundToInt(), stripH.roundToInt()),
                colorFilter = tint
            )
        }
        y += stripH
    }
}

// ---------- PROCEDURAL FALLBACK WORLD ----------
fun DrawScope.drawProceduralWorld(size: Size, horizonY: Float, lakeBottom: Float, time: Float, sunX: Float, sunY: Float, light: Color, isDay: Boolean) {
    drawRect(Brush.verticalGradient(listOf(if (isDay) Color(0xFF4A708B) else Color(0xFF0B1021), if (isDay) Color(0xFF87CEEB) else Color(0xFF2A3B5C), if (isDay) Color(0xFFFFE082) else Color(0xFF8C6E5D))))
    val mtn = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, horizonY)
        var x = 0f
        while (x < size.width) { y0(horizonY, x); x += 20f }
        lineTo(size.width, horizonY); close()
    }
    drawPath(mtn, Color(0xFF12202C))
    drawRect(Color(0xFF0B1320), topLeft = Offset(0f, horizonY), size = Size(size.width, lakeBottom - horizonY))
    val hill = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, size.height); lineTo(0f, size.height * 0.85f)
        cubicTo(size.width * 0.2f, size.height * 0.75f, size.width * 0.35f, size.height * 0.82f, size.width * 0.5f, size.height * 0.82f)
        cubicTo(size.width * 0.65f, size.height * 0.82f, size.width * 0.8f, size.height * 0.90f, size.width, size.height * 0.85f)
        lineTo(size.width, size.height); close()
    }
    drawPath(hill, Brush.verticalGradient(listOf(Color(0xFF2E4028), Color(0xFF0A120B))))
}
private fun androidx.compose.ui.graphics.Path.y0(h: Float, x: Float) { lineTo(x, h - 60f - sin(x * 0.01f) * 50f) }

// ---------- 3D OBJECTS ----------
fun DrawScope.draw3DStone(center: Offset, radius: Float, fireCenter: Offset, fireLight: Color) {
    val d = sqrt((center.x - fireCenter.x) * (center.x - fireCenter.x) + (center.y - fireCenter.y) * (center.y - fireCenter.y))
    val li = (1f / (1f + d * 0.003f)).coerceIn(0f, 1f)
    drawOval(Color.Black.copy(alpha = 0.6f), topLeft = center + Offset(4f, 8f), size = Size(radius * 2.2f, radius * 1.4f))
    drawOval(Brush.radialGradient(listOf(Color(0xFF78909C), Color(0xFF37474F), Color(0xFF111111)), center = center + Offset(-radius * 0.3f, -radius * 0.3f), radius = radius * 1.5f), topLeft = center - Offset(radius, radius * 0.8f), size = Size(radius * 2f, radius * 1.6f))
    if (li > 0.1f) drawOval(fireLight.copy(alpha = 0.4f * li), topLeft = center - Offset(radius, radius * 0.8f), size = Size(radius * 2f, radius * 1.6f))
}

fun DrawScope.draw3DLog(start: Offset, end: Offset, thickness: Float, isCharred: Boolean, fireCenter: Offset, fireLight: Color) {
    val midX = (start.x + end.x) / 2f; val midY = (start.y + end.y) / 2f
    val d = sqrt((midX - fireCenter.x) * (midX - fireCenter.x) + (midY - fireCenter.y) * (midY - fireCenter.y))
    val li = (1f / (1f + d * 0.004f)).coerceIn(0f, 1f)
    drawLine(Color.Black.copy(alpha = 0.7f), start + Offset(5f, 12f), end + Offset(5f, 12f), strokeWidth = thickness, cap = StrokeCap.Round)
    val dark = if (isCharred) Color(0xFF1A110B) else Color(0xFF3E2723)
    val lite = if (isCharred) Color(0xFF4E342E) else Color(0xFF5D4037)
    drawLine(dark, start + Offset(0f, thickness / 4), end + Offset(0f, thickness / 4), strokeWidth = thickness / 2, cap = StrokeCap.Round)
    drawLine(lite, start, end, strokeWidth = thickness, cap = StrokeCap.Round)
    drawLine(lite.copy(alpha = 0.5f), start + Offset(0f, -thickness / 4), end + Offset(0f, -thickness / 4), strokeWidth = thickness / 3, cap = StrokeCap.Round)
    if (!isCharred) {
        val r = thickness / 2f
        drawOval(Color(0xFF8D6E63), topLeft = end - Offset(r, r), size = Size(r * 2, r * 2))
        drawOval(Color(0xFF5D4037), topLeft = end - Offset(r * 0.7f, r * 0.7f), size = Size(r * 1.4f, r * 1.4f))
        drawOval(Color(0xFF3E2723), topLeft = end - Offset(r * 0.4f, r * 0.4f), size = Size(r * 0.8f, r * 0.8f))
    } else drawCircle(fireLight.copy(alpha = 0.8f * li), radius = thickness / 3f, center = end)
    if (li > 0.1f) drawLine(fireLight.copy(alpha = 0.3f * li), start, end, strokeWidth = thickness, cap = StrokeCap.Round)
}

fun DrawScope.drawMasterpieceFlame(center: Offset, scale: Float, time: Float) {
    val f1 = sin(time * 12f) * 15f * scale
    val f2 = cos(time * 8f) * 10f * scale
    val outer = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 80f * scale, center.y - 60f * scale, center.x - 40f * scale + f1, center.y - 250f * scale, center.x, center.y - 280f * scale)
        cubicTo(center.x + 40f * scale - f1, center.y - 250f * scale, center.x + 80f * scale, center.y - 60f * scale, center.x, center.y)
        close()
    }
    drawPath(outer, Brush.radialGradient(listOf(Color(0xFFFF5722).copy(alpha = 0.8f), Color(0xFFFF5722).copy(alpha = 0.2f), Color.Transparent), center = center, radius = 300f * scale))
    val mid = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 50f * scale, center.y - 40f * scale, center.x - 20f * scale + f2, center.y - 180f * scale, center.x, center.y - 200f * scale)
        cubicTo(center.x + 20f * scale - f2, center.y - 180f * scale, center.x + 50f * scale, center.y - 40f * scale, center.x, center.y)
        close()
    }
    drawPath(mid, Color(0xFFFFC107).copy(alpha = 0.9f))
    val core = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 25f * scale, center.y - 20f * scale, center.x - 10f * scale + f1 * 0.5f, center.y - 100f * scale, center.x, center.y - 120f * scale)
        cubicTo(center.x + 10f * scale - f1 * 0.5f, center.y - 100f * scale, center.x + 25f * scale, center.y - 20f * scale, center.x, center.y)
        close()
    }
    drawPath(core, Color.White)
}

fun getCircadianMatrix(elevation: Float): FloatArray {
    val t = (elevation / 90f).coerceIn(-1f, 1f)
    val night = floatArrayOf(0.35f, 0f, 0.1f, 0f, 0f, 0f, 0.25f, 0.3f, 0f, 0f, 0.1f, 0.1f, 0.85f, 0f, 25f, 0f, 0f, 0f, 1f, 0f) // Prussian + Ultramarine
    val dusk = floatArrayOf(1.2f, 0.2f, 0f, 0f, 20f, 0.2f, 0.9f, 0.1f, 0f, 10f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f) // Madder + Gamboge
    val day = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0.1f, 0f, 10f, 0f, 0.1f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f) // Crisp Cyan
    return if (t < 0) lerpM(night, dusk, t + 1f) else lerpM(dusk, day, t)
}
fun lerpM(a: FloatArray, b: FloatArray, t: Float) = FloatArray(20) { a[it] * (1 - t) + b[it] * t }

// ---------- SATCHEL & AUDIO (unchanged, proven) ----------
@Composable
fun LeatherSatchel(context: Context, isExpanded: MutableState<Boolean>, modifier: Modifier = Modifier) {
    val height by animateFloatAsState(targetValue = if (isExpanded.value) 600f else 80f, animationSpec = tween(600, easing = FastOutSlowInEasing), label = "SatchelHeight")
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(isExpanded.value) { if (isExpanded.value && apps.isEmpty()) apps = withContext(Dispatchers.Default) { runCatching { getInstalledApps(context) }.getOrElse { emptyList() } } }
    Box(modifier = modifier.padding(24.dp).width(300.dp).height(height.dp).then(if (isExpanded.value) Modifier.blur(40.dp) else Modifier).background(if (isExpanded.value) Color(0x99000000) else Color(0xFF4E342E), shape = RoundedCornerShape(24.dp)).clickable { isExpanded.value = !isExpanded.value }) {
        if (!isExpanded.value) Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(20.dp).background(Color.White, CircleShape))
        else LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(apps) { app ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) } } }) {
                    Image(bitmap = app.icon.asImageBitmap(), contentDescription = app.name, modifier = Modifier.size(48.dp))
                    Text(text = app.name, color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

data class AppInfo(val name: String, val icon: Bitmap, val packageName: String)
fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
        runCatching {
            val d = ri.activityInfo.loadIcon(pm)
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 108
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 108
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); d.setBounds(0, 0, c.width, c.height); d.draw(c)
            AppInfo(ri.loadLabel(pm).toString(), bmp, ri.activityInfo.packageName)
        }.getOrNull()
    }.take(30)
}

class CozyAudioEngine {
    private var ambienceTrack: AudioTrack? = null; private var fireTrack: AudioTrack? = null; private var isPlaying = false; private var isPaused = false; private var fireIntensity = 0f; private var ambienceThread: Thread? = null; private var fireThread: Thread? = null
    fun start() { if (isPlaying) return; isPlaying = true; isPaused = false
        ambienceThread = Thread { try { val sr = 16000; var bs = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT); if (bs <= 0) bs = 4096; ambienceTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM).build(); ambienceTrack?.play(); val buf = ShortArray(bs / 2); var lo = 0.0; while (isPlaying) { if (!isPaused) { for (i in buf.indices) { val w = Math.random() * 2 - 1; lo = (lo + 0.02 * w) / 1.02; buf[i] = (lo * 32767 * 0.15).toInt().toShort() }; ambienceTrack?.write(buf, 0, buf.size) } else Thread.sleep(100) } } catch (e: Exception) { } }; ambienceThread?.start()
        fireThread = Thread { try { val sr = 22050; var bs = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT); if (bs <= 0) bs = 4096; fireTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM).build(); fireTrack?.play(); val buf = ShortArray(bs / 2); while (isPlaying) { if (!isPaused && fireIntensity > 0.05f) { for (i in buf.indices) { val c = if (Math.random() < 0.005 * fireIntensity) (Math.random() * 2 - 1) * 32767 * fireIntensity else 0.0; buf[i] = c.toInt().toShort() }; fireTrack?.write(buf, 0, buf.size) } else Thread.sleep(100) } } catch (e: Exception) { } }; fireThread?.start() }
    fun updateFireIntensity(i: Float) { fireIntensity = i.coerceIn(0f, 1f) }
    fun playThump() { Thread { try { val sr = 44100; val n = (sr * 0.2).toInt(); val b = ShortArray(n); for (i in 0 until n) { val t = i.toDouble() / sr; val f = 60 - t * 100; val w = sin(2 * PI * f * t) * (1 - t / 0.2); val nz = (Math.random() * 2 - 1) * (1 - t / 0.2) * 0.5; b[i] = ((w + nz) * 32767 * 0.5).toInt().toShort() }; val tr = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(b.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build(); tr.write(b, 0, b.size); tr.play(); Thread.sleep(200); tr.release() } catch (e: Exception) { } }.start() }
    fun pause() { isPaused = true; runCatching { ambienceTrack?.pause() }; runCatching { fireTrack?.pause() } }
    fun resume() { isPaused = false; runCatching { ambienceTrack?.play() }; runCatching { fireTrack?.play() } }
    fun stop() { isPlaying = false; ambienceThread?.interrupt(); fireThread?.interrupt(); runCatching { ambienceTrack?.release() }; runCatching { fireTrack?.release() } }
}
