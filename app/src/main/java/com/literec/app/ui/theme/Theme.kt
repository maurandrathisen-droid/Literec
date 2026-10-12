package com.literec.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val SurfaceVariantDark = Color(0xFF2A2A2A)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val RecordRed = Color(0xFFE53935)
val StopBlue = Color(0xFF1E88E5)
val StatusAmber = Color(0xFFFFB300)
val StatusGreen = Color(0xFF43A047)
val StatusRed = Color(0xFFEF5350)
val BorderColor = Color(0xFF3A3A3A)

private val DarkColors = darkColorScheme(
    primary = RecordRed,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun LiteRecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
