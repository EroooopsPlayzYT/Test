package com.eroooops.wifispeedtest.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Sky = Color(0xFF38BDF8)
val SkyDark = Color(0xFF0284C7)
val Mint = Color(0xFF34D399)
val Amber = Color(0xFFFBBF24)
val Ink = Color(0xFF0B1220)

private val DarkColors = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF00243A),
    secondary = Mint,
    onSecondary = Color(0xFF00281B),
    tertiary = Amber,
    background = Ink,
    surface = Color(0xFF111C2E),
    surfaceVariant = Color(0xFF1B2740),
)

private val LightColors = lightColorScheme(
    primary = SkyDark,
    secondary = Color(0xFF0F9D6E),
    tertiary = Color(0xFFB45309),
)

@Composable
fun WifiSpeedTestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
