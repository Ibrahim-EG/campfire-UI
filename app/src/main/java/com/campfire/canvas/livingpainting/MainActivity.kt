package com.campfire.canvas.livingpainting

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel: PaintingViewModel by viewModels()
    private val audioEngine = CozyAudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashBlackBox() 
        super.onCreate(savedInstanceState)
        setContent {
            DisposableEffect(Unit) {
                audioEngine.start()
                onDispose { audioEngine.stop() }
            }
            CampfireCanvasApp(viewModel, audioEngine)
        }
    }

    // THE BLACK BOX V2: Writes the crash log to the public Documents folder so you can read it via "My Files"
    private fun installCrashBlackBox() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = "Thread: ${thread.name}\n" + Log.getStackTraceString(error)
                
                // 1. Internal vault (for the UI card if the app partially boots)
                getSharedPreferences("blackbox", MODE_PRIVATE).edit()
                    .putString("last_crash", trace)
                    .apply()

                // 2. External file (Accessible via Samsung "My Files" -> Documents -> CampfireCanvas)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "campfire_crash_${System.currentTimeMillis()}.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CampfireCanvas")
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        out.write(trace.toByteArray())
                    }
                }
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
    val effectiveElevation by animateFloatAsState(
        targetValue = targetElevation,
        animationSpec = tween(
            durationMillis = 90_000,
            easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f) 
        ),
        label = "CircadianElevation"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (constraints.maxWidth == 0 || constraints.maxHeight == 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            return@BoxWithConstraints
        }

        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val isVertical = maxHeight > maxWidth
        val horizonY = size.height * if (isVertical) 0.6f else 0.5f
        val grassTop = size.height * 0.80f
        val lakeTop = horizonY
        val lakeBottom = grassTop

        var staticBackground by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(size, isVertical) {
            staticBackground = withContext(Dispatchers.Default) {
                runCatching { generateStaticBackground(size, horizonY, lakeTop, lakeBottom, isVertical) }.getOrNull()
            }
        }

        var sunPos by remember { mutableStateOf(Offset(size.width / 2, size.height / 4)) }
        var moonPos by remember { mutableStateOf(Offset(size.width / 2, size.height * 0.75f)) }

        LaunchedEffect(Unit) {
            while (true) {
                val t = (effectiveElevation / 90f).coerceIn(-1f, 1f)
                // DEFUSED: Kotlin strictly forbids mixing Float and Double. 
                // We explicitly cast t to Double for the PI math, then back to Float for the Offset.
                val sinVal = sin(t.toDouble() * PI).toFloat()
                sunPos = Offset(size.width * (0.5f + 0.4f * sinVal), size.height * (0.5f - 0.4f * t))
                moonPos = Offset(size.width * (0.5f - 0.4f * sinVal), size.height * (0.5f + 0.4f * t))
                kotlinx.coroutines.delay(60_000) 
            }
        }

        val logsBurning = viewModel.logsBurning
        val burnProgress = remember { List(5) { Animatable(0f) } }
        val fireCenter = Offset(size.width * 0.5f, size.height * 0.88f)

        val woodStack = remember {
            mutableStateListOf(
                Offset(size.width * 0.1f, size.height * 0.92f),
                Offset(size.width * 0.15f, size.height * 0.94f),
                Offset(size.width * 0.2f, size.height * 0.92f)
            )
        }
        var draggingLogIndex by remember { mutableStateOf(-1) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        val birds = remember {
            List(4) { i -> BirdState(y = 100f + i * 50f, speed = 20f + i * 5f, scale = 1f - i * 0.15f) }
        }

        val isDrawerOpen = remember { mutableStateOf(false) }

        LaunchedEffect(logsBurning.value) {
            audioEngine.updateFireIntensity(logsBurning.value / 5f)
        }

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
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
                                        burnProgress[logSlot].animateTo(1f, tween(150_000, easing = LinearEasing)) 
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
        ) {
            try {
                val bg = staticBackground
                if (bg != null) {
                    drawImage(
                        image = bg,
                        topLeft = Offset.Zero,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix(getCircadianMatrix(effectiveElevation)))
                    )
                } else {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF283593), Color(0xFF5C6BC0), Color(0xFF33691E)) 
                        )
                    )
                }

                if (effectiveElevation > 0) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFD700), Color.Transparent),
                            center = sunPos,
                            radius = (size.width * 0.04f).coerceAtLeast(1f)
                        )
                    )
                } else {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFE0E8F0), Color(0x80E0E8F0), Color.Transparent),
                            center = moonPos,
                            radius = (size.width * 0.03f).coerceAtLeast(1f)
                        )
                    )
                }

                val reflectX = if (effectiveElevation > 0) sunPos.x else moonPos.x
                val reflectColor = if (effectiveElevation > 0) Color(0xFFFFD700) else Color(0xFFE0E8F0)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(reflectColor.copy(alpha = 0.6f), Color.Transparent),
                        center = Offset(reflectX, (lakeTop + lakeBottom) / 2f),
                        radius = 60f
                    ),
                    topLeft = Offset(reflectX - 60f, lakeTop),
                    size = Size(120f, (lakeBottom - lakeTop).coerceAtLeast(1f))
                )

                val dashPath = androidx.compose.ui.graphics.Path()
                for (i in 0 until 6) {
                    val y = lakeTop + (lakeBottom - lakeTop) * (i / 6f)
                    dashPath.moveTo(reflectX - 50f, y)
                    dashPath.lineTo(reflectX + 50f, y)
                }
                drawPath(
                    path = dashPath,
                    color = Color(0x80E0F7FA), 
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f), 0f))
                )

                val time = viewModel.cinematicTime.value / 1_000_000_000f
                birds.forEach { bird ->
                    val x = (time * bird.speed) % (size.width + 200) - 100
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x, bird.y)
                        quadraticBezierTo(x + 10 * bird.scale, bird.y - 10 * bird.scale, x + 20 * bird.scale, bird.y)
                        moveTo(x + 20 * bird.scale, bird.y)
                        quadraticBezierTo(x + 30 * bird.scale, bird.y - 10 * bird.scale, x + 40 * bird.scale, bird.y)
                    }
                    drawPath(path = path, color = Color.Black, style = Stroke(width = 2f * bird.scale, cap = StrokeCap.Round)) 
                }

                val flameScale = logsBurning.value / 5f
                if (flameScale > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x80E64A19), Color(0x00E64A19)), 
                            center = fireCenter,
                            radius = (400f * flameScale).coerceAtLeast(1f)
                        )
                    )
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
                    drawPath(flamePath, color = Color(0x80FF5722)) 
                    drawPath(flamePath, color = Color(0xFFFFEB3B), style = Stroke(width = (20f * flameScale).coerceAtLeast(1f))) 
                    drawPath(flamePath, color = Color.White, style = Stroke(width = (10f * flameScale).coerceAtLeast(1f))) 
                }

                for (i in 0 until 8) {
                    val angle = i * (2 * PI / 8)
                    drawCircle(
                        color = Color(0xFF5D4037), 
                        radius = 15f,
                        center = Offset(fireCenter.x + 80f * cos(angle).toFloat(), fireCenter.y + 40f * sin(angle).toFloat())
                    )
                }

                woodStack.forEachIndexed { index, pos ->
                    if (index != draggingLogIndex) {
                        drawRoundRect(
                            color = Color(0xFF3E2723), 
                            topLeft = pos,
                            size = Size(60f, 20f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                        )
                    }
                }
                if (draggingLogIndex != -1) {
                    drawRoundRect(
                        color = Color(0xFF3E2723),
                        topLeft = woodStack[draggingLogIndex] + dragOffset,
                        size = Size(60f, 20f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                    )
                }
            } catch (e: Exception) { }
        }

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
                    Text(
                        text = blackboxTrace,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    )
                    Text(
                        text = "TAP HERE TO DISMISS AND FORGET",
                        color = Color(0xFF80CBC4),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).edit().remove("last_crash").apply()
                            showBlackbox = false
                        }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("⚙", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("Preview Mode — your usual launcher is still in charge", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }

        LeatherSatchel(
            context = context,
            isExpanded = isDrawerOpen,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
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
            .background(
                if (isExpanded.value) Color(0x80000000) else Color(0xFF4E342E), 
                shape = RoundedCornerShape(24.dp)
            )
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
                            runCatching {
                                context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) }
                            }
                        }
                    ) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.name,
                            modifier = Modifier.size(48.dp),
                            colorFilter = ColorFilter.tint(Color.White) 
                        )
                        Text(text = app.name, color = Color.White, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

data class BirdState(val y: Float, val speed: Float, val scale: Float)
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
            AppInfo(
                name = resolveInfo.loadLabel(pm).toString(),
                icon = bmp,
                packageName = resolveInfo.activityInfo.packageName
            )
        }.getOrNull()
    }.take(20)
}

