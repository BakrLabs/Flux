package dev.bakrlabs.flux

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val ground = Color(0xFF0A0C10)
    val card = Color(0xFF12161D)
    val border = Color(0xFF222936)
    val track = Color(0xFF1B212C)
    val text = Color(0xFFE8ECF2)
    val muted = Color(0xFF8A94A6)
    val accent = Color(0xFF38E1C6)
    val onAccent = Color(0xFF04130F)
    val danger = Color(0xFFFF7A7A)
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    background = Palette.ground,
    onBackground = Palette.text,
    surface = Palette.card,
    onSurface = Palette.text,
)

@Composable
fun FluxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
