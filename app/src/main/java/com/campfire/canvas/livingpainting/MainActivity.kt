package com.campfire.canvas.livingpainting

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel: PaintingViewModel by viewModels()
    private val audioEngine = CozyAudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashBlackBox() 
        super.onCreate(savedInstanceState)
        
        // Request Location for true astronomical solar tracking
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

    override fun onPause() {
        super.onPause()
        audioEngine.pause()
        viewModel.isPaused.value = true 
    }

    override fun onResume() {
        super.onResume()
        audioEngine.resume()
        viewModel.isPaused.value = false
    }
}

@Composable
fun CampfireCanvasApp(viewModel: PaintingViewModel, audioEngine: CozyAudioEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val targetElevation by viewModel.sunElevation
    // 90-second cubic ease-in-out for the sunset bleed
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
        
        // Composition Layout (Wide View Optimized)
        val horizonY = size.height * 0.55f
        val lakeBottom = size.height * 0.75f
        val fireX = size.width * 0.5f
        val fireY = size.height * 0.82f 
        val fireCenter = Offset(fireX, fireY)

        val woodStack = remember {
            mutableStateListOf(
                Offset(size.width * 0.15f, size.height * 0.92f),
                Offset(size.width * 0.22f, size.height * 0.94f),
                Offset(size.width * 0.29f, size.height * 0.91f)
            )
        }
        var draggingLogIndex by remember { mutableStateOf(-1) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        val isDrawerOpen = remember { mutableStateOf(false) }
        val logsBurning = viewModel.logsBurning
        val flameScale = (logsBurning.value / 5f).coerceAtLeast(0.1f) // Base embers always glow

        LaunchedEffect(logsBurning.value) {
            audioEngine.updateFireIntensity(logsBurning.value / 5f)
        }

        // Time accumulator for water ripples and fire flickering
        val time = viewModel.cinematicTime.value / 1_000_000_000f

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            woodStack.forEachIndexed { index, pos ->
                                if (offset.x > pos.x && offset.x < pos.x + 80 && offset.y > pos.y && offset.y < pos.y + 30) {
                                    draggingLogIndex = index
                                    dragOffset = Offset.Zero
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (draggingLogIndex != -1) {
                                dragOffset += dragAmount
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (draggingLogIndex != -1) {
                                val dropPos = woodStack[draggingLogIndex] + dragOffset
                                val distance = kotlin.math.hypot(dropPos.x - fireCenter.x, dropPos.y - fireCenter.y)
                                if (distance < 150f && logsBurning.value < 5) {
                                    logsBurning.value++
                                    audioEngine.playThump()
                                }
                            }
                            draggingLogIndex = -1
                            dragOffset = Offset.Zero
                        }
                    )
                }
        ) {
            try {
                // ==========================================
                // THE MASTERPIECE ENGINE
                // ==========================================

                val isDay = effectiveElevation > 0
                val sunAngle = (effectiveElevation / 90f) * Math.PI
                val sunX = size.width * (0.5f + 0.4f * cos(sunAngle)).toFloat()
                val sunY = horizonY - (size.height * 0.4f * sin(sunAngle)).toFloat()
                
                // Dynamic Light Color: Shifts from Cool Silver (Night) to Madder Lake (Dusk) to Warm Gold (Day)
                val lightColor = when {
                    effectiveElevation > 30 -> Color(0xFFFFD54F) // Crisp Day Gold
                    effectiveElevation > 0 -> Color(0xFFFF7043)  // Dusk Madder Lake / Gamboge
                    effectiveElevation > -30 -> Color(0xFFBA68C8) // Twilight Mauve
                    else -> Color(0xFFB0BEC5)                    // Night Cool Silver
                }

                // --- LAYER 1: THE CHROMATIC SKYDOME ---
                val skyTop = if (isDay) Color(0xFF4A708B) else Color(0xFF0B1021)
                val skyMid = if (isDay) Color(0xFF87CEEB) else Color(0xFF2A3B5C)
                val skyBottom = if (isDay) Color(0xFFFFE082) else Color(0xFF8C6E5D)
                
                drawRect(brush = Brush.verticalGradient(listOf(skyTop, skyMid, skyBottom)))

                // Sun/Moon Aura (Color Bleeding into the sky)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(lightColor.copy(alpha = 0.4f), Color.Transparent),
                        radius = size.width * 0.5f
                    ),
                    center = Offset(sunX, sunY)
                )

                // Celestial Body
                if (isDay) {
                    drawCircle(Brush.radialGradient(listOf(Color.White, lightColor, Color.Transparent), radius = size.width * 0.06f), center = Offset(sunX, sunY))
                } else {
                    val moonY = horizonY - (size.height * 0.3f * sin(sunAngle + PI)).toFloat()
                    drawCircle(Brush.radialGradient(listOf(lightColor, lightColor.copy(alpha = 0.5f), Color.Transparent), radius = size.width * 0.04f), center = Offset(sunX, moonY))
                }

                // --- LAYER 2: ATMOSPHERIC MOUNTAINS (Color Bleeding) ---
                // Back Layer: Bleeds heavily into the sky (low contrast, cool tint)
                val backMountain = Path().apply {
                    moveTo(0f, horizonY)
                    cubicTo(size.width * 0.2f, horizonY - 120f, size.width * 0.4f, horizonY - 180f, size.width * 0.6f, horizonY - 90f)
                    cubicTo(size.width * 0.8f, horizonY - 20f, size.width * 0.9f, horizonY - 140f, size.width, horizonY - 60f)
                    lineTo(size.width, horizonY); close()
                }
                drawPath(backMountain, skyMid.copy(alpha = 0.6f)) // Bleeds into sky

                // Mid Layer: Mid contrast
                val midMountain = Path().apply {
                    moveTo(0f, horizonY)
                    cubicTo(size.width * 0.15f, horizonY - 80f, size.width * 0.35f, horizonY - 140f, size.width * 0.5f, horizonY - 60f)
                    cubicTo(size.width * 0.75f, horizonY - 10f, size.width * 0.85f, horizonY - 100f, size.width, horizonY - 30f)
                    lineTo(size.width, horizonY); close()
                }
                drawPath(midMountain, Color(0xFF1A2530).copy(alpha = 0.8f))

                // Front Layer: High contrast, dark silhouette
                val frontMountain = Path().apply {
                    moveTo(0f, horizonY)
                    cubicTo(size.width * 0.25f, horizonY - 50f, size.width * 0.45f, horizonY - 90f, size.width * 0.65f, horizonY - 40f)
                    cubicTo(size.width * 0.8f, horizonY - 10f, size.width * 0.95f, horizonY - 60f, size.width, horizonY)
                    lineTo(size.width, horizonY); close()
                }
                drawPath(frontMountain, Color(0xFF0A1118))

                // --- LAYER 3: THE HIGH HILL (Foreground) ---
                val hillPath = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, size.height * 0.85f)
                    cubicTo(size.width * 0.2f, size.height * 0.75f, size.width * 0.35f, size.height * 0.82f, size.width * 0.5f, size.height * 0.82f)
                    cubicTo(size.width * 0.65f, size.height * 0.82f, size.width * 0.8f, size.height * 0.90f, size.width, size.height * 0.85f)
                    lineTo(size.width, size.height); close()
                }
                drawPath(hillPath, Brush.verticalGradient(listOf(Color(0xFF2E4028), Color(0xFF0A120B))))

                // --- LAYER 4: THE LAKE & REFLECTIONS ---
                drawRect(Color(0xFF0B1320), topLeft = Offset(0f, horizonY), size = Size(size.width, lakeBottom - horizonY))

                // Mountain Reflections (Flipped and darkened)
                val reflectPath = Path().apply {
                    moveTo(0f, horizonY)
                    cubicTo(size.width * 0.25f, horizonY + 50f, size.width * 0.45f, horizonY + 90f, size.width * 0.65f, horizonY + 40f)
                    cubicTo(size.width * 0.8f, horizonY + 10f, size.width * 0.95f, horizonY + 60f, size.width, horizonY)
                    lineTo(size.width, horizonY); close()
                }
                drawPath(reflectPath, Color(0xFF050814).copy(alpha = 0.7f))

                // Celestial Reflection Pillar
                val reflectWidth = 120f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(lightColor.copy(alpha = 0.5f), lightColor.copy(alpha = 0.1f), Color.Transparent),
                        startY = horizonY, endY = lakeBottom
                    ),
                    topLeft = Offset(sunX - reflectWidth / 2, horizonY),
                    size = Size(reflectWidth, lakeBottom - horizonY)
                )

                // Rhythmic Water Ripples
                for (i in 0 until 25) {
                    val y = horizonY + (lakeBottom - horizonY) * (i / 25f)
                    val waveOffset = sin(time * 2 + i * 1.2f) * 20f
                    val rippleWidth = 60f + cos(i * 0.8f) * 40f
                    drawLine(
                        color = lightColor.copy(alpha = 0.4f - i * 0.015f),
                        start = Offset(sunX - rippleWidth / 2 + waveOffset, y),
                        end = Offset(sunX + rippleWidth / 2 + waveOffset, y),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round
                    )
                }

                // --- LAYER 5: CHIAROSCURO (Fire Light Bleeding onto the world) ---
                // This is the "moving portrait" magic. The fire casts a warm glow over the dark grass and stones.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF8A65).copy(alpha = 0.6f * flameScale), // Warm firelight
                            Color(0xFFE64A19).copy(alpha = 0.2f * flameScale),
                            Color.Transparent
                        ),
                        center = fireCenter,
                        radius = 500f * flameScale
                    )
                )

                // --- LAYER 6: 3D CAMPFIRE & STONES ---
                // 3D Stones (Base + Shadow + Highlight)
                for (i in 0 until 10) {
                    val angle = i * (2 * PI / 10)
                    val stoneX = fireCenter.x + 120f * cos(angle).toFloat()
                    val stoneY = fireCenter.y + 35f * sin(angle).toFloat()
                    draw3DStone(Offset(stoneX, stoneY), 15f)
                }

                // Base Logs in Fire (Charred)
                draw3DLog(Offset(fireCenter.x - 60f, fireCenter.y), Offset(fireCenter.x + 60f, fireCenter.y - 10f), 18f, true)
                draw3DLog(Offset(fireCenter.x - 50f, fireCenter.y - 20f), Offset(fireCenter.x + 50f, fireCenter.y - 5f), 16f, true)

                // The Flame (Morphing Beziers)
                if (logsBurning.value > 0) {
                    val flicker = sin(time * 8) * 10f * flameScale
                    val flamePath = Path().apply {
                        moveTo(fireCenter.x, fireCenter.y)
                        cubicTo(
                            fireCenter.x - 70f * flameScale, fireCenter.y - 50f * flameScale,
                            fireCenter.x - 30f * flameScale + flicker, fireCenter.y - 200f * flameScale,
                            fireCenter.x, fireCenter.y - 220f * flameScale
                        )
                        cubicTo(
                            fireCenter.x + 30f * flameScale - flicker, fireCenter.y - 200f * flameScale,
                            fireCenter.x + 70f * flameScale, fireCenter.y - 50f * flameScale,
                            fireCenter.x, fireCenter.y
                        )
                        close()
                    }
                    // Outer Orange
                    drawPath(flamePath, color = Color(0xFFFF5722).copy(alpha = 0.8f)) 
                    // Mid Yellow
                    drawPath(flamePath, color = Color(0xFFFFEB3B), style = Stroke(width = 30f * flameScale)) 
                    // Core White
                    drawPath(flamePath, color = Color.White, style = Stroke(width = 15f * flameScale)) 
                }

                // --- LAYER 7: 3D WOOD STACK ---
                woodStack.forEachIndexed { index, pos ->
                    if (index != draggingLogIndex) {
                        draw3DLog(pos, Offset(pos.x + 80f, pos.y - 10f), 25f, false)
                    }
                }
                if (draggingLogIndex != -1) {
                    draw3DLog(woodStack[draggingLogIndex] + dragOffset, Offset(woodStack[draggingLogIndex].x + 80f + dragOffset.x, woodStack[draggingLogIndex].y - 10f + dragOffset.y), 25f, false)
                }

                // --- LAYER 8: THE CIRCADIAN WASH (Global Color Bleeding) ---
                // A painterly glaze that shifts the entire canvas from Day to Night
                val nightIntensity = (1f - (effectiveElevation + 90f) / 180f).coerceIn(0f, 0.75f)
                drawRect(color = Color(0xFF050814).copy(alpha = nightIntensity))

            } catch (e: Exception) { }
        }

        // --- UI OVERLAYS ---
        val blackboxTrace = remember { context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).getString("last_crash", null) }
        var showBlackbox by remember { mutableStateOf(blackboxTrace != null) }
        if (showBlackbox && blackboxTrace != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(Color(0xE6212121), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text("🕯️ The Black Box recovered a crash:", color = Color(0xFFFFCC80), fontSize = 13.sp)
                    Text(text = blackboxTrace, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                    Text("TAP HERE TO DISMISS AND FORGET", color = Color(0xFF80CBC4), fontSize = 11.sp, modifier = Modifier.clickable {
                        context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).edit().remove("last_crash").apply()
                        showBlackbox = false
                    })
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clickable { runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            contentAlignment = Alignment.Center
        ) { Text("⚙", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp) }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text("Preview Mode — your usual launcher is still in charge", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp) }

        LeatherSatchel(context, isDrawerOpen, Modifier.align(Alignment.BottomEnd))
    }
}

