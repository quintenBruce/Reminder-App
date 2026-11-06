package com.qcbru.remind

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF285E59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EEE8),
    onPrimaryContainer = Color(0xFF123D39),
    secondary = Color(0xFF506864),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EFEB),
    onSecondaryContainer = Color(0xFF294541),
    tertiary = Color(0xFF855B46),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE5D7),
    onTertiaryContainer = Color(0xFF553521),
    background = Color(0xFFF6F8F5),
    onBackground = Color(0xFF182523),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF182523),
    surfaceVariant = Color(0xFFE8EFEB),
    onSurfaceVariant = Color(0xFF4B5B56),
    outline = Color(0xFF788A82),
    outlineVariant = Color(0xFFDDE5DF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D9CE),
    onPrimary = Color(0xFF0C3934),
    primaryContainer = Color(0xFF24544E),
    onPrimaryContainer = Color(0xFFD2F2E9),
    secondary = Color(0xFFB8D0C9),
    onSecondary = Color(0xFF233B36),
    secondaryContainer = Color(0xFF354D47),
    onSecondaryContainer = Color(0xFFDAEEE7),
    tertiary = Color(0xFFE8BBA1),
    onTertiary = Color(0xFF523622),
    tertiaryContainer = Color(0xFF684730),
    onTertiaryContainer = Color(0xFFFFE5D4),
    background = Color(0xFF111C1A),
    onBackground = Color(0xFFE1EDE7),
    surface = Color(0xFF192522),
    onSurface = Color(0xFFE1EDE7),
    surfaceVariant = Color(0xFF33433D),
    onSurfaceVariant = Color(0xFFC0CEC6),
    outline = Color(0xFF8A9D92),
    outlineVariant = Color(0xFF3B4B44),
)

private val RemindShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)

private val RemindTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun RemindTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = RemindShapes,
        typography = RemindTypography,
        content = content,
    )
}
