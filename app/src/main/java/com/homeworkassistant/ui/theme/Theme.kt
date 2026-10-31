package com.homeworkassistant.ui.theme

import android.os.Build
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.homeworkassistant.domain.model.ThemeMode

@Composable
fun HomeworkTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val palette = if (dark) darkColorScheme(
        primary = Color(0xFFA8D6BD), onPrimary = Color(0xFF163C2C), primaryContainer = Color(0xFF294E3C), onPrimaryContainer = Color(0xFFD4EFDE),
        secondary = Color(0xFFBDCABD), onSecondary = Color(0xFF28352B), secondaryContainer = Color(0xFF35483B), onSecondaryContainer = Color(0xFFD6E9D8),
        background = Color(0xFF111914), onBackground = Color(0xFFE4EAE3), surface = Color(0xFF17201A), onSurface = Color(0xFFE4EAE3),
        surfaceVariant = Color(0xFF29342C), onSurfaceVariant = Color(0xFFB0BDB2), outline = Color(0xFF859488), outlineVariant = Color(0xFF354239),
        surfaceContainerLowest = Color(0xFF0D1510), surfaceContainerLow = Color(0xFF19241C), surfaceContainer = Color(0xFF202C23),
        surfaceContainerHigh = Color(0xFF29362D), surfaceContainerHighest = Color(0xFF344138), error = Color(0xFFFFB4A8)
    ) else lightColorScheme(
        primary = Color(0xFF285841), onPrimary = Color.White, primaryContainer = Color(0xFFDDEDE1), onPrimaryContainer = Color(0xFF214C36),
        secondary = Color(0xFF596B5D), onSecondary = Color.White, secondaryContainer = Color(0xFFE8EEE5), onSecondaryContainer = Color(0xFF354738),
        background = Color(0xFFF5F5EF), onBackground = Color(0xFF20372B), surface = Color(0xFFFFFEF9), onSurface = Color(0xFF20372B),
        surfaceVariant = Color(0xFFEAEDE4), onSurfaceVariant = Color(0xFF647165), outline = Color(0xFF859185), outlineVariant = Color(0xFFDEE4D9),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F2E9), surfaceContainer = Color(0xFFECEFE5),
        surfaceContainerHigh = Color(0xFFE5EADF), surfaceContainerHighest = Color(0xFFDFE5D9), error = Color(0xFFAD4839), errorContainer = Color(0xFFFFE9E2), onErrorContainer = Color(0xFF88382E)
    )
    // Wallpaper accents remain available; reading surfaces keep a consistent brand palette.
    val dynamic = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else null
    val scheme = dynamic?.let { palette.copy(tertiary = it.tertiary, onTertiary = it.onTertiary,
        tertiaryContainer = it.tertiaryContainer, onTertiaryContainer = it.onTertiaryContainer) } ?: palette
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val activity = view.context as? Activity
        activity?.let { WindowCompat.getInsetsController(it.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        } }
    }
    MaterialTheme(colorScheme = scheme, typography = StudyTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
private val StudyTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-.6).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp), bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 17.sp)
)
object SemanticColors {
    @Composable fun pending(): Color = if (MaterialTheme.colorScheme.background.luminanceCompat() < .4f) Color(0xFFFFB86A) else Color(0xFF935200)
    @Composable fun done(): Color = if (MaterialTheme.colorScheme.background.luminanceCompat() < .4f) Color(0xFF8CD6A3) else Color(0xFF226737)
    @Composable fun graded(): Color = if (MaterialTheme.colorScheme.background.luminanceCompat() < .4f) Color(0xFFA6C8FF) else Color(0xFF245CA6)
}
private fun Color.luminanceCompat() = (red + green + blue) / 3f