// --- 3D DRAWING EXTENSIONS ---

fun DrawScope.draw3DStone(center: Offset, radius: Float) {
    // Drop Shadow
    drawOval(Color.Black.copy(alpha = 0.5f), topLeft = center + Offset(4f, 8f), size = Size(radius * 2, radius * 1.2f))
    // Base Stone
    drawCircle(Color(0xFF4A4A4A), center, radius)
    // Specular Highlight (Top Left)
    drawCircle(Color(0xFF888888).copy(alpha = 0.7f), center + Offset(-radius * 0.3f, -radius * 0.3f), radius * 0.5f)
    // Core Shadow (Bottom Right)
    drawCircle(Color(0xFF111111).copy(alpha = 0.8f), center + Offset(radius * 0.4f, radius * 0.4f), radius * 0.6f)
}

fun DrawScope.draw3DLog(start: Offset, end: Offset, thickness: Float, isCharred: Boolean) {
    val baseColor = if (isCharred) Color(0xFF1A110B) else Color(0xFF3E2723)
    val highlightColor = if (isCharred) Color(0xFFE64A19).copy(alpha = 0.6f) else Color(0xFF5D4037)
    
    // Drop Shadow
    drawLine(Color.Black.copy(alpha = 0.5f), start + Offset(0f, 10f), end + Offset(0f, 10f), strokeWidth = thickness, cap = StrokeCap.Round)
    // Bark Base
    drawLine(baseColor, start, end, strokeWidth = thickness, cap = StrokeCap.Round)
    // Bark Highlight (Top Edge)
    drawLine(highlightColor, start + Offset(0f, -thickness / 4), end + Offset(0f, -thickness / 4), strokeWidth = thickness / 3, cap = StrokeCap.Round)
    
    // End Cap (if not charred)
    if (!isCharred) {
        val capCenter = end
        drawOval(Color(0xFF8D6E63), topLeft = capCenter - Offset(thickness/2, thickness/2), size = Size(thickness, thickness))
        drawOval(Color(0xFF4E342E), topLeft = capCenter - Offset(thickness/3, thickness/3), size = Size(thickness/1.5f, thickness/1.5f))
    }
}

