package com.campfire.canvas.livingpainting

import androidx.compose.ui.graphics.Color
import kotlin.math.exp

// The oil painter's palette. Every hex is a physical pigment choice.
object Palette {
    val prussian      = Color(0xFF003153) // Prussian Blue: melancholic night depth
    val ultramarine   = Color(0xFF1A237E) // Ultramarine: receding atmospheric zenith
    val periwinkle    = Color(0xFF8C9EFF) // Periwinkle: where sky kisses horizon
    val ceruleanDeep  = Color(0xFF0277BD) // Deep cerulean: cold mysterious water body
    val tealBroken    = Color(0xFF00838F) // Teal: impressionist broken-color ripple
    val paleYellow    = Color(0xFFFFF59D) // Pale Yellow: captured sunlight fragments
    val softPink      = Color(0xFFF48FB1) // Soft Pink: dying ember reflections
    val sapGreen      = Color(0xFF33691E) // Sap Green: rich damp forest floor
    val sapDark       = Color(0xFF1B5E20) // Dark Sap: shadowed grass depth
    val sapLight      = Color(0xFF7CB342) // Light Sap: moonlit grass tips
    val rawUmber      = Color(0xFF4E342E) // Raw Umber: worn leather satchel impasto
    val burntSienna   = Color(0xFFE64A19) // Burnt Sienna: chiaroscuro fire bleed
    val gamboge       = Color(0xFFFFC107) // Gamboge Yellow: dancing flame body
    val madderLake    = Color(0xFFFF7043) // Madder Lake: dusk warmth wash
    val slateDeep     = Color(0xFF12202C) // Deep slate: mountain silhouette mass
    val mistBlue      = Color(0xFF90A4AE) // Mist blue: night celestial silver
    val ashGray       = Color(0xFF9E9E9E) // Ash: consumed log residue
    val charcoalBark  = Color(0xFF1A110B) // Charred bark: fire-consumed wood
    val barkBrown     = Color(0xFF3E2723) // Pine bark: fresh fuel logs
    val barkLight     = Color(0xFF5D4037) // Bark highlight: cylindrical volume
    val endGrain      = Color(0xFF8D6E63) // End grain: cut log face rings
    val flameWhite    = Color(0xFFFFF8E1) // Flame core: hottest quietest heart
    val nightIndigo   = Color(0xFF0A1030) // Night glaze: indigo multiply wash
    val duskMauve     = Color(0xFFBA68C8) // Twilight mauve: shadow softener
}

// Circadian Color Matrix: interpolates Night -> Dusk -> Day over the sun's elevation.
fun circadianMatrix(elevation: Float): FloatArray {
    val t = (elevation / 90f).coerceIn(-1f, 1f)
    // Night (Indigo Cozy): Prussian + Ultramarine, contrast dropped, only fire stays warm
    val night = floatArrayOf(
        0.35f, 0f, 0.10f, 0f, 0f,
        0f, 0.25f, 0.30f, 0f, 0f,
        0.10f, 0.10f, 0.85f, 0f, 25f,
        0f, 0f, 0f, 1f, 0f
    )
    // Dawn/Dusk (Golden Cozy): Madder Lake Red + Gamboge, shadows to soft Mauve
    val dusk = floatArrayOf(
        1.20f, 0.20f, 0f, 0f, 20f,
        0.20f, 0.90f, 0.10f, 0f, 10f,
        0f, 0f, 0.50f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    // Midday (Crisp Cozy): vibrant Cyan + boosted Yellow-Green foliage
    val day = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1.10f, 0.10f, 0f, 10f,
        0f, 0.10f, 1.10f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f
    )
    return if (t < 0) lerpMatrix(night, dusk, t + 1f) else lerpMatrix(dusk, day, t)
}

fun lerpMatrix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(20) { a[it] * (1f - t) + b[it] * t }

fun lerpColor(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = a.alpha + (b.alpha - a.alpha) * k
    )
}

// Gaussian bell: used for the dusk warmth peak around the horizon crossing
fun bell(x: Float, center: Float, width: Float): Float {
    val d = (x - center) / width
    return exp(-(d * d)).toFloat()
}
