package com.phonerobot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FB),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFF9DCAFF),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DCAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD7BDE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C20),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF191C20),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFEFF0F7),
    inverseOnSurface = Color(0xFF2E3135),
    inversePrimary = Color(0xFF0061A4),
)

@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightStatusColors = StatusColors(
    success = Color(0xFF1E7D3C),
    successContainer = Color(0xFFA9F2B1),
    onSuccessContainer = Color(0xFF00210A),
    warning = Color(0xFF8A5000),
    warningContainer = Color(0xFFFFDCBE),
    onWarningContainer = Color(0xFF2D1600),
)

private val DarkStatusColors = StatusColors(
    success = Color(0xFF71D684),
    successContainer = Color(0xFF005321),
    onSuccessContainer = Color(0xFF87F99A),
    warning = Color(0xFFFFB86B),
    warningContainer = Color(0xFF663D00),
    onWarningContainer = Color(0xFFFFDCBE),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

object PhoneRobotThemeDefaults {
    val statusColors: StatusColors
        @Composable get() = LocalStatusColors.current
}

private val PhoneRobotShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun PhoneRobotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colors,
            shapes = PhoneRobotShapes,
            content = content,
        )
    }
}
