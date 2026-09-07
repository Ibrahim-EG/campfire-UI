package com.campfire.canvas.livingpainting

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class Ember(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    var maxLife: Float,
    var size: Float
)

class PaintingViewModel : ViewModel() {
    val sunElevation = mutableStateOf(15f) 
    val logsBurning = mutableStateOf(0)
    val isPaused = mutableStateOf(false)
    val cinematicTime = mutableStateOf(0L)
    
    // Physics-driven Ember Particle System
    val embers = mutableStateListOf<Ember>()
    private val random = Random(42) // Seeded for deterministic beauty

    init {
        // 24 FPS Cinematic Render Cap & Particle Physics
        viewModelScope.launch {
            var lastFrameTime = System.nanoTime()
            var lastElevationUpdate = 0L
            
            while (isActive) {
                if (!isPaused.value) {
                    val currentTime = System.nanoTime()
                    val deltaTime = currentTime - lastFrameTime
                    
                    if (deltaTime >= 41_666_666L) { // 24 FPS
                        cinematicTime.value = currentTime
                        lastFrameTime = currentTime
                        
                        // Update Solar Elevation every 5 seconds to save CPU
                        if (currentTime - lastElevationUpdate >= 5_000_000_000L) {
                            lastElevationUpdate = currentTime
                            val cycleProgress = (currentTime / 1_000_000_000L) % 600 / 600.0
                            sunElevation.value = (sin(cycleProgress * Math.PI * 2) * 90).toFloat()
                        }
                        
                        // Update Ember Physics
                        val logs = logsBurning.value
                        if (logs > 0) {
                            // Spawn new embers based on fire intensity
                            if (embers.size < logs * 15 && random.nextFloat() < 0.3f * logs) {
                                embers.add(
                                    Ember(
                                        x = 0f, y = 0f, // Will be positioned relative to fire center in UI
                                        vx = (random.nextFloat() - 0.5f) * 2f,
                                        vy = -random.nextFloat() * 4f - 2f,
                                        life = 1f,
                                        maxLife = 1f,
                                        size = random.nextFloat() * 4f + 2f
                                    )
                                )
                            }
                        }
                        
                        // Age and move embers
                        val iterator = embers.iterator()
                        while (iterator.hasNext()) {
                            val ember = iterator.next()
                            ember.x += ember.vx
                            ember.y += ember.vy
                            ember.vy -= 0.05f // Upward draft acceleration
                            ember.vx += (random.nextFloat() - 0.5f) * 0.2f // Wind turbulence
                            ember.life -= 0.015f
                            if (ember.life <= 0) iterator.remove()
                        }
                    }
                } else {
                    lastFrameTime = System.nanoTime() 
                }
                delay(16L) 
            }
        }
    }
}
