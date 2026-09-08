package com.campfire.canvas.livingpainting

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class Ember(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float)
data class SumiBird(var x: Float, val depth: Float, val phase: Float) // depth 0 = farthest

class PaintingViewModel : ViewModel() {
    val cinematicTime = mutableStateOf(0L)          // nanos, ticked at exactly 24 FPS
    val isPaused = mutableStateOf(false)            // Battery Sanctity freeze flag
    val sunElevation = mutableStateOf(10f)          // degrees, -90..90
    val sunHourAngle = mutableStateOf(0f)           // radians, drives the wide sun arc
    val embers = mutableStateListOf<Ember>()
    val birds = mutableStateListOf<SumiBird>()
    val wind = mutableStateOf(0f)                   // -1.5..1.5 slow atmospheric drift
    var fireIntensity = 0f                          // set by UI from burning log count
    private var latitude = 45.0                     // fallback; overwritten by GPS

    private val rnd = Random(777)

    init {
        // Sumi-e flock: 5 birds, far ones smaller & slower (depth-scaled)
        repeat(5) { i ->
            birds.add(SumiBird(x = rnd.nextFloat() * 1.4f - 0.2f, depth = i / 4f, phase = rnd.nextFloat() * 6.28f))
        }

        viewModelScope.launch {
            var lastFrame = System.nanoTime()
            var lastSun = 0L
            while (isActive) {
                if (!isPaused.value) {
                    val now = System.nanoTime()
                    if (now - lastFrame >= 41_666_666L) { // 24 FPS cinematic cap
                        cinematicTime.value = now
                        lastFrame = now
                        val tMs = now / 1_000_000.0

                        // Wind: two incommensurate sine drifts = never repeats
                        wind.value = (sin(tMs * 0.00011) + 0.5f * sin(tMs * 0.000043)).toFloat()

                        // Astronomy refresh every 5s: true solar elevation from latitude + day-of-year
                        if (now - lastSun >= 5_000_000_000L) {
                            lastSun = now
                            val cal = Calendar.getInstance()
                            val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
                            val day = cal.get(Calendar.DAY_OF_YEAR)
                            val decl = 23.45 * sin(2.0 * PI / 365.0 * (day - 81))
                            val ha = 15.0 * (hour - 12.0)
                            val latR = latitude * PI / 180.0
                            val decR = decl * PI / 180.0
                            val haR = ha * PI / 180.0
                            val sinAlt = sin(latR) * sin(decR) + cos(latR) * cos(decR) * cos(haR)
                            sunElevation.value = (asin(sinAlt.coerceIn(-1.0, 1.0)) * 180.0 / PI).toFloat()
                            sunHourAngle.value = haR.toFloat()
                        }

                        // Ember physics: spawn rate follows fire intensity
                        if (fireIntensity > 0.02f && embers.size < (fireIntensity * 45f).toInt()) {
                            if (rnd.nextFloat() < 0.35f * fireIntensity) {
                                embers.add(Ember(
                                    x = (rnd.nextFloat() - 0.5f) * 60f,
                                    y = -10f,
                                    vx = (rnd.nextFloat() - 0.5f) * 1.6f,
                                    vy = -(1.5f + rnd.nextFloat() * 3f),
                                    life = 1f,
                                    size = 1.5f + rnd.nextFloat() * 3f
                                ))
                            }
                        }
                        val it = embers.iterator()
                        while (it.hasNext()) {
                            val e = it.next()
                            e.x += e.vx + wind.value * 0.6f
                            e.y += e.vy
                            e.vy -= 0.045f            // thermal draft acceleration
                            e.vx += (rnd.nextFloat() - 0.5f) * 0.18f
                            e.life -= 0.014f
                            if (e.life <= 0f) it.remove()
                        }

                        // Bird drift: far birds slower
                        birds.forEach { b ->
                            b.x += 0.0011f + 0.0032f * (1f - b.depth)
                            if (b.x > 1.25f) b.x = -0.25f
                        }
                    }
                } else {
                    lastFrame = System.nanoTime()
                }
                delay(16L)
            }
        }
    }

    fun setLatitude(lat: Double) { latitude = lat.coerceIn(-66.0, 66.0) }
    fun setFireIntensity(f: Float) { fireIntensity = f.coerceIn(0f, 1f) }
}
