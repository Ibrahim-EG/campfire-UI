package com.campfire.canvas.livingpainting

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "campfire_crash_${System.currentTimeMillis()}.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CampfireCanvas")
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(trace.toByteArray()) } }
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
    val scope = rememberCoroutineScope()

    val targetElevation by viewModel.sunElevation
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
        val fireX = size.width * 0.5f
        val fireY = size.height * 0.82f 
        val fireCenter = Offset(fireX, fireY)

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

        // HEAVYWEIGHT OPTIMIZATION: Generate the massive, highly-detailed static background ONCE on a background thread.
        // This includes the sky, stars, mountains, lake base, and the high hill with impasto grass.
        var staticMasterpiece by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(size) {
            staticMasterpiece = withContext(Dispatchers.Default) {
                runCatching { generateMasterpieceBackground(size, horizonY, lakeBottom) }.getOrNull()
            }
        }

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
                
                // Dynamic Global Light Color (Oil Painting Palette)
                val globalLightColor = when {
                    effectiveElevation > 30 -> Color(0xFFFFD54F) // Crisp Day Gold
                    effectiveElevation > 0 -> Color(0xFFFF7043)  // Dusk Madder Lake / Gamboge
                    effectiveElevation > -30 -> Color(0xFFBA68C8) // Twilight Mauve
                    else -> Color(0xFF90A4AE)                    // Night Cool Silver
                }

                // 1. DRAW STATIC MASTERPIECE (Cached)
                val bg = staticMasterpiece
                if (bg != null) {
                    drawImage(bg, topLeft = Offset.Zero)
                } else {
                    drawRect(Color(0xFF0B1021)) // Fallback while loading
                }

                // 2. CELESTIAL BODIES & AURA
                drawCircle(Brush.radialGradient(listOf(globalLightColor.copy(alpha = 0.4f), Color.Transparent), radius = size.width * 0.6f), center = Offset(sunX, sunY))
                if (isDay) {
                    drawCircle(Brush.radialGradient(listOf(Color.White, globalLightColor, Color.Transparent), radius = size.width * 0.05f), center = Offset(sunX, sunY))
                } else {
                    val moonY = horizonY - (size.height * 0.35f * sin(sunAngle + PI.toFloat()))
                    drawCircle(Brush.radialGradient(listOf(globalLightColor, globalLightColor.copy(alpha = 0.4f), Color.Transparent), radius = size.width * 0.04f), center = Offset(sunX, moonY))
                }

                // 3. LAKE REFLECTIONS (Reactive to Sun/Moon)
                val reflectWidth = 160f
                drawRect(
                    Brush.verticalGradient(listOf(globalLightColor.copy(alpha = 0.6f), globalLightColor.copy(alpha = 0.1f), Color.Transparent), startY = horizonY, endY = lakeBottom), 
                    topLeft = Offset(sunX - reflectWidth/2, horizonY), 
                    size = Size(reflectWidth, lakeBottom - horizonY)
                )
                
                // Specular Ripples (Perspective corrected: wider at the bottom)
                for (i in 0 until 30) {
                    val progress = i / 30f
                    val y = horizonY + (lakeBottom - horizonY) * progress
                    val wave = sin(time * 2f + i * 0.8f) * (10f + progress * 30f)
                    val rippleWidth = 40f + progress * 180f
                    val alpha = (0.7f - progress * 0.6f).coerceAtLeast(0f)
                    drawLine(
                        globalLightColor.copy(alpha = alpha), 
                        start = Offset(sunX - rippleWidth/2 + wave, y), 
                        end = Offset(sunX + rippleWidth/2 + wave, y), 
                        strokeWidth = 2f + progress * 4f, 
                        cap = StrokeCap.Round
                    )
                }

                // 4. CHIAROSCURO: THE FIRE GLOW (Color Bleeding)
                // This massive radial gradient physically tints the grass, stones, and wood with warm Burnt Sienna.
                val fireLightColor = Color(0xFFFF5722) // Deep Orange/Red
                val glowRadius = 800f * flameScale
                drawCircle(
                    Brush.radialGradient(
                        colors = listOf(
                            fireLightColor.copy(alpha = 0.5f * flameScale), 
                            Color(0xFFE64A19).copy(alpha = 0.2f * flameScale), 
                            Color.Transparent
                        ),
                        center = fireCenter,
                        radius = glowRadius
                    )
                )

                // 5. 3D STONES (Ring around the fire)
                for (i in 0 until 12) {
                    val angle = i * (2f * PI.toFloat() / 12f)
                    val stoneX = fireCenter.x + 140f * cos(angle)
                    val stoneY = fireCenter.y + 45f * sin(angle)
                    draw3DStone(Offset(stoneX, stoneY), 18f, fireCenter, fireLightColor)
                }

                // 6. 3D WOOD STACK & FIRE LOGS
                // Base charred logs in the fire
                draw3DLog(Offset(fireCenter.x - 70f, fireCenter.y + 10f), Offset(fireCenter.x + 70f, fireCenter.y - 5f), 22f, true, fireCenter, fireLightColor)
                draw3DLog(Offset(fireCenter.x - 50f, fireCenter.y - 15f), Offset(fireCenter.x + 60f, fireCenter.y + 15f), 20f, true, fireCenter, fireLightColor)

                // The Flame (Morphing Beziers)
                if (logsBurning.value > 0) {
                    drawMasterpieceFlame(fireCenter, flameScale, time)
                }

                // Wood Stack on the Hill
                woodStack.forEachIndexed { index, pos ->
                    if (index != draggingLogIndex) draw3DLog(pos, Offset(pos.x + 100f, pos.y - 15f), 28f, false, fireCenter, fireLightColor)
                }
                if (draggingLogIndex != -1) {
                    val p = woodStack[draggingLogIndex] + dragOffset
                    draw3DLog(p, Offset(p.x + 100f, p.y - 15f), 28f, false, fireCenter, fireLightColor)
                }

                // 7. EMBER PARTICLE SYSTEM
                viewModel.embers.forEach { ember ->
                    val alpha = ember.life.coerceIn(0f, 1f)
                    val emberColor = if (ember.life > 0.6f) Color(0xFFFFFF00) else Color(0xFFFF5722)
                    drawCircle(
                        emberColor.copy(alpha = alpha),
                        radius = ember.size * ember.life,
                        center = Offset(fireCenter.x + ember.x, fireCenter.y + ember.y)
                    )
                }

                // 8. GLOBAL CIRCADIAN WASH (Night Darkening)
                val nightIntensity = (1f - (effectiveElevation + 90f) / 180f).coerceIn(0f, 0.7f)
                drawRect(color = Color(0xFF02040A).copy(alpha = nightIntensity))

            } catch (e: Exception) { 
                // Swallow draw exceptions to prevent launcher death
            }
        }

        // UI OVERLAYS
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

