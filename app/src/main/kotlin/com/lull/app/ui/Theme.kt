package com.lull.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lull.bedjetkit.Mode

/** Port of `enum Theme` in Theme.swift. Dark only: the app is used at night, next to a bed. */
object LullColors {
    val background = Color.Black
    val surface = Color(red = 0.055f, green = 0.059f, blue = 0.071f)
    val raised = Color(red = 0.098f, green = 0.102f, blue = 0.118f)
    val hairline = Color.White.copy(alpha = 0.08f)
    val secondary = Color.White.copy(alpha = 0.55f)
    val tertiary = Color.White.copy(alpha = 0.32f)

    val cool = Color(red = 0.30f, green = 0.64f, blue = 1.0f)
    val deepCool = Color(red = 0.17f, green = 0.36f, blue = 1.0f)
    val warm = Color(red = 1.0f, green = 0.54f, blue = 0.24f)
    val hot = Color(red = 1.0f, green = 0.30f, blue = 0.18f)
    val neutral = Color(red = 0.56f, green = 0.56f, blue = 0.60f)

    /** `Mode.extendedHeat.tint` in Theme.swift. */
    val extendedHeat = Color(red = 1.0f, green = 0.70f, blue = 0.25f)
    /** `Mode.dry.tint` in Theme.swift. */
    val dry = Color(red = 0.35f, green = 0.84f, blue = 0.77f)

    /** SwiftUI's `Color.green` in dark mode: the connected dot and the DeviceSheet toggles (`.tint(.green)`). */
    val connected = Color(red = 0.188f, green = 0.820f, blue = 0.345f)
    /** SwiftUI's destructive red in dark mode: "Remove Phase", "Delete", "Forget This BedJet". */
    val destructive = Color(red = 1.0f, green = 0.271f, blue = 0.227f)
}

val Mode.tint: Color
    get() = when (this) {
        Mode.COOL -> LullColors.cool
        Mode.HEAT -> LullColors.warm
        Mode.TURBO -> LullColors.hot
        Mode.EXTENDED_HEAT -> LullColors.extendedHeat
        Mode.DRY -> LullColors.dry
        Mode.STANDBY, Mode.WAIT -> LullColors.neutral
    }

/** Material stand-ins for the SF Symbols in `Mode.symbol` (PORT_PLAN §5.6). */
val Mode.icon: ImageVector
    get() = when (this) {
        Mode.STANDBY -> Icons.Outlined.PowerSettingsNew
        Mode.HEAT -> Icons.Outlined.LocalFireDepartment
        Mode.TURBO -> Icons.Filled.Bolt
        Mode.EXTENDED_HEAT -> Icons.Outlined.WbSunny
        Mode.COOL -> Icons.Outlined.AcUnit
        Mode.DRY -> Icons.Outlined.Air
        Mode.WAIT -> Icons.Outlined.Pause
    }

/** SwiftUI's `.monospacedDigit()`: tabular figures, so numbers don't jitter as they change. */
val MonospacedDigits = TextStyle(fontFeatureSettings = "tnum")

// No line heights are set: screens size text ad hoc (as the Swift does with `.font(.system(size:))`), and a fixed
// line height inherited from the theme would clip a 60 sp number. Letter spacing is 0 like SF, except the eyebrow.
private fun style(size: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = tracking.sp,
)

private val LullTypography = Typography(
    displaySmall = style(34, FontWeight.Bold),          // Screen large titles
    headlineMedium = style(26, FontWeight.SemiBold),    // card titles ("Warm Welcome")
    headlineSmall = style(20, FontWeight.SemiBold),     // AlertDialog titles
    titleLarge = style(22, FontWeight.SemiBold),        // "No schedules yet", "Phase"
    titleMedium = style(17, FontWeight.SemiBold),
    titleSmall = style(15, FontWeight.Medium),
    bodyLarge = style(15, FontWeight.Normal),           // default Text style
    bodyMedium = style(15, FontWeight.Normal),          // AlertDialog message
    bodySmall = style(13, FontWeight.Normal),
    labelLarge = style(15, FontWeight.Medium),          // TextButton, menu items
    labelMedium = style(12, FontWeight.Medium),         // navigation bar labels
    labelSmall = style(11, FontWeight.SemiBold, 1.6f),  // Eyebrow
)

private val LullColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = LullColors.raised,
    onPrimaryContainer = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = LullColors.raised,
    onSecondaryContainer = Color.White,
    tertiary = LullColors.cool,
    onTertiary = Color.Black,
    tertiaryContainer = LullColors.raised,
    onTertiaryContainer = Color.White,
    background = LullColors.background,
    onBackground = Color.White,
    surface = LullColors.surface,
    onSurface = Color.White,
    surfaceVariant = LullColors.raised,
    onSurfaceVariant = LullColors.secondary,
    // No tonal-elevation tint: surfaces stay the exact Theme.swift colours.
    surfaceTint = Color.Transparent,
    outline = Color.White.copy(alpha = 0.2f),
    outlineVariant = LullColors.hairline,
    error = LullColors.destructive,
    onError = Color.White,
    surfaceBright = LullColors.raised,
    surfaceDim = LullColors.background,
    surfaceContainerLowest = LullColors.background,
    surfaceContainerLow = LullColors.surface,
    surfaceContainer = LullColors.raised,
    surfaceContainerHigh = LullColors.raised,
    surfaceContainerHighest = LullColors.raised,
)

@Composable
fun LullTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LullColorScheme, typography = LullTypography) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}
