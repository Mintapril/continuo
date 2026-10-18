package dev.dsh.pocket.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.dsh.pocket.R

/** Continuo「宣纸石青」tokens (see Continuo 品牌与图标设计指导). Stone-blue accent = "AI is executing" + primary actions. */
@Immutable data class PocketColors(
    val accent: Color, val accentPressed: Color, val accentSubtle: Color, val userBubble: Color, val codeBackground: Color,
    val hairline: Color, val muted: Color, val faint: Color, val link: Color, val success: Color,
    val warning: Color, val danger: Color, val ink: Color, val onInk: Color, val chrome: Color, val dark: Boolean,
)
private val LightTokens = PocketColors(
    accent = Color(0xFF2E6A8E), accentPressed = Color(0xFF265A79), accentSubtle = Color(0xFFDCE6EC), userBubble = Color(0xFFDCE6EC), codeBackground = Color(0xFFE2DED4),
    hairline = Color(0xFFD3CEC2), muted = Color(0xFF5B5F66), faint = Color(0xFF80848A), link = Color(0xFF2E6A8E), success = Color(0xFF36704F),
    warning = Color(0xFF8A6414), danger = Color(0xFFA2264B), ink = Color(0xFF22252A), onInk = Color(0xFFECE9E1), chrome = Color(0xFFE2DED4), dark = false,
)
private val DarkTokens = PocketColors(
    accent = Color(0xFF5B9BC0), accentPressed = Color(0xFF7BB0CE), accentSubtle = Color(0xFF22384A), userBubble = Color(0xFF22384A), codeBackground = Color(0xFF15171A),
    hairline = Color(0xFF3A3E45), muted = Color(0xFFA3A6AB), faint = Color(0xFF7C8087), link = Color(0xFF5B9BC0), success = Color(0xFF6FB08C),
    warning = Color(0xFFD9A84A), danger = Color(0xFFE07A95), ink = Color(0xFFECE9E1), onInk = Color(0xFF1B1D21), chrome = Color(0xFF15171A), dark = true,
)
val LocalPocketColors = staticCompositionLocalOf { LightTokens }

private val Light = lightColorScheme(
    primary = Color(0xFF2E6A8E), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6EC), onPrimaryContainer = Color(0xFF1D4560),
    secondary = Color(0xFF5B5F66), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2DED4), onSecondaryContainer = Color(0xFF22252A),
    tertiary = Color(0xFF36704F), onTertiary = Color.White,
    background = Color(0xFFECE9E1), onBackground = Color(0xFF22252A),
    surface = Color(0xFFF6F4EF), onSurface = Color(0xFF22252A),
    surfaceVariant = Color(0xFFE2DED4), onSurfaceVariant = Color(0xFF5B5F66),
    surfaceContainerLowest = Color(0xFFF6F4EF), surfaceContainerLow = Color(0xFFF1EFE8),
    surfaceContainer = Color(0xFFE2DED4), surfaceContainerHigh = Color(0xFFDCD8CD), surfaceContainerHighest = Color(0xFFD3CEC2),
    surfaceBright = Color(0xFFF6F4EF), surfaceDim = Color(0xFFE2DED4),
    outline = Color(0xFFB4AFA3), outlineVariant = Color(0xFFD3CEC2),
    error = Color(0xFFA2264B), onError = Color.White, errorContainer = Color(0xFFF3DDE3), onErrorContainer = Color(0xFF6E1630),
    inverseSurface = Color(0xFF22252A), inverseOnSurface = Color(0xFFECE9E1), scrim = Color(0xFF22252A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF5B9BC0), onPrimary = Color(0xFF1B1D21),
    primaryContainer = Color(0xFF22384A), onPrimaryContainer = Color(0xFFBBD6E6),
    secondary = Color(0xFFA3A6AB), onSecondary = Color(0xFF1B1D21),
    secondaryContainer = Color(0xFF2E3238), onSecondaryContainer = Color(0xFFECE9E1),
    tertiary = Color(0xFF6FB08C), onTertiary = Color(0xFF1B1D21),
    background = Color(0xFF1B1D21), onBackground = Color(0xFFECE9E1),
    surface = Color(0xFF25282D), onSurface = Color(0xFFECE9E1),
    surfaceVariant = Color(0xFF2E3238), onSurfaceVariant = Color(0xFFA3A6AB),
    surfaceContainerLowest = Color(0xFF15171A), surfaceContainerLow = Color(0xFF212429),
    surfaceContainer = Color(0xFF2A2D33), surfaceContainerHigh = Color(0xFF30343A), surfaceContainerHighest = Color(0xFF3A3E45),
    surfaceBright = Color(0xFF3A3E45), surfaceDim = Color(0xFF1B1D21),
    outline = Color(0xFF5A5F68), outlineVariant = Color(0xFF3A3E45),
    error = Color(0xFFE07A95), onError = Color(0xFF1B1D21), errorContainer = Color(0xFF4A2230), onErrorContainer = Color(0xFFF5C4D1),
    inverseSurface = Color(0xFFECE9E1), inverseOnSurface = Color(0xFF1B1D21), scrim = Color.Black,
)

/**
 * Continuo type: everyday UI = system sans (Roboto / Noto Sans SC, no bundled sans); code and logs = JetBrains Mono (SIL OFL);
 * serif only for brand moments (system serif / Noto Serif SC).
 */
@Immutable class PocketFonts(
    val serif: FontFamily, val sans: FontFamily, val mono: FontFamily,
    val serifTypeface: android.graphics.Typeface, val sansTypeface: android.graphics.Typeface, val monoTypeface: android.graphics.Typeface,
)
val LocalPocketFonts = staticCompositionLocalOf<PocketFonts> { error("PocketTheme missing") }

fun loadPocketFonts(context: Context): PocketFonts {
    val serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
    val sans = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    val mono = runCatching { context.resources.getFont(R.font.jetbrains_mono) }.getOrElse { android.graphics.Typeface.MONOSPACE }
    fun family(t: android.graphics.Typeface) = FontFamily(androidx.compose.ui.text.font.Typeface(t))
    return PocketFonts(family(serif), family(sans), family(mono), serif, sans, mono)
}
private fun typography(f: PocketFonts) = Typography(
    displaySmall = TextStyle(fontFamily = f.sans, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = f.sans, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = f.sans, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = f.sans, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = f.sans, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = f.sans, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = f.sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = f.sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = f.sans, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = f.sans, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = f.sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = f.sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = f.sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable fun PocketTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val fonts = remember(context) { loadPocketFonts(context.applicationContext) }
    val view = LocalView.current
    val scheme = if (dark) Dark else Light
    SideEffect {
        (view.context as? Activity)?.window?.let {
            it.decorView.setBackgroundColor(scheme.background.toArgb())
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalPocketColors provides if (dark) DarkTokens else LightTokens, LocalPocketFonts provides fonts) {
        MaterialTheme(colorScheme = scheme, typography = typography(fonts)) {
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