// ==========================================
// THE HEAVYWEIGHT IMPASTO & 3D RENDERING ENGINE
// ==========================================

fun DrawScope.draw3DStone(center: Offset, radius: Float, fireCenter: Offset, fireLight: Color) {
    // Calculate light direction from fire
    val dx = center.x - fireCenter.x
    val dy = center.y - fireCenter.y
    val dist = sqrt(dx * dx + dy * dy)
    val lightInfluence = (1f / (1f + dist * 0.003f)).coerceIn(0f, 1f)
    
    // Ambient Occlusion Shadow
    drawOval(Color.Black.copy(alpha = 0.6f), topLeft = center + Offset(4f, 8f), size = Size(radius * 2.2f, radius * 1.4f))
    
    // Base Stone with Radial Gradient for 3D volume
    val baseColor = Color(0xFF37474F) // Blue-grey slate
    val highlightColor = Color(0xFF78909C)
    drawOval(
        Brush.radialGradient(
            colors = listOf(highlightColor, baseColor, Color(0xFF111111)),
            center = center + Offset(-radius * 0.3f, -radius * 0.3f),
            radius = radius * 1.5f
        ),
        topLeft = center - Offset(radius, radius * 0.8f),
        size = Size(radius * 2f, radius * 1.6f)
    )
    
    // Fire Light Bleed (Chiaroscuro)
    if (lightInfluence > 0.1f) {
        drawOval(
            fireLight.copy(alpha = 0.4f * lightInfluence),
            topLeft = center - Offset(radius, radius * 0.8f),
            size = Size(radius * 2f, radius * 1.6f)
        )
    }
}

