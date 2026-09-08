package com.campfire.canvas.livingpainting

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private val viewModel: PaintingViewModel by viewModels()
    private val audio = CozyAudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        installBlackBox()
        super.onCreate(savedInstanceState)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
        }
        setContent {
            DisposableEffect(Unit) { audio.start(); onDispose { audio.stop() } }
            CampfireRoot(viewModel, audio)
        }
    }

    override fun onResume() {
        super.onResume()
        audio.resume(); viewModel.isPaused.value = false
        runCatching {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                for (pn in lm.getProviders(true)) {
                    val loc = lm.getLastKnownLocation(pn)
                    if (loc != null) { viewModel.setLatitude(loc.latitude); break }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        audio.pause(); viewModel.isPaused.value = true
    }

    private fun installBlackBox() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = "Thread: ${t.name}\n" + Log.getStackTraceString(e)
                getSharedPreferences("blackbox", MODE_PRIVATE).edit().putString("last_crash", trace).apply()
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "campfire_crash_${System.currentTimeMillis()}.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CampfireCanvas")
                }
                contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(trace.toByteArray()) } }
            }
            prev?.uncaughtException(t, e)
        }
    }
}

data class BurningLog(val startTime: Long)

@Composable
fun CampfireRoot(viewModel: PaintingViewModel, audio: CozyAudioEngine) {
    val context = LocalContext.current

    val prefs = remember { context.getSharedPreferences("scene_grid", Context.MODE_PRIVATE) }
    var grid by remember { mutableStateOf(SceneGrid(
        prefs.getFloat("horizon", 0.55f), prefs.getFloat("lakeEnd", 0.75f),
        prefs.getFloat("fireX", 0.50f), prefs.getFloat("fireY", 0.82f))) }
    var repaintKey by remember { mutableStateOf(0) }
    var atelierOpen by remember { mutableStateOf(false) }

    val target by viewModel.sunElevation
    val elevation by animateFloatAsState(target, tween(90_000, easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)), label = "circadian")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (constraints.maxWidth == 0 || constraints.maxHeight == 0) { Box(Modifier.fillMaxSize().background(Color.Black)); return@BoxWithConstraints }
        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val horizonY = size.height * grid.horizon
        val lakeBottom = size.height * grid.lakeEnd
        val fireCenter = Offset(size.width * grid.fireX, size.height * grid.fireY)
        val time = viewModel.cinematicTime.value / 1_000_000_000f
        val night = ((-elevation) / 30f).coerceIn(0f, 1f)
        val dusk = bell(elevation, 4f, 22f)

        var world by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(size, repaintKey, grid.horizon, grid.lakeEnd, grid.fireX, grid.fireY) {
            world = withContext(Dispatchers.Default) {
                runCatching { paintStaticWorld(size.width.toInt(), size.height.toInt(), grid.horizon, grid.lakeEnd, grid.fireX, grid.fireY) }.getOrNull()
            }
        }

        // Timestamp-based burn mechanic (avoids Animatable type inference traps)
        val burns = remember { mutableStateListOf<BurningLog>() }
        val flameScale = (burns.size / 5f).coerceAtLeast(0.06f)
        LaunchedEffect(burns.size) { viewModel.setFireIntensity(burns.size / 5f); audio.updateFire(burns.size / 5f) }
        
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = viewModel.cinematicTime.value
                val it = burns.iterator()
                while (it.hasNext()) {
                    if (now - it.next().startTime >= 150_000_000_000L) it.remove()
                }
            }
        }

        var pile by remember { mutableStateOf(3) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(90_000); if (!viewModel.isPaused.value && pile < 3) pile++ } }
        val pileSlots = remember(size) { listOf(Offset(size.width * 0.12f, size.height * 0.90f), Offset(size.width * 0.18f, size.height * 0.935f), Offset(size.width * 0.25f, size.height * 0.905f)) }
        var dragIdx by remember { mutableStateOf(-1) }
        var dragOff by remember { mutableStateOf(Offset.Zero) }

        val minuteBucket = viewModel.cinematicTime.value / 60_000_000_000L
        var sunPos by remember { mutableStateOf(Offset(size.width / 2, size.height / 4)) }
        LaunchedEffect(minuteBucket, size) {
            val ha = viewModel.sunHourAngle.value
            val el = viewModel.sunElevation.value
            val sunX = size.width * (0.5f + 0.45f * sin(ha))
            val sunY = horizonY - size.height * 0.42f * (el / 90f)
            sunPos = Offset(sunX, sunY)
        }
        val isDay = elevation > 0
        val lightColor = when {
            elevation > 30 -> Color(0xFFFFD54F); elevation > 0 -> Color(0xFFFF7043)
            elevation > -30 -> Color(0xFFBA68C8); else -> Color(0xFF90A4AE)
        }
        val cloudTint = lerpColor(Color(0xFFE8EAF6), Color(0xFFE6A08C), dusk)

        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset: Offset -> 
                    pileSlots.forEachIndexed { i, p -> 
                        if (i < pile && offset.x > p.x - 20 && offset.x < p.x + 120 && offset.y > p.y - 20 && offset.y < p.y + 40) { 
                            dragIdx = i; dragOff = Offset.Zero 
                        } 
                    } 
                },
                onDrag = { change: PointerInputChange, dragAmount: Offset -> 
                    if (dragIdx != -1) { dragOff += dragAmount; change.consume() } 
                },
                onDragEnd = {
                    if (dragIdx != -1) {
                        val drop = pileSlots[dragIdx] + dragOff
                        if (sqrt((drop.x - fireCenter.x) * (drop.x - fireCenter.x) + (drop.y - fireCenter.y) * (drop.y - fireCenter.y)) < 160f && burns.size < 5) {
                            pile--
                            audio.playDrop()
                            burns.add(BurningLog(viewModel.cinematicTime.value))
                        }
                    }
                    dragIdx = -1; dragOff = Offset.Zero
                })
        }) {
            try {
                val circadian = ColorFilter.colorMatrix(ColorMatrix(circadianMatrix(elevation)))
                val w = world
                if (w != null) drawImage(w, topLeft = Offset.Zero, colorFilter = circadian)
                else drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1021), Color(0xFF2A3B5C), Color(0xFF8C6E5D))))

                drawTwinklingStars(size, time, night, horizonY)
                drawClouds(size, time, horizonY, cloudTint)
                
                val ha = viewModel.sunHourAngle.value
                val moonY = horizonY - size.height * 0.30f * sin(ha + PI.toFloat())
                drawCelestial(sunPos.x, if (isDay) sunPos.y else moonY, isDay, lightColor, size.width)
                
                drawWaterLife(horizonY, lakeBottom, time, viewModel.wind.value, lightColor, size)
                drawBirds(viewModel.birds.map { SumiBirdProxy(it.x, it.depth, it.phase) }, time, size)
                drawGlazes(elevation)

                val flick = 0.9f + 0.1f * sin(time * 11f) + 0.05f * sin(time * 23f)
                if (burns.size > 0) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF6D3A).copy(alpha = 0.55f * flameScale * flick), Color(0xFFE64A19).copy(alpha = 0.22f * flameScale), Color.Transparent), center = fireCenter, radius = 720f * flameScale), blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
                }

                val now = viewModel.cinematicTime.value
                for (i in 0 until 8) {
                    val a = i * (2f * PI.toFloat() / 8f)
                    val sx = fireCenter.x + 130f * cos(a)
                    val sy = fireCenter.y + 40f * sin(a)
                    draw3DStone(Offset(sx, sy), 17f, fireCenter, Color(0xFFFF5722), burns.size > 0)
                }
                
                burns.forEachIndexed { i, b ->
                    val elapsed = (now - b.startTime) / 1000_000_000f
                    val p = (elapsed / 150f).coerceIn(0f, 1f)
                    val shrink = 1f - 0.5f * p
                    draw3DLog(
                        Offset(fireCenter.x - 66f * shrink, fireCenter.y + 8f - i * 10f), 
                        Offset(fireCenter.x + 66f * shrink, fireCenter.y - 4f - i * 10f), 
                        20f * shrink, p, fireCenter, Color(0xFFFF5722), time
                    )
                }
                
                if (burns.size > 0) {
                    drawFlame(fireCenter, flameScale * flick, time)
                    drawSmoke(fireCenter, flameScale, time, viewModel.wind.value, flameScale)
                    viewModel.embers.forEach { e ->
                        drawCircle(if (e.life > 0.6f) Color(0xFFFFEB3B) else Color(0xFFFF5722), radius = e.size * e.life.coerceIn(0f, 1f), center = Offset(fireCenter.x + e.x, fireCenter.y - 40f + e.y), blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
                    }
                }
                
                pileSlots.forEachIndexed { i, p -> if (i < pile && i != dragIdx) draw3DLog(p, Offset(p.x + 100f, p.y - 14f), 26f, 0f, fireCenter, Color(0xFFFF5722), time) }
                if (dragIdx != -1) { val p = pileSlots[dragIdx] + dragOff; draw3DLog(p, Offset(p.x + 100f, p.y - 14f), 26f, 0f, fireCenter, Color(0xFFFF5722), time) }

                drawGrassFringe(size, time, viewModel.wind.value)
                drawFireflies(size, time, night)
            } catch (e: Exception) { }
        }

        Box(Modifier.fillMaxSize()) {
            val trace = remember { context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).getString("last_crash", null) }
            var showBB by remember { mutableStateOf(trace != null) }
            if (showBB && trace != null) BlackBoxCard(trace) { context.getSharedPreferences("blackbox", Context.MODE_PRIVATE).edit().remove("last_crash").apply(); showBB = false }
            Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                EscapeGear(onTap = { runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, onLong = { atelierOpen = !atelierOpen })
            }
            if (atelierOpen) Box(Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 20.dp, end = 20.dp)) {
                CalibrationPanel(grid, onChange = { grid = it }, onApply = {
                    prefs.edit().putFloat("horizon", grid.horizon).putFloat("lakeEnd", grid.lakeEnd).putFloat("fireX", grid.fireX).putFloat("fireY", grid.fireY).apply()
                    repaintKey++
                }, onClose = { atelierOpen = false })
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { PreviewBadge() }
            LeatherSatchel(context, remember { mutableStateOf(false) }, Modifier.align(Alignment.BottomEnd))
        }
    }
}
