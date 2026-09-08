package com.campfire.canvas.livingpainting

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(val name: String, val icon: Bitmap, val packageName: String)

@Composable
fun LeatherSatchel(context: Context, isExpanded: MutableState<Boolean>, modifier: Modifier = Modifier) {
    // Heavy leather flap easing: fast out, slow in — a bag settling open
    val height by animateDpAsState(if (isExpanded.value) 560.dp else 84.dp, tween(650, easing = FastOutSlowInEasing), "satchelH")
    val radius by animateDpAsState(if (isExpanded.value) 28.dp else 40.dp, tween(650, easing = FastOutSlowInEasing), "satchelR")
    val frost by animateDpAsState(if (isExpanded.value) 42.dp else 0.dp, tween(650), "satchelFrost")
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(isExpanded.value) {
        if (isExpanded.value && apps.isEmpty()) apps = withContext(Dispatchers.Default) { loadApps(context) }
    }
    Box(modifier = modifier.padding(20.dp).width(320.dp).height(height)
        .then(if (frost > 0.dp) Modifier.blur(frost) else Modifier)
        .background(if (isExpanded.value) Color(0x99100C08) else Color(0xFF4E342E), RoundedCornerShape(radius)) // Raw Umber impasto blob
        .clickable { isExpanded.value = !isExpanded.value }) {
        if (!isExpanded.value) {
            Box(Modifier.align(Alignment.TopEnd).padding(18.dp).size(18.dp).background(Color(0xF2FFFFFF), CircleShape)) // single white highlight
            Text("🧺", Modifier.align(Alignment.Center), fontSize = 26.sp)
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(apps) { app ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                        runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it) } }
                    }) {
                        Box(Modifier.size(56.dp).background(Color(0x33FFFFFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Image(app.icon.asImageBitmap(), app.name, Modifier.size(40.dp), colorFilter = ColorFilter.tint(Color(0xF5FFFFFF))) // white glyph contrast
                        }
                        Text(app.name, color = Color(0xD9FFFFFF), fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
        runCatching {
            val d = ri.activityInfo.loadIcon(pm)
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 108
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 108
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, c.width, c.height); d.draw(c)
            AppInfo(ri.loadLabel(pm).toString(), bmp, ri.activityInfo.packageName)
        }.getOrNull()
    }.take(30)
}

@Composable
fun BlackBoxCard(trace: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Color(0xE6212121), RoundedCornerShape(16.dp)).padding(16.dp)) {
        Column {
            Text("🕯️ The Black Box recovered a crash:", color = Color(0xFFFFCC80), fontSize = 13.sp)
            Text(trace, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            Text("TAP HERE TO DISMISS AND FORGET", color = Color(0xFF80CBC4), fontSize = 11.sp, modifier = Modifier.clickable { onDismiss() })
        }
    }
}

@Composable
fun EscapeGear(onTap: () -> Unit, onLong: () -> Unit) {
    Box(Modifier.size(40.dp).background(Color(0x66000000), CircleShape)
        .combinedClickable(onClick = onTap, onLongClick = onLong), contentAlignment = Alignment.Center) {
        Text("⚙", color = Color(0xCCFFFFFF), fontSize = 20.sp)
    }
}

@Composable
fun PreviewBadge() {
    Box(Modifier.background(Color(0x80000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Preview Mode — your usual launcher is still in charge", color = Color(0xCCFFFFFF), fontSize = 11.sp)
    }
}

// THE ATELIER: on-tablet calibration sliders, persisted, repaint on apply
data class SceneGrid(var horizon: Float, var lakeEnd: Float, var fireX: Float, var fireY: Float)

@Composable
fun CalibrationPanel(grid: SceneGrid, onChange: (SceneGrid) -> Unit, onApply: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Color(0xE614100C), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Column {
            Text("🎨 The Atelier — align the engine to your painting", color = Color(0xFFFFCC80), fontSize = 13.sp)
            SliderRow("Horizon", grid.horizon, 0.35f, 0.70f) { grid.horizon = it; onChange(grid) }
            SliderRow("Lake end", grid.lakeEnd, 0.55f, 0.85f) { grid.lakeEnd = it; onChange(grid) }
            SliderRow("Fire X", grid.fireX, 0.2f, 0.8f) { grid.fireX = it; onChange(grid) }
            SliderRow("Fire Y", grid.fireY, 0.6f, 0.95f) { grid.fireY = it; onChange(grid) }
            Text("APPLY & REPAINT WORLD", color = Color(0xFFA5D6A7), fontSize = 12.sp, modifier = Modifier.clickable { onApply() })
            Text("CLOSE", color = Color(0xFF90A4AE), fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, from: Float, to: Float, set: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).toInt()}%", color = Color(0xB3FFFFFF), fontSize = 10.sp)
        Slider(value = value, onValueChange = set, valueRange = from..to)
    }
}