fun DrawScope.draw3DLog(start: Offset, end: Offset, thickness: Float, isCharred: Boolean, fireCenter: Offset, fireLight: Color) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = sqrt(dx * dx + dy * dy)
    val angle = kotlin.math.atan2(dy, dx)
    
    // Calculate fire influence for color bleeding
    val midX = (start.x + end.x) / 2f
    val midY = (start.y + end.y) / 2f
    val distToFire = sqrt((midX - fireCenter.x) * (midX - fireCenter.x) + (midY - fireCenter.y) * (midY - fireCenter.y))
    val fireInfluence = (1f / (1f + distToFire * 0.004f)).coerceIn(0f, 1f)

    // 1. Drop Shadow
    drawLine(Color.Black.copy(alpha = 0.7f), start + Offset(5f, 12f), end + Offset(5f, 12f), strokeWidth = thickness, cap = StrokeCap.Round)
    
    // 2. Bark Base (Linear Gradient for cylindrical 3D shading)
    val barkDark = if (isCharred) Color(0xFF1A110B) else Color(0xFF3E2723)
    val barkLight = if (isCharred) Color(0xFF4E342E) else Color(0xFF5D4037)
    
    // We simulate a linear gradient across the cylinder by drawing 3 overlapping lines
    drawLine(barkDark, start + Offset(0f, thickness/4), end + Offset(0f, thickness/4), strokeWidth = thickness/2, cap = StrokeCap.Round) // Shadow side
    drawLine(barkLight, start, end, strokeWidth = thickness, cap = StrokeCap.Round) // Midtone
    drawLine(barkLight.copy(alpha = 0.5f), start + Offset(0f, -thickness/4), end + Offset(0f, -thickness/4), strokeWidth = thickness/3, cap = StrokeCap.Round) // Highlight side

    // 3. Bark Texture (Jagged lines)
    val barkTexture = if (isCharred) Color(0xFF000000) else Color(0xFF2D1A11)
    for (i in 0 until 3) {
        val offset = (i - 1) * (thickness / 4)
        val perpX = -sin(angle) * offset
        val perpY = cos(angle) * offset
        drawLine(barkTexture.copy(alpha = 0.6f), start + Offset(perpX, perpY), end + Offset(perpX, perpY), strokeWidth = 2f, cap = StrokeCap.Round)
    }

    // 4. End Cap (Wood Grain)
    if (!isCharred) {
        val capCenter = end
        val capRadius = thickness / 2f
        drawOval(Color(0xFF8D6E63), topLeft = capCenter - Offset(capRadius, capRadius), size = Size(capRadius * 2, capRadius * 2))
        drawOval(Color(0xFF5D4037), topLeft = capCenter - Offset(capRadius * 0.7f, capRadius * 0.7f), size = Size(capRadius * 1.4f, capRadius * 1.4f))
        drawOval(Color(0xFF3E2723), topLeft = capCenter - Offset(capRadius * 0.4f, capRadius * 0.4f), size = Size(capRadius * 0.8f, capRadius * 0.8f))
    } else {
        // Glowing embers on charred ends
        drawCircle(fireLight.copy(alpha = 0.8f * fireInfluence), radius = thickness / 3f, center = end)
    }

    // 5. Fire Light Bleed over the entire log
    if (fireInfluence > 0.1f) {
        drawLine(fireLight.copy(alpha = 0.3f * fireInfluence), start, end, strokeWidth = thickness, cap = StrokeCap.Round)
    }
}

