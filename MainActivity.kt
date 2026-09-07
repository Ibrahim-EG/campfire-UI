package com.campfire.canvas.livingpainting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel: PaintingViewModel by viewModels()
    private val audioEngine = CozyAudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request location for true solar elevation (gracefully degrades to simulated cycle if denied)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 100)
        }

        setContent {
            DisposableEffect(Unit) {
                audioEngine.start()
                onDispose {
                    audioEngine.stop()
                }
            }
            CampfireCanvasApp(viewModel, audioEngine)
        }
    }

    override fun onPause() {
        super.onPause()
        audioEngine.pause()
        viewModel.isPaused.value = true // Freeze the Canvas drawing thread completely (0% GPU/CPU usage)
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
    
    // Circadian Color Matrix: All interpolations take 90 seconds using a cubic ease-in-out
    val targetElevation by viewModel.sunElevation
    val effectiveElevation by animateFloatAsState(
        targetValue = targetElevation,
        animationSpec = tween(
            durationMillis = 90_000, // 90 seconds to complete the transition
            easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f) // Cubic easing for a 90-second sunset bleed, ensuring no hard cuts
        ),
        label = "CircadianElevation"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        
        // Adaptive Rotation: Detects orientation to adjust the living painting's composition
        val isVertical = maxHeight > maxWidth
        val horizonY = size.height * if (isVertical) 0.6f else 0.5f // Horizon drops slightly lower in vertical
        val grassTop = size.height * 0.80f // Grass layer remains pinned to the absolute bottom 20%
        val lakeTop = horizonY
        val lakeBottom = grassTop

        // Hardware Caching: Cache Layers 1, 2, and 3 into a single ImageBitmap
        val staticBackground = remember(size, isVertical) {
            generateStaticBackground(size, horizonY, lakeTop, lakeBottom, isVertical)
        }

        // Celestial Bodies: Update X/Y coordinates only once every 60 seconds to save CPU
        var sunPos by remember { mutableStateOf(Offset.Zero) }
        var moonPos by remember { mutableStateOf(Offset.Zero) }
        
        LaunchedEffect(Unit) {
            while (true) {
                val t = (effectiveElevation / 90f).coerceIn(-1f, 1f)
                val sunX = size.width * (0.5f + 0.4f * sin(t * PI))
                val sunY = size.height * (0.5f - 0.4f * t)
                sunPos = Offset(sunX.toFloat(), sunY.toFloat())
                
                val moonX = size.width * (0.5f - 0.4f * sin(t * PI))
                val moonY = size.height * (0.5f + 0.4f * t)
                moonPos = Offset(moonX.toFloat(), moonY.toFloat())
                
                kotlinx.coroutines.delay(60_000) // 60 seconds update interval
            }
        }

        // Fire & Wood State
        val logsBurning = viewModel.logsBurning
        val burnProgress = remember { List(5) { Animatable(0f) } }
        val fireCenter = Offset(size.width * 0.5f, size.height * 0.88f)
        
        // Wood Stack: 3 small pine logs in the bottom-left
        val woodStack = remember {
            mutableStateListOf(
                Offset(size.width * 0.1f, size.height * 0.92f),
                Offset(size.width * 0.15f, size.height * 0.94f),
                Offset(size.width * 0.2f, size.height * 0.92f)
            )
        }
        var draggingLogIndex by remember { mutableStateOf(-1) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        // Sumi-e Birds State
        val birds = remember {
            List(4) { i ->
                BirdState(
                    y = 100f + i * 50f,
                    speed = 20f + i * 5f,
                    scale = 1f - i * 0.15f // Far-away birds move slower and are smaller
                )
            }
        }

        // UI State
        var isDrawerOpen by remember { mutableStateOf(false) }

        // Dynamic Audio Adjustment based on flame height
        LaunchedEffect(logsBurning.value) {
            audioEngine.updateFireIntensity(logsBurning.value / 5f)
        }

        // Main Canvas Rendering
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw cached static background with the Circadian Color Matrix overlay applied
            drawImage(
                image = staticBackground,
                topLeft = Offset.Zero,
                colorFilter = ColorFilter.colorMatrix(getCircadianMatrix(effectiveElevation))
            )

            // Layer: Celestial Bodies (Sun/Moon)
            if (effectiveElevation > 0) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFD700), Color.Transparent), // Pure White core to Warm Yellow edge
                        center = sunPos,
                        radius = size.width * 0.04f // Occupies roughly 4% of the screen width
                    )
                )
            } else {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE0E8F0), Color(0x80E0E8F0), Color.Transparent), // Cool Silver with a soft pale glow
                        center = moonPos,
                        radius = size.width * 0.03f
                    )
                )
            }

            // Layer: Lake Reflection Rule
            drawIntoCanvas { canvas ->
                val pillarPaint = Paint().apply {
                    style = Paint.Style.FILL
                    maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL) // High blur for shimmering water illusion
                    isAntiAlias = true
                }
                
                val reflectX = if (effectiveElevation > 0) sunPos.x else moonPos.x
                val reflectColor = if (effectiveElevation > 0) android.graphics.Color.parseColor("#FFD700") else android.graphics.Color.parseColor("#E0E8F0")
                pillarPaint.color = reflectColor
                
                // Draw vertical pillar beneath the celestial body
                canvas.nativeCanvas.drawRect(reflectX - 20f, lakeTop, reflectX + 20f, lakeBottom, pillarPaint)
                
                // Overlay 6 distinct horizontal dashed lines for rippled water
                val dashPaint = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = android.graphics.Color.parseColor("#E0F7FA") // Low-opacity cyan for shimmering ripples
                    alpha = 120
                    pathEffect = DashPathEffect(floatArrayOf(30f, 20f), 0f)
                    isAntiAlias = true
                }
                
                val path = Path()
                for (i in 0 until 6) {
                    val y = lakeTop + (lakeBottom - lakeTop) * (i / 6f)
                    path.moveTo(reflectX - 50f, y)
                    path.lineTo(reflectX + 50f, y)
                }
                canvas.nativeCanvas.drawPath(path, dashPaint)
            }

            // Layer: Sumi-e Birds
            val time = viewModel.cinematicTime.value / 1_000_000_000f
            birds.forEach { bird ->
                val x = (time * bird.speed) % (size.width + 200) - 100
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, bird.y)
                    quadTo(x + 10 * bird.scale, bird.y - 10 * bird.scale, x + 20 * bird.scale, bird.y)
                    moveTo(x + 20 * bird.scale, bird.y)
                    quadTo(x + 30 * bird.scale, bird.y - 10 * bird.scale, x + 40 * bird.scale, bird.y)
                }
                drawPath(
                    path = path, 
                    color = Color.Black, 
                    style = Stroke(width = 2f * bird.scale, cap = StrokeCap.Round) // Sharp, angular Sumi-e ink strokes
                )
            }

            // Layer: The Chiaroscuro Glow (Fire localized tinting)
            val flameScale = logsBurning.value / 5f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x80E64A19), // Burnt Sienna with 50% alpha for intense, localized warmth
                        Color(0x00E64A19)  // Fading to transparent for a soft chiaroscuro bleed
                    ),
                    center = fireCenter,
                    radius = 400f * flameScale
                ),
                blendMode = BlendMode.Screen // Tints surrounding grass and stones warmly without darkening them
            )

            // Layer: Fire & Stones
            // Ring of 8 stones
            for (i in 0 until 8) {
                val angle = i * (2 * PI / 8)
                val stoneX = fireCenter.x + 80f * cos(angle).toFloat()
                val stoneY = fireCenter.y + 40f * sin(angle).toFloat()
                drawCircle(
                    color = Color(0xFF5D4037), // Earthy Umber for the hearth stones
                    radius = 15f,
                    center = Offset(stoneX, stoneY)
                )
            }

            // Multi-layered Flame (Bezier curves)
            if (flameScale > 0f) {
                val flamePath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(fireCenter.x, fireCenter.y)
                    cubicTo(
                        fireCenter.x - 50f * flameScale, fireCenter.y - 50f * flameScale,
                        fireCenter.x - 25f * flameScale, fireCenter.y - 150f * flameScale,
                        fireCenter.x, fireCenter.y - 150f * flameScale
                    )
                    cubicTo(
                        fireCenter.x + 25f * flameScale, fireCenter.y - 150f * flameScale,
                        fireCenter.x + 50f * flameScale, fireCenter.y - 50f * flameScale,
                        fireCenter.x, fireCenter.y
                    )
                    close()
                }
                
                // Orange transparent tips
                drawPath(flamePath, color = Color(0x80FF5722)) 
                // Yellow mid
                drawPath(flamePath, color = Color(0xFFFFEB3B), style = Stroke(width = 20f * flameScale))
                // White core
                drawPath(flamePath, color = Color.White, style = Stroke(width = 10f * flameScale))
            }

            // Wood Stack
            woodStack.forEachIndexed { index, pos ->
                if (index != draggingLogIndex) {
                    drawRoundRect(
                        color = Color(0xFF3E2723), // Deep pine bark for the unburnt logs
                        topLeft = pos,
                        size = Size(60f, 20f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                    )
                }
            }

            // Draw dragging log
            if (draggingLogIndex != -1) {
                drawRoundRect(
                    color = Color(0xFF3E2723),
                    topLeft = woodStack[draggingLogIndex] + dragOffset,
                    size = Size(60f, 20f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                )
            }
        }

        // --- THE ESCAPE HATCH ---
        // A subtle, frosted gear icon in the top-left. If the app crashes or you feel trapped,
        // tap this to open Android's Home Settings and safely revert to your previous launcher.
        IconButton(
            onClick = {
                val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Escape Hatch - Change Launcher",
                tint = Color.White.copy(alpha = 0.6f) // Soft white to blend with the dusk sky
            )
        }

        // Interaction Layer: Drag and Drop
        Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    woodStack.forEachIndexed { index, pos ->
                        if (offset.x > pos.x && offset.x < pos.x + 60 && offset.y > pos.y && offset.y < pos.y + 20) {
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
                        
                        if (distance < 100f && logsBurning.value < 5) {
                            logsBurning.value++
                            audioEngine.playThump()
                            
                            val logSlot = logsBurning.value - 1
                            scope.launch {
                                burnProgress[logSlot].animateTo(
                                    1f,
                                    tween(150_000, easing = LinearEasing) // Exactly 150 seconds, linear for steady consumption into ash
                                )
                                logsBurning.value--
                                burnProgress[logSlot].snapTo(0f)
                            }
                        }
                    }
                    draggingLogIndex = -1
                    dragOffset = Offset.Zero
                }
            )
        }

        // The Leather Satchel (App Drawer)
        LeatherSatchel(context, isDrawerOpen)
    }
}

