package com.campfire.canvas.livingpainting

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(val name: String, val icon: Bitmap, val packageName: String)

// ---- THE LEATHER SATCHEL: entirely hand-drawn, hand-animated. Stitch by stitch. ----
@Composable
fun LeatherSatchel(context: Context, isExpanded: MutableState<Boolean>, modifier: Modifier = Modifier) {
    // Heavy leather easing: the bag settles open like a real flap
    val t by animateFloatAsState(if (isExpanded.value) 1f else 0f, tween(650, easing = FastOutSlowInEasing), "satchelMorph")
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(isExpanded.value) {
        if (isExpanded.value && apps.isEmpty()) apps = withContext(Dispatchers.Default) { loadApps(context) }
    }

    Box(modifier = modifier.padding(20.dp).width(320.dp).clickable { isExpanded.value = !isExpanded.value }) {
        // Frosted glass panel rising from the bag
        if (t > 0.02f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .height((84 + 476 * t).dp)
                    .fillMaxWidth()
                    .then(if (t > 0.5f) Modifier.blur((42 * t).dp) else Modifier)
                    .background(Color(0x99100C08).copy(alpha = 0.6f * t), RoundedCornerShape((40 - 12 * t).dp))
            ) {
                if (t > 0.6f) {
                    LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                        items(apps) { app ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it) } }
                            }) {
                                Box(Modifier.size(56.dp).background(Color(0x33FFFFFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Image(app.icon.asImageBitmap(), app.name, Modifier.size(40.dp), colorFilter = ColorFilter.tint(Color(0xF5FFFFFF)))
                                }
                                Text(app.name, color = Color(0xD9FFFFFF), fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        // The bag itself: drawn stitch by stitch, sinking away as the panel rises
        if (t < 0.98f) {
            Canvas(Modifier.align(Alignment.BottomEnd).size(118.dp, 92.dp).padding(2.dp)) {
                val w = size.width; val h = size.height
                val a = 1f - t
                // body: soft trapezoid in Raw Umber with vertical volume
                val body = Path().apply {
                    moveTo(w * 0.08f, h * 0.34f); lineTo(w * 0.92f, h * 0.34f)
                    quadTo(w * 0.99f, h * 0.36f, w * 0.97f, h * 0.52f); lineTo(w * 0.93f, h * 0.90f)
                    quadTo(w * 0.92f, h, w * 0.85f, h); lineTo(w * 0.15f, h)
                    quadTo(w * 0.08f, h, w * 0.07f, h * 0.90f); lineTo(w * 0.03f, h * 0.52f)
                    quadTo(w * 0.01f, h * 0.36f, w * 0.08f, h * 0.34f); close()
                }
                drawPath(body, Brush.verticalGradient(listOf(Color(0xFF6D4C41), Color(0xFF4E342E), Color(0xFF33221B)), startY = h * 0.3f, endY = h), alpha = a)
                // flap
                val flap = Path().apply {
                    moveTo(w * 0.05f, h * 0.36f); lineTo(w * 0.95f, h * 0.36f)
                    lineTo(w * 0.90f, h * 0.62f); quadTo(w * 0.5f, h * 0.74f, w * 0.10f, h * 0.62f); close()
                }
                drawPath(flap, Brush.verticalGradient(listOf(Color(0xFF795548), Color(0xFF4E342E)), startY = h * 0.3f, endY = h * 0.75f), alpha = a)
                // hand stitching: dashed pale-tan seam along the flap edge
                val stitch = Path().apply { moveTo(w * 0.10f, h * 0.60f); quadTo(w * 0.5f, h * 0.71f, w * 0.90f, h * 0.60f) }
                drawPath(stitch, Color(0x99D7CCC8), style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)), alpha = a)
                // brass buckle + prong
                drawRect(Color(0xFFC9A227), topLeft = Offset(w * 0.455f, h * 0.56f), size = Size(w * 0.09f, h * 0.12f), alpha = a)
                drawRect(Color(0xFF33221B), topLeft = Offset(w * 0.475f, h * 0.585f), size = Size(w * 0.05f, h * 0.07f), alpha = a)
                drawLine(Color(0xFFC9A227), Offset(w * 0.5f, h * 0.585f), Offset(w * 0.5f, h * 0.645f), strokeWidth = 2f, alpha = a)
                // single white impasto highlight
                drawOval(Color(0x2EFFFFFF), topLeft = Offset(w * 0.14f, h * 0.40f), size = Size(w * 0.30f, h * 0.10f), alpha = a)
                // handle arc
                val handle = Path().apply { moveTo(w * 0.38f, h * 0.34f); quadTo(w * 0.5f, h * 0.10f, w * 0.62f, h * 0.34f) }
                drawPath(handle, Color(0xFF33221B), style = Stroke(width = 5f), alpha = a)
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
    Box(Modifier.size(40.dp).background(Color(0x66000000), CircleShape).combinedClickable(onClick = onTap, onLongClick = onLong), contentAlignment = Alignment.Center) {
        Text("⚙", color = Color(0xCCFFFFFF), fontSize = 20.sp)
    }
}

@Composable
fun PreviewBadge() {
    Box(Modifier.background(Color(0x80000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Preview Mode — your usual launcher is still in charge", color = Color(0xCCFFFFFF), fontSize = 11.sp)
    }
}

data class SceneGrid(var horizon: Float, var lakeEnd: Float, var fireX: Float, var fireY: Float)

@Composable
fun CalibrationPanel(grid: SceneGrid, onChange: (SceneGrid) -> Unit, onApply: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Color(0xE614100C), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Column {
            Text("🎨 The Atelier — tune the composition", color = Color(0xFFFFCC80), fontSize = 13.sp)
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
