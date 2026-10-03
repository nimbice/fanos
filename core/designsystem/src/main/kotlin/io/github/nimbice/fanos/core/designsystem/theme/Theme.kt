package io.github.nimbice.fanos.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.nimbice.fanos.core.model.ThemeMode

/** Lamplight: warm amber on dark brown, like a page under a desk lamp. */
private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFF2C46D),
        onPrimary = Color(0xFF3A2A0B),
        primaryContainer = Color(0xFF5A4214),
        onPrimaryContainer = Color(0xFFFFDFA3),
        secondary = Color(0xFFD8C4A0),
        onSecondary = Color(0xFF3B2F1A),
        secondaryContainer = Color(0xFF52452F),
        onSecondaryContainer = Color(0xFFF5E0BB),
        tertiary = Color(0xFFA8CFB0),
        onTertiary = Color(0xFF133721),
        tertiaryContainer = Color(0xFF2B4E37),
        onTertiaryContainer = Color(0xFFC4EBCB),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        background = Color(0xFF15120F),
        onBackground = Color(0xFFECE1D6),
        surface = Color(0xFF15120F),
        onSurface = Color(0xFFECE1D6),
        surfaceVariant = Color(0xFF4D4539),
        onSurfaceVariant = Color(0xFFD0C5B4),
        surfaceContainerLowest = Color(0xFF100D0A),
        surfaceContainerLow = Color(0xFF1D1915),
        surfaceContainer = Color(0xFF221D19),
        surfaceContainerHigh = Color(0xFF2C2723),
        surfaceContainerHighest = Color(0xFF37322D),
        outline = Color(0xFF998F80),
        outlineVariant = Color(0xFF4D4539),
    )

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF7A5A00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDFA3),
        onPrimaryContainer = Color(0xFF261900),
        secondary = Color(0xFF6B5D3F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF5E0BB),
        onSecondaryContainer = Color(0xFF241A04),
        tertiary = Color(0xFF45664E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC6ECCD),
        onTertiaryContainer = Color(0xFF01210E),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        background = Color(0xFFFFF8F2),
        onBackground = Color(0xFF1F1B16),
        surface = Color(0xFFFFF8F2),
        onSurface = Color(0xFF1F1B16),
        surfaceVariant = Color(0xFFEDE1CF),
        onSurfaceVariant = Color(0xFF4D4539),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFBF2E8),
        surfaceContainer = Color(0xFFF6EDE2),
        surfaceContainerHigh = Color(0xFFF0E7DC),
        surfaceContainerHighest = Color(0xFFEAE1D7),
        outline = Color(0xFF7F7667),
        outlineVariant = Color(0xFFD0C5B4),
    )

@Composable
fun FanosTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            // Like the reader's pages: the app works that out and asks for Light or Dark; asked for it here, the system's.
            ThemeMode.System, ThemeMode.Reader -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    MaterialTheme(colorScheme = fanosColors(dark, dynamicColor), content = content)
}

/** The app's colours, [dark] or light: the wallpaper's with [dynamicColor] (from Android 12), else the app's own. */
@Composable
fun fanosColors(dark: Boolean, dynamicColor: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
}