fun DrawScope.drawMasterpieceFlame(center: Offset, scale: Float, time: Float) {
    val flicker1 = sin(time * 12f) * 15f * scale
    val flicker2 = cos(time * 8f) * 10f * scale
    
    // Outer Aura (Deep Orange)
    val outerPath = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 80f * scale, center.y - 60f * scale, center.x - 40f * scale + flicker1, center.y - 250f * scale, center.x, center.y - 280f * scale)
        cubicTo(center.x + 40f * scale - flicker1, center.y - 250f * scale, center.x + 80f * scale, center.y - 60f * scale, center.x, center.y)
        close()
    }
    drawPath(outerPath, Brush.radialGradient(listOf(Color(0xFFFF5722).copy(alpha = 0.8f), Color(0xFFFF5722).copy(alpha = 0.2f), Color.Transparent), center = center, radius = 300f * scale))

    // Mid Body (Gamboge Yellow)
    val midPath = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 50f * scale, center.y - 40f * scale, center.x - 20f * scale + flicker2, center.y - 180f * scale, center.x, center.y - 200f * scale)
        cubicTo(center.x + 20f * scale - flicker2, center.y - 180f * scale, center.x + 50f * scale, center.y - 40f * scale, center.x, center.y)
        close()
    }
    drawPath(midPath, Color(0xFFFFC107).copy(alpha = 0.9f))

    // Inner Core (Pure White)
    val corePath = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - 25f * scale, center.y - 20f * scale, center.x - 10f * scale + flicker1 * 0.5f, center.y - 100f * scale, center.x, center.y - 120f * scale)
        cubicTo(center.x + 10f * scale - flicker1 * 0.5f, center.y - 100f * scale, center.x + 25f * scale, center.y - 20f * scale, center.x, center.y)
        close()
    }
    drawPath(corePath, Color.White)
}

// ==========================================
// THE STATIC MASTERPIECE GENERATOR (Heavyweight)
// ==========================================

