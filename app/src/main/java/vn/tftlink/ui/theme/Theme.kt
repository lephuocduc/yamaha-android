package vn.tftlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = YamahaCyan,
    onPrimary = Color.Black,
    primaryContainer = YamahaBlue,
    onPrimaryContainer = Color.White,
    secondary = YamahaCyan,
    onSecondary = Color.Black,
    secondaryContainer = DarkSurfaceHighlight,
    onSecondaryContainer = YamahaCyan,
    tertiary = NeonGreen,
    onTertiary = Color.Black,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = DarkSurfaceBorder,
    error = NeonRed,
    onError = Color.Black
)

@Composable
fun NConnectTheme(
    darkTheme: Boolean = true, // Luôn mặc định nền tối phong cách Yamaha Dashboard
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
