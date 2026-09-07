package com.campfire.canvas.livingpainting

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PaintingViewModel : ViewModel() {
    // The soul of the painting: tracks the sun's elevation for the Circadian Color Matrix
    val sunElevation = mutableStateOf(15f) 
    
    // Interactive state for the cozy mechanic
    val logsBurning = mutableStateOf(0)
    
    // Battery Sanctity: completely freezes the Canvas drawing thread when the user opens another app
    val isPaused = mutableStateOf(false)
    
    // Time accumulator strictly capped to 24 frames per second (cinematic standard)
    val cinematicTime = mutableStateOf(0L)

    init {
        viewModelScope.launch {
            var lastFrameTime = System.nanoTime()
            while (isActive) {
                if (!isPaused.value) {
                    val currentTime = System.nanoTime()
                    val deltaTime = currentTime - lastFrameTime
                    
                    // Cap rendering loop strictly to 24 frames per second (41.66ms per frame)
                    // This prevents GPU thrashing and preserves battery life
                    if (deltaTime >= 41_666_666L) {
                        cinematicTime.value = currentTime
                        lastFrameTime = currentTime
                        
                        // Simulate a slow, breathing day/night cycle (1 full day = 10 minutes for demo purposes)
                        // In a production environment, this would be tied to actual GPS solar elevation
                        val cycleProgress = (currentTime / 1_000_000_000L) % 600 / 600.0
                        sunElevation.value = (sin(cycleProgress * Math.PI * 2) * 90).toFloat()
                    }
                } else {
                    lastFrameTime = System.nanoTime() // Reset baseline when paused to prevent time jumps
                }
                delay(16L) // Check loop runs at 60Hz but only updates state at 24Hz
            }
        }
    }
}