fun generateMasterpieceBackground(size: Size, horizonY: Float, lakeBottom: Float): ImageBitmap {
    // Cap resolution to prevent OOM on low-RAM tablets, while maintaining visual density
    val maxDim = 2000 
    val scale = minOf(maxDim.toFloat() / size.width, maxDim.toFloat() / size.height).coerceAtMost(1f)
    val w = (size.width * scale).toInt().coerceAtLeast(1)
    val h = (size.height * scale).toInt().coerceAtLeast(1)
    
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = true }
    
    val sHorizon = horizonY * scale
    val sLakeBottom = lakeBottom * scale
    val sWidth = w.toFloat()
    val sHeight = h.toFloat()

    // 1. SKY (Prussian Blue to Ultramarine)
    val skyShader = android.graphics.LinearGradient(0f, 0f, 0f, sHorizon, intArrayOf(0xFF0B1021.toInt(), 0xFF2A3B5C.toInt(), 0xFF8C6E5D.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    paint.shader = skyShader
    canvas.drawRect(0f, 0f, sWidth, sHorizon, paint)
    
    // Stars (Subtle)
    paint.shader = null
    paint.color = android.graphics.Color.WHITE
    for (i in 0 until 200) {
        val x = (Math.random() * sWidth).toFloat()
        val y = (Math.random() * sHorizon * 0.8).toFloat()
        val r = (Math.random() * 1.5).toFloat()
        paint.alpha = (Math.random() * 150 + 50).toInt()
        canvas.drawCircle(x, y, r, paint)
    }

    // 2. MOUNTAINS (Deterministic Geological Noise)
    val mtnColors = intArrayOf(0xFF1A2530.toInt(), 0xFF111820.toInt(), 0xFF0A1118.toInt())
    for (layer in 0 until 3) {
        val path = Path()
        path.moveTo(0f, sHorizon)
        val baseHeight = sHorizon - (50 + layer * 40) * scale
        val freq = 0.005 + layer * 0.002
        for (x in 0..w step 10) {
            val y = baseHeight + (sin(x * freq) * 30 + sin(x * freq * 2.5) * 15 + sin(x * freq * 0.5) * 40) * scale
            path.lineTo(x.toFloat(), y.toFloat())
        }
        path.lineTo(sWidth, sHorizon)
        path.close()
        paint.color = mtnColors[layer]
        canvas.drawPath(path, paint)
    }

    // 3. LAKE BASE (Deep Cerulean with Impasto Strokes)
    paint.color = 0xFF0B1320.toInt()
    canvas.drawRect(0f, sHorizon, sWidth, sLakeBottom, paint)
    
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f * scale
    for (y in (sHorizon.toInt() + 10)..sLakeBottom.toInt() step 8) {
        val progress = (y - sHorizon) / (sLakeBottom - sHorizon)
        paint.color = android.graphics.Color.argb((40 + progress * 60).toInt(), 20, 40, 60)
        var x = 0f
        while (x < sWidth) {
            val len = (Math.random() * 60 + 20).toFloat() * scale
            canvas.drawLine(x, y.toFloat(), x + len, y.toFloat(), paint)
            x += len + 15 * scale
        }
    }

    // 4. THE HIGH HILL (Sweeping Bezier with Sap-Green Impasto Grass)
    val hillPath = Path()
    hillPath.moveTo(0f, sHeight)
    hillPath.lineTo(0f, sHeight * 0.85f)
    hillPath.cubicTo(sWidth * 0.2f, sHeight * 0.75f, sWidth * 0.35f, sHeight * 0.82f, sWidth * 0.5f, sHeight * 0.82f)
    hillPath.cubicTo(sWidth * 0.65f, sHeight * 0.82f, sWidth * 0.8f, sHeight * 0.90f, sWidth, sHeight * 0.85f)
    hillPath.lineTo(sWidth, sHeight)
    hillPath.close()
    
    paint.style = Paint.Style.FILL
    val hillShader = android.graphics.LinearGradient(0f, sHeight * 0.75f, 0f, sHeight, intArrayOf(0xFF2E4028.toInt(), 0xFF0A120B.toInt()), null, Shader.TileMode.CLAMP)
    paint.shader = hillShader
    canvas.drawPath(hillPath, paint)
    
    // Grass Impasto Strokes (Thousands of detailed strokes)
    paint.shader = null
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    for (i in 0 until 3000) {
        val x = (Math.random() * sWidth).toFloat()
        // Calculate Y based on the hill curve roughly
        val baseY = sHeight * 0.82f + (Math.random() * (sHeight - sHeight * 0.82f)).toFloat()
        val strokeLen = (Math.random() * 15 + 5).toFloat() * scale
        val shade = Math.random()
        paint.color = if (shade > 0.5) 0xFF33691E.toInt() else 0xFF1B5E20.toInt()
        paint.alpha = (150 + Math.random() * 100).toInt()
        paint.strokeWidth = (Math.random() * 3 + 1).toFloat() * scale
        canvas.drawLine(x, baseY, x + (Math.random() * 10 - 5) * scale, baseY - strokeLen, paint)
    }

    return bitmap.asImageBitmap()
}

// ==========================================
// UI & AUDIO (Preserved & Optimized)
// ==========================================

@Composable
fun LeatherSatchel(context: Context, isExpanded: MutableState<Boolean>, modifier: Modifier = Modifier) {
    val height by animateFloatAsState(targetValue = if (isExpanded.value) 600f else 80f, animationSpec = tween(600, easing = FastOutSlowInEasing), label = "SatchelHeight")
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(isExpanded.value) { if (isExpanded.value && apps.isEmpty()) apps = withContext(Dispatchers.Default) { runCatching { getInstalledApps(context) }.getOrElse { emptyList() } } }
    Box(modifier = modifier.padding(24.dp).width(300.dp).height(height.dp).then(if (isExpanded.value) Modifier.blur(40.dp) else Modifier).background(if (isExpanded.value) Color(0x99000000) else Color(0xFF4E342E), shape = RoundedCornerShape(24.dp)).clickable { isExpanded.value = !isExpanded.value }) {
        if (!isExpanded.value) Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(20.dp).background(Color.White, CircleShape))
        else {
            LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(apps) { app ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) } } }) {
                        Image(bitmap = app.icon.asImageBitmap(), contentDescription = app.name, modifier = Modifier.size(48.dp))
                        Text(text = app.name, color = Color.White, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

data class AppInfo(val name: String, val icon: Bitmap, val packageName: String)
fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).mapNotNull { resolveInfo ->
        runCatching {
            val drawable = resolveInfo.activityInfo.loadIcon(pm)
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 108
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 108
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp); drawable.setBounds(0, 0, canvas.width, canvas.height); drawable.draw(canvas)
            AppInfo(name = resolveInfo.loadLabel(pm).toString(), icon = bmp, packageName = resolveInfo.activityInfo.packageName)
        }.getOrNull()
    }.take(30)
}