fun generateStaticBackground(size: Size, horizonY: Float, lakeTop: Float, lakeBottom: Float, isVertical: Boolean): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = true }

    val safeHorizonY = horizonY.coerceAtLeast(1f)
    val skyShader = android.graphics.LinearGradient(
        0f, 0f, 0f, safeHorizonY,
        intArrayOf(
            android.graphics.Color.parseColor("#1A237E"), 
            android.graphics.Color.parseColor("#3949AB"), 
            android.graphics.Color.parseColor("#8C9EFF")  
        ),
        floatArrayOf(0f, 0.6f, 1f),
        Shader.TileMode.CLAMP
    )
    paint.shader = skyShader
    canvas.drawRect(0f, 0f, size.width, horizonY, paint)

    val pinePath = Path()
    pinePath.moveTo(0f, horizonY)
    var x = 0f
    while (x < size.width) {
        val h = (Math.random() * 40 + 20).toFloat()
        pinePath.lineTo(x + 10, horizonY - h)
        pinePath.lineTo(x + 20, horizonY)
        x += 30
    }
    pinePath.close()

    paint.shader = null
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.parseColor("#263238") 
    canvas.saveLayer(null, null)
    paint.maskFilter = android.graphics.BlurMaskFilter(15f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    canvas.drawPath(pinePath, paint)
    canvas.restore()

    paint.maskFilter = null
    paint.color = android.graphics.Color.parseColor("#0277BD") 
    canvas.drawRect(0f, lakeTop, size.width, lakeBottom, paint)

    val colors = intArrayOf(
        android.graphics.Color.parseColor("#00838F"), 
        android.graphics.Color.parseColor("#FFF59D"), 
        android.graphics.Color.parseColor("#F48FB1")  
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

    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.parseColor("#33691E") 
    canvas.drawRect(0f, lakeBottom, size.width, size.height, paint)

    val heightDiff = (size.height - lakeBottom).coerceAtLeast(1f)
    for (y in (lakeBottom.toInt() + 5)..size.height.toInt() step 10) {
        val darkness = ((y - lakeBottom) / heightDiff).coerceIn(0f, 1f)
        paint.color = android.graphics.Color.argb(
            (200 + darkness * 55).toInt(),
            (50 - darkness * 20).toInt(),
            (100 - darkness * 40).toInt(),
            (20 - darkness * 20).toInt()
        )
        paint.strokeWidth = 5f + darkness * 10f
        var startX = 0f
        while (startX < size.width) {
            val h = (Math.random() * 20 + 10 + darkness * 20).toFloat()
            canvas.drawLine(startX, y.toFloat(), startX + (Math.random() * 10 - 5).toFloat(), y - h, paint)
            startX += 15
        }
    }
    return bitmap.asImageBitmap()
}

fun getCircadianMatrix(elevation: Float): FloatArray {
    val t = (elevation / 90f).coerceIn(-1f, 1f)
    val nightMatrix = floatArrayOf(
        0.4f, 0.0f, 0.1f, 0f, 0f,
        0.0f, 0.3f, 0.2f, 0f, 0f,
        0.1f, 0.1f, 0.8f, 0f, 40f, 
        0f, 0f, 0f, 1f, 0f
    )
    val duskMatrix = floatArrayOf(
        1.2f, 0.2f, 0.0f, 0f, 20f, 
        0.2f, 0.9f, 0.1f, 0f, 10f, 
        0.0f, 0.0f, 0.5f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    val dayMatrix = floatArrayOf(
        1.0f, 0.0f, 0.0f, 0f, 0f,
        0.0f, 1.1f, 0.1f, 0f, 10f, 
        0.0f, 0.1f, 1.1f, 0f, 10f, 
        0f, 0f, 0f, 1f, 0f
    )
    return if (t < 0) interpolateMatrix(nightMatrix, duskMatrix, t + 1f)
    else interpolateMatrix(duskMatrix, dayMatrix, t)
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
                ambienceTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
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
                            lastOut = (lastOut + (0.02 * white)) / 1.02 
                            buffer[i] = (lastOut * 32767 * 0.15).toInt().toShort()
                        }
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
                fireTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                fireTrack?.play()
                val buffer = ShortArray(bufferSize / 2)
                while (isPlaying) {
                    if (!isPaused && fireIntensity > 0.05f) {
                        for (i in buffer.indices) {
                            // DEFUSED: Explicitly cast fireIntensity to Double to prevent Float/Double mismatch
                            val crackle = if (Math.random() < 0.005 * fireIntensity.toDouble()) (Math.random() * 2 - 1) * 32767.0 * fireIntensity.toDouble() else 0.0
                            buffer[i] = crackle.toInt().toShort()
                        }
                        fireTrack?.write(buffer, 0, buffer.size)
                    } else Thread.sleep(100)
                }
            } catch (e: Exception) { }
        }
        fireThread?.start()
    }

    fun updateFireIntensity(intensity: Float) { fireIntensity = intensity.coerceIn(0f, 1f) }

    fun playThump() {
        Thread {
            try {
                val sampleRate = 44100
                val numSamples = (sampleRate * 0.2).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = 60 - (t * 100) 
                    val wave = sin(2 * PI * freq * t) * (1 - t / 0.2)
                    val noise = (Math.random() * 2 - 1) * (1 - t / 0.2) * 0.5
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
            } catch (e: Exception) { }
        }.start()
    }

    fun pause() {
        isPaused = true
        runCatching { ambienceTrack?.pause() }
        runCatching { fireTrack?.pause() }
    }

    fun resume() {
        isPaused = false
        runCatching { ambienceTrack?.play() }
        runCatching { fireTrack?.play() }
    }

    fun stop() {
        isPlaying = false
        ambienceThread?.interrupt()
        fireThread?.interrupt()
        runCatching { ambienceTrack?.release() }
        runCatching { fireTrack?.release() }
    }
}
