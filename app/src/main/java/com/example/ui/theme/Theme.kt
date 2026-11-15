package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CrimsonAccent,
    onPrimary = Color.White,
    primaryContainer = CrimsonDark,
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = WarmAmber,
    onSecondary = Color(0xFF281800),
    secondaryContainer = WarmAmberDark,
    onSecondaryContainer = Color(0xFFFFE0A8),
    tertiary = CrimsonGlow,
    onTertiary = Color(0xFF3E000A),
    background = ObsidianBg,
    onBackground = InkWhite,
    surface = ObsidianSurface,
    onSurface = InkWhite,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = InkMuted,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF242938),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFD91E36),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADE),
    onPrimaryContainer = Color(0xFF41000B),
    secondary = Color(0xFFC77800),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5BE),
    onSecondaryContainer = Color(0xFF331C00),
    tertiary = CrimsonAccent,
    onTertiary = Color.White,
    background = DaylightBg,
    onBackground = InkDark,
    surface = DaylightSurface,
    onSurface = InkDark,
    surfaceVariant = DaylightSurfaceHigh,
    onSurfaceVariant = InkDarkMuted,
    surfaceContainerLow = DaylightSurface,
    surfaceContainer = DaylightSurfaceHigh,
    surfaceContainerHigh = DaylightSurfaceHighest,
    outline = DaylightOutline,
    outlineVariant = Color(0xFFE4E8F0),
)

val XemTubeShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun XemTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = XemTubeShapes,
        content = content,
    )
}