class CozyAudioEngine {
    private var ambienceTrack: AudioTrack? = null; private var fireTrack: AudioTrack? = null; private var isPlaying = false; private var isPaused = false; private var fireIntensity = 0f; private var ambienceThread: Thread? = null; private var fireThread: Thread? = null
    fun start() { if (isPlaying) return; isPlaying = true; isPaused = false; ambienceThread = Thread { try { val sampleRate = 16000; var bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT); if (bufferSize <= 0) bufferSize = 4096; ambienceTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build(); ambienceTrack?.play(); val buffer = ShortArray(bufferSize / 2); var lastOut = 0.0; while (isPlaying) { if (!isPaused) { for (i in buffer.indices) { val white = Math.random() * 2 - 1; lastOut = (lastOut + (0.02 * white)) / 1.02; buffer[i] = (lastOut * 32767 * 0.15).toInt().toShort() }; ambienceTrack?.write(buffer, 0, buffer.size) } else Thread.sleep(100) } } catch (e: Exception) { } }; ambienceThread?.start(); fireThread = Thread { try { val sampleRate = 22050; var bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT); if (bufferSize <= 0) bufferSize = 4096; fireTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build(); fireTrack?.play(); val buffer = ShortArray(bufferSize / 2); while (isPlaying) { if (!isPaused && fireIntensity > 0.05f) { for (i in buffer.indices) { val crackle = if (Math.random() < 0.005 * fireIntensity) (Math.random() * 2 - 1) * 32767 * fireIntensity else 0.0; buffer[i] = crackle.toInt().toShort() }; fireTrack?.write(buffer, 0, buffer.size) } else Thread.sleep(100) } } catch (e: Exception) { } }; fireThread?.start() }
    fun updateFireIntensity(intensity: Float) { fireIntensity = intensity.coerceIn(0f, 1f) }
    fun playThump() { Thread { try { val sampleRate = 44100; val numSamples = (sampleRate * 0.2).toInt(); val buffer = ShortArray(numSamples); for (i in 0 until numSamples) { val t = i.toDouble() / sampleRate; val freq = 60 - (t * 100); val wave = sin(2 * PI * freq * t) * (1 - t / 0.2); val noise = (Math.random() * 2 - 1) * (1 - t / 0.2) * 0.5; buffer[i] = ((wave + noise) * 32767 * 0.5).toInt().toShort() }; val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(buffer.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build(); track.write(buffer, 0, buffer.size); track.play(); Thread.sleep(200); track.release() } catch (e: Exception) { } }.start() }
    fun pause() { isPaused = true; runCatching { ambienceTrack?.pause() }; runCatching { fireTrack?.pause() } }
    fun resume() { isPaused = false; runCatching { ambienceTrack?.play() }; runCatching { fireTrack?.play() } }
    fun stop() { isPlaying = false; ambienceThread?.interrupt(); fireThread?.interrupt(); runCatching { ambienceTrack?.release() }; runCatching { fireTrack?.release() } }
}
