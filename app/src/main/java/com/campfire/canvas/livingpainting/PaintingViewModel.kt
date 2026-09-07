package com.campfire.canvas.livingpainting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

class PaintingViewModel : ViewModel() {
    val sunElevation = mutableStateOf(15f) 
    val logsBurning = mutableStateOf(0)
    val isPaused = mutableStateOf(false)
    val cinematicTime = mutableStateOf(0L)

    init {
        viewModelScope.launch {
            var lastFrameTime = System.nanoTime()
            while (isActive) {
                if (!isPaused.value) {
                    val currentTime = System.nanoTime()
                    val deltaTime = currentTime - lastFrameTime
                    if (deltaTime >= 41_666_666L) {
                        cinematicTime.value = currentTime
                        lastFrameTime = currentTime
                    }
                } else {
                    lastFrameTime = System.nanoTime() 
                }
                delay(16L) 
            }
        }
        
        // Real-Time Solar Tracker
        viewModelScope.launch {
            while (isActive) {
                val cal = Calendar.getInstance()
                val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
                // Sun arc: 6AM = 0deg, 12PM = 90deg, 6PM = 0deg, 12AM = -90deg
                val angle = (hour - 6f) / 12f * Math.PI
                sunElevation.value = (sin(angle) * 90).toFloat()
                delay(60_000) // Update every minute
            }
        }
    }
}
