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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel: PaintingViewModel by viewModels()
    private val audioEngine = CozyAudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashBlackBox() 
        super.onCreate(savedInstanceState)
        
        // Re-enabled Location Request: Gracefully degrades if denied
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
        val horizonY = size.height * 0.55f
        val lakeBottom = size.height * 0.75f

        // The Campfire sits on the peak of the high hill
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

        LaunchedEffect(logsBurning.value) {
            audioEngine.updateFireIntensity(logsBurning.value / 5f)
        }

        // THE CANVAS: Wrapped in a graphicsLayer to apply the Circadian Color Matrix to the ENTIRE painting uniformly
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix(getCircadianMatrix(effectiveElevation)))
                }
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
                // --- LAYER 1: THE SKY ---
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0B1021), // Deep night zenith
                            Color(0xFF2A3B5C), // Mid-sky twilight
                            Color(0xFF8C6E5D)  // Horizon dusk glow
                        )
                    )
                )

                // --- LAYER 2: CELESTIAL BODIES ---
                val sunAngle = (effectiveElevation / 90f) * Math.PI
                val sunX = size.width * (0.5f + 0.4f * cos(sunAngle)).toFloat()
                val sunY = horizonY - (size.height * 0.4f * sin(sunAngle)).toFloat()
                
                val isDay = effectiveElevation > 0
                val celestialColor = if (isDay) Color(0xFFFFD700) else Color(0xFFE0E8F0)
                val celestialY = if (isDay) sunY else (horizonY - (size.height * 0.3f * sin(sunAngle + PI)).toFloat())

                if (isDay) {
                    drawCircle(Brush.radialGradient(listOf(Color.White, celestialColor, Color.Transparent), radius = size.width * 0.08f), center = Offset(sunX, celestialY))
                } else {
                    drawCircle(Brush.radialGradient(listOf(celestialColor, celestialColor.copy(alpha = 0.5f), Color.Transparent), radius = size.width * 0.06f), center = Offset(sunX, celestialY))
                }

                // --- LAYER 3: DISTANT MOUNTAINS ---
                val mountainPath = Path().apply {
                    moveTo(0f, horizonY)
                    cubicTo(size.width * 0.2f, horizonY - 100f, size.width * 0.3f, horizonY - 150f, size.width * 0.5f, horizonY - 80f)
                    cubicTo(size.width * 0.7f, horizonY - 10f, size.width * 0.8f, horizonY - 120f, size.width, horizonY - 40f)
                    lineTo(size.width, horizonY)
                    close()
                }
                drawPath(mountainPath, Color(0xFF1A2530)) // Dark silhouette blue-grey

                // --- LAYER 4: THE LAKE ---
                drawRect(Color(0xFF121A2F), topLeft = Offset(0f, horizonY), size = Size(size.width, lakeBottom - horizonY))

                // --- LAYER 5: WATER REFLECTIONS (Structured, not random) ---
                val reflectWidth = 120f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(celestialColor.copy(alpha = 0.6f), celestialColor.copy(alpha = 0.1f), Color.Transparent),
                        startY = horizonY, endY = lakeBottom
                    ),
                    topLeft = Offset(sunX - reflectWidth / 2, horizonY),
                    size = Size(reflectWidth, lakeBottom - horizonY)
                )

                // Rhythmic Ripples
                for (i in 0 until 15) {
                    val y = horizonY + (lakeBottom - horizonY) * (i / 15f)
                    val waveOffset = sin(i * 1.2f) * 40f
                    val rippleWidth = 80f + cos(i * 0.8f) * 30f
                    drawLine(
                        color = celestialColor.copy(alpha = 0.5f - i * 0.03f),
                        start = Offset(sunX - rippleWidth / 2 + waveOffset, y),
                        end = Offset(sunX + rippleWidth / 2 + waveOffset, y),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }

                // --- LAYER 6: THE HIGH HILL (Foreground) ---
                val hillPath = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, size.height * 0.85f)
                    cubicTo(size.width * 0.2f, size.height * 0.75f, size.width * 0.35f, size.height * 0.82f, size.width * 0.5f, size.height * 0.82f)
                    cubicTo(size.width * 0.65f, size.height * 0.82f, size.width * 0.8f, size.height * 0.90f, size.width, size.height * 0.85f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(hillPath, Brush.verticalGradient(listOf(Color(0xFF2E4028), Color(0xFF111810))))

                // --- LAYER 7: THE CAMPFIRE ---
                val flameScale = (logsBurning.value / 5f).coerceAtLeast(0.1f) // Base embers always glow
                
                // The Chiaroscuro Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE64A19).copy(alpha = 0.8f), Color(0xFFE64A19).copy(alpha = 0.2f), Color.Transparent),
                        center = fireCenter,
                        radius = 400f * flameScale
                    )
                )

                // Hearth Stones
                for (i in 0 until 10) {
                    val angle = i * (2 * PI / 10)
                    val stoneX = fireCenter.x + 100f * cos(angle).toFloat()
                    val stoneY = fireCenter.y + 30f * sin(angle).toFloat()
                    drawCircle(color = Color(0xFF3E2723), radius = 12f, center = Offset(stoneX, stoneY))
                }

                // The Flame (Layered Beziers)
                if (logsBurning.value > 0) {
                    val flamePath = Path().apply {
                        moveTo(fireCenter.x, fireCenter.y)
                        cubicTo(
                            fireCenter.x - 60f * flameScale, fireCenter.y - 40f * flameScale,
                            fireCenter.x - 30f * flameScale, fireCenter.y - 180f * flameScale,
                            fireCenter.x, fireCenter.y - 180f * flameScale
                        )
                        cubicTo(
                            fireCenter.x + 30f * flameScale, fireCenter.y - 180f * flameScale,
                            fireCenter.x + 60f * flameScale, fireCenter.y - 40f * flameScale,
                            fireCenter.x, fireCenter.y
                        )
                        close()
                    }
                    drawPath(flamePath, color = Color(0xFFFF5722).copy(alpha = 0.8f)) // Outer Orange
                    drawPath(flamePath, color = Color(0xFFFFEB3B), style = Stroke(width = 25f * flameScale)) // Mid Yellow
                    drawPath(flamePath, color = Color.White, style = Stroke(width = 12f * flameScale)) // Core White
                }

                // Base Logs in Fire
                drawRoundRect(Color(0xFF1A110B), topLeft = Offset(fireCenter.x - 50f, fireCenter.y - 10f), size = Size(100f, 20f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
                drawRoundRect(Color(0xFF2D1A11), topLeft = Offset(fireCenter.x - 40f, fireCenter.y - 25f), size = Size(80f, 20f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))

                // Wood Stack on the Hill
                woodStack.forEachIndexed { index, pos ->
                    if (index != draggingLogIndex) {
                        drawRoundRect(
                            color = Color(0xFF3E2723),
                            topLeft = pos,
                            size = Size(80f, 25f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
                        )
                        // Wood grain highlight
                        drawLine(Color(0xFF5D4037), start = Offset(pos.x + 10f, pos.y + 12f), end = Offset(pos.x + 70f, pos.y + 12f), strokeWidth = 2f)
                    }
                }
                if (draggingLogIndex != -1) {
                    drawRoundRect(
                        color = Color(0xFF3E2723),
                        topLeft = woodStack[draggingLogIndex] + dragOffset,
                        size = Size(80f, 25f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
                    )
                }
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

        // Escape Hatch
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clickable { runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            contentAlignment = Alignment.Center
        ) { Text("⚙", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp) }

        // Preview Badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text("Preview Mode — your usual launcher is still in charge", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp) }

        // The Leather Satchel
        LeatherSatchel(context, isDrawerOpen, Modifier.align(Alignment.BottomEnd))
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

fun getCircadianMatrix(elevation: Float): FloatArray {
    val t = (elevation / 90f).coerceIn(-1f, 1f)
    val nightMatrix = floatArrayOf(0.3f, 0.0f, 0.1f, 0f, 0f, 0.0f, 0.2f, 0.3f, 0f, 0f, 0.1f, 0.1f, 0.8f, 0f, 20f, 0f, 0f, 0f, 1f, 0f)
    val duskMatrix = floatArrayOf(1.2f, 0.2f, 0.0f, 0f, 20f, 0.2f, 0.9f, 0.1f, 0f, 10f, 0.0f, 0.0f, 0.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    val dayMatrix = floatArrayOf(1.0f, 0.0f, 0.0f, 0f, 0f, 0.0f, 1.1f, 0.1f, 0f, 10f, 0.0f, 0.1f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)
    return if (t < 0) interpolateMatrix(nightMatrix, duskMatrix, t + 1f) else interpolateMatrix(duskMatrix, dayMatrix, t)
}

fun interpolateMatrix(m1: FloatArray, m2: FloatArray, t: Float): FloatArray {
    return FloatArray(20) { m1[it] * (1 - t) + m2[it] * t }
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
