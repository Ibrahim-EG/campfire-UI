package com.campfire.canvas.livingpainting

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

class PaintingViewModel : ViewModel() {
    val sunElevation = mutableStateOf(15f) 
    val logsBurning = mutableStateOf(0)
    val isPaused = mutableStateOf(false)
    val cinematicTime = mutableStateOf(0L)

    init {
        viewModelScope.launch {
            var lastFrameTime = System.nanoTime()
            var lastElevationUpdate = 0L
            while (isActive) {
                if (!isPaused.value) {
                    val currentTime = System.nanoTime()
                    val deltaTime = currentTime - lastFrameTime
                    
                    // Cap rendering loop strictly to 24 frames per second
                    if (deltaTime >= 41_666_666L) {
                        cinematicTime.value = currentTime
                        lastFrameTime = currentTime
                        
                        // DEFUSED: Elevation now updates once every 5 seconds.
                        // Updating it 24x/second restarted the 90-second tween 24x/second,
                        // melting weak tablet CPUs in a recomposition furnace.
                        if (currentTime - lastElevationUpdate >= 5_000_000_000L) {
                            lastElevationUpdate = currentTime
                            val cycleProgress = (currentTime / 1_000_000_000L) % 600 / 600.0
                            sunElevation.value = (sin(cycleProgress * Math.PI * 2) * 90).toFloat()
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