@Composable
fun LeatherSatchel(context: Context, isExpanded: MutableState<Boolean>) {
    val height by animateFloatAsState(
        targetValue = if (isExpanded.value) 600f else 80f,
        animationSpec = tween(600, easing = FastOutSlowInEasing), // Heavy leather flap easing
        label = "SatchelHeight"
    )
    
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(24.dp)
            .width(300.dp)
            .height(height.dp)
            .blur(if (isExpanded.value) 40.dp else 0.dp) // Heavy frost for the glass panel
            .background(
                if (isExpanded.value) Color(0x80000000) else Color(0xFF4E342E), // Raw Umber impasto blob
                shape = RoundedCornerShape(24.dp)
            )
            .clickable { isExpanded.value = !isExpanded.value }
    ) {
        if (!isExpanded.value) {
            // Single white highlight on the impasto leather
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(20.dp).background(Color.White, CircleShape))
        } else {
            val apps = remember { getInstalledApps(context) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(apps) { app ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.name,
                            modifier = Modifier.size(48.dp),
                            colorFilter = ColorFilter.tint(Color.White) // Clean, minimal white glyph to contrast the chaotic background
                        )
                        Text(text = app.name, color = Color.White, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// --- Helper Functions & Classes ---

data class BirdState(val y: Float, val speed: Float, val scale: Float)
data class AppInfo(val name: String, val icon: Bitmap)

fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    val appList = pm.queryIntentActivities(intent, 0)
    return appList.map { 
        AppInfo(
            name = it.loadLabel(pm).toString(),
            icon = it.activityInfo.loadIcon(pm).let { drawable ->
                val bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
        )
    }.take(20) // Limit for performance
}

fun generateStaticBackground(size: Size, horizonY: Float, lakeTop: Float, lakeBottom: Float, isVertical: Boolean): androidx.compose.ui.graphics.ImageBitmap {
    val bitmap = Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = true }
    
    // Layer 1: The Chromatic Skydome
    val skyShader = android.graphics.LinearGradient(
        0f, 0f, 0f, horizonY,
        intArrayOf(
            android.graphics.Color.parseColor("#1A237E"), // Prussian Blue for melancholic night depth at the zenith
            android.graphics.Color.parseColor("#3949AB"), // Ultramarine for the receding atmospheric perspective
            android.graphics.Color.parseColor("#8C9EFF")  // Soft periwinkle where the sky kisses the horizon
        ),
        floatArrayOf(0f, 0.6f, 1f),
        Shader.TileMode.CLAMP
    )
    paint.shader = skyShader
    canvas.drawRect(0f, 0f, size.width, horizonY, paint)
    
    // Layer 2: The Distant Pines
    val pinePath = Path()
    pinePath.moveTo(0f, horizonY)
    var x = 0f
    while (x < size.width) {
        val height = (Math.random() * 40 + 20).toFloat()
        pinePath.lineTo(x + 10, horizonY - height)
        pinePath.lineTo(x + 20, horizonY)
        x += 30
    }
    pinePath.close()
    
    paint.shader = null
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.parseColor("#263238") // Blue-grey mist for distant, receding treelines
    
    // Software layer required for BlurMaskFilter to work on hardware canvas
    canvas.saveLayer(null, null)
    paint.maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
    canvas.drawPath(pinePath, paint)
    canvas.restore()
    
    // Layer 3: The "Broken Color" Lake
    paint.maskFilter = null
    paint.color = android.graphics.Color.parseColor("#0277BD") // Deep cerulean for cold, mysterious water depth
    canvas.drawRect(0f, lakeTop, size.width, lakeBottom, paint)
    
    // Impressionist broken color segments
    val colors = intArrayOf(
        android.graphics.Color.parseColor("#00838F"), // Teal for crisp water reflections
        android.graphics.Color.parseColor("#FFF59D"), // Pale Yellow for captured sunlight
        android.graphics.Color.parseColor("#F48FB1")  // Soft Pink for the dying embers of the sun
    )
    
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 8f
    paint.alpha = 150
    
    for (y in (lakeTop.toInt() + 10)..lakeBottom.toInt() step 15) {
        paint.color = colors[(y / 15) % colors.size]
        var startX = 0f
        while (startX < size.width) {
            val len = (Math.random() * 40 + 20).toFloat()
            canvas.drawLine(startX, y.toFloat(), startX + len, y.toFloat(), paint)
            startX += len + 10
        }
    }
    
    // Layer 4: The Cozy Foreground (Grass)
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.parseColor("#33691E") // Sap-Green for the rich, damp forest floor
    canvas.drawRect(0f, lakeBottom, size.width, size.height, paint)
    
    // Dry-brush impasto strokes (larger and darker at the bottom edge)
    for (y in (lakeBottom.toInt() + 5)..size.height.toInt() step 10) {
        val darkness = (y - lakeBottom) / (size.height - lakeBottom)
        paint.color = android.graphics.Color.argb(
            (200 + darkness * 55).toInt(), 
            (50 - darkness * 20).toInt(), 
            (100 - darkness * 40).toInt(), 
            (20 - darkness * 20).toInt()
        )
        paint.strokeWidth = 5f + darkness * 10f
        
        var startX = 0f
        while (startX < size.width) {
            val height = (Math.random() * 20 + 10 + darkness * 20).toFloat()
            canvas.drawLine(startX, y.toFloat(), startX + (Math.random() * 10 - 5).toFloat(), y - height, paint)
            startX += 15
        }
    }
    
    return bitmap.asImageBitmap()
}

fun getCircadianMatrix(elevation: Float): FloatArray {
    val t = (elevation / 90f).coerceIn(-1f, 1f)
    
    // Night (Indigo Cozy): Prussian Blue + Ultramarine overlay. Contrast ratio drops.
    val nightMatrix = floatArrayOf(
        0.4f, 0.0f, 0.1f, 0f, 0f,   
        0.0f, 0.3f, 0.2f, 0f, 0f,   
        0.1f, 0.1f, 0.8f, 0f, 40f,  
        0f,   0f,   0f,   1f, 0f
    )

    // Dawn/Dusk (Golden Cozy): Madder Lake Red + Gamboge Yellow. Shadows turn soft Mauve.
    val duskMatrix = floatArrayOf(
        1.2f, 0.2f, 0.0f, 0f, 20f,  
        0.2f, 0.9f, 0.1f, 0f, 10f,  
        0.0f, 0.0f, 0.5f, 0f, 0f,   
        0f,   0f,   0f,   1f, 0f
    )

    // Midday (Crisp Cozy): Vibrant Cyan and boosted Yellow-Green.
    val dayMatrix = floatArrayOf(
        1.0f, 0.0f, 0.0f, 0f, 0f,   
        0.0f, 1.1f, 0.1f, 0f, 10f,  
        0.0f, 0.1f, 1.1f, 0f, 10f,  
        0f,   0f,   0f,   1f, 0f
    )

    return if (t < 0) {
        val localT = (t + 1f) 
        interpolateMatrix(nightMatrix, duskMatrix, localT)
    } else {
        val localT = t 
        interpolateMatrix(duskMatrix, dayMatrix, localT)
    }
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
        
        // Ambience: Low-bitrate Brownian noise for soft wind and distant rustling leaves
        ambienceThread = Thread {
            val sampleRate = 16000
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            ambienceTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            ambienceTrack?.play()
            val buffer = ShortArray(bufferSize / 2)
            var lastOut = 0.0
            
            while (isPlaying) {
                if (!isPaused) {
                    for (i in buffer.indices) {
                        val white = Math.random() * 2 - 1
                        lastOut = (lastOut + (0.02 * white)) / 1.02 // Brownian motion integration
                        buffer[i] = (lastOut * 32767 * 0.15).toInt().toShort()
                    }
                    ambienceTrack?.write(buffer, 0, buffer.size)
                } else {
                    Thread.sleep(100)
                }
            }
        }
        ambienceThread?.start()
        
        // Fire: Crackle noise modulated by fireIntensity
        fireThread = Thread {
            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            fireTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            fireTrack?.play()
            val buffer = ShortArray(bufferSize / 2)
            
            while (isPlaying) {
                if (!isPaused && fireIntensity > 0.05f) {
                    for (i in buffer.indices) {
                        // Random pops for crackling wood
                        val crackle = if (Math.random() < 0.005 * fireIntensity) (Math.random() * 2 - 1) * 32767 * fireIntensity else 0.0
                        buffer[i] = crackle.toInt().toShort()
                    }
                    fireTrack?.write(buffer, 0, buffer.size)
                } else {
                    Thread.sleep(100)
                }
            }
        }
        fireThread?.start()
    }

    fun updateFireIntensity(intensity: Float) {
        fireIntensity = intensity.coerceIn(0f, 1f)
    }
    
    fun playThump() {
        // Distinct low-end "thump" + sharp whoosh when dropping a log
        Thread {
            val sampleRate = 44100
            val duration = 0.2
            val numSamples = (sampleRate * duration).toInt()
            val buffer = ShortArray(numSamples)
            
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = 60 - (t * 100) // Pitch sweep down
                val wave = sin(2 * PI * freq * t) * (1 - t / duration)
                val noise = (Math.random() * 2 - 1) * (1 - t / duration) * 0.5
                buffer[i] = ((wave + noise) * 32767 * 0.5).toInt().toShort()
            }
            
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                
            track.write(buffer, 0, buffer.size)
            track.play()
            Thread.sleep(200)
            track.release()
        }.start()
    }

    fun pause() {
        isPaused = true
        ambienceTrack?.pause()
        fireTrack?.pause()
    }

    fun resume() {
        isPaused = false
        ambienceTrack?.play()
        fireTrack?.play()
    }

    fun stop() {
        isPlaying = false
        ambienceThread?.interrupt()
        fireThread?.interrupt()
        ambienceTrack?.release()
        fireTrack?.release()
    }
}