@Composable
fun LeatherSatchel(context: Context, isExpanded: MutableState<Boolean>, modifier: Modifier = Modifier) {
    val height by animateFloatAsState(
        targetValue = if (isExpanded.value) 600f else 80f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "SatchelHeight"
    )

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(isExpanded.value) {
        if (isExpanded.value && apps.isEmpty()) {
            apps = withContext(Dispatchers.Default) {
                runCatching { getInstalledApps(context) }.getOrElse { emptyList() }
            }
        }
    }

    Box(
        modifier = modifier
            .padding(24.dp)
            .width(300.dp)
            .height(height.dp)
            .then(if (isExpanded.value) Modifier.blur(40.dp) else Modifier)
            .background(if (isExpanded.value) Color(0x99000000) else Color(0xFF4E342E), shape = RoundedCornerShape(24.dp))
            .clickable { isExpanded.value = !isExpanded.value }
    ) {
        if (!isExpanded.value) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(20.dp).background(Color.White, CircleShape))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(apps) { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) } }
                        }
                    ) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.name,
                            modifier = Modifier.size(48.dp)
                        )
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
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            AppInfo(name = resolveInfo.loadLabel(pm).toString(), icon = bmp, packageName = resolveInfo.activityInfo.packageName)
        }.getOrNull()
    }.take(30)
}

class CozyAudioEngine {
    private var ambienceTrack: AudioTrack? = null
    private var fireTrack: AudioTrack? = null
    private var isPlaying = false
    private var isPaused = false
    private var fireIntensity = 0f
    private var ambienceThread: Thread? = null
    private var fireThread: Thread? = null

    fun start() {
        if (isPlaying) return
        isPlaying = true
        isPaused = false
        ambienceThread = Thread {
            try {
                val sampleRate = 16000
                var bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (bufferSize <= 0) bufferSize = 4096
                ambienceTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build()
                ambienceTrack?.play()
                val buffer = ShortArray(bufferSize / 2)
                var lastOut = 0.0
                while (isPlaying) {
                    if (!isPaused) {
                        for (i in buffer.indices) { val white = Math.random() * 2 - 1; lastOut = (lastOut + (0.02 * white)) / 1.02; buffer[i] = (lastOut * 32767 * 0.15).toInt().toShort() }
                        ambienceTrack?.write(buffer, 0, buffer.size)
                    } else Thread.sleep(100)
                }
            } catch (e: Exception) { }
        }
        ambienceThread?.start()
        fireThread = Thread {
            try {
                val sampleRate = 22050
                var bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (bufferSize <= 0) bufferSize = 4096
                fireTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build()
                fireTrack?.play()
                val buffer = ShortArray(bufferSize / 2)
                while (isPlaying) {
                    if (!isPaused && fireIntensity > 0.05f) {
                        for (i in buffer.indices) { val crackle = if (Math.random() < 0.005 * fireIntensity) (Math.random() * 2 - 1) * 32767 * fireIntensity else 0.0; buffer[i] = crackle.toInt().toShort() }
                        fireTrack?.write(buffer, 0, buffer.size)
                    } else Thread.sleep(100)
                }
            } catch (e: Exception) { }
        }
        fireThread?.start()
    }
    fun updateFireIntensity(intensity: Float) { fireIntensity = intensity.coerceIn(0f, 1f) }
    fun playThump() { Thread { try { val sampleRate = 44100; val numSamples = (sampleRate * 0.2).toInt(); val buffer = ShortArray(numSamples); for (i in 0 until numSamples) { val t = i.toDouble() / sampleRate; val freq = 60 - (t * 100); val wave = sin(2 * PI * freq * t) * (1 - t / 0.2); val noise = (Math.random() * 2 - 1) * (1 - t / 0.2) * 0.5; buffer[i] = ((wave + noise) * 32767 * 0.5).toInt().toShort() }; val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(buffer.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build(); track.write(buffer, 0, buffer.size); track.play(); Thread.sleep(200); track.release() } catch (e: Exception) { } }.start() }
    fun pause() { isPaused = true; runCatching { ambienceTrack?.pause() }; runCatching { fireTrack?.pause() } }
    fun resume() { isPaused = false; runCatching { ambienceTrack?.play() }; runCatching { fireTrack?.play() } }
    fun stop() { isPlaying = false; ambienceThread?.interrupt(); fireThread?.interrupt(); runCatching { ambienceTrack?.release() }; runCatching { fireTrack?.release() } }
}
