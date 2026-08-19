package dk.lifelist.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The theme, as a *Material 3 theme* rather than a private palette.
 *
 * The first version defined its own colours and its own buttons and then handed neither to
 * `MaterialTheme`, so every M3 component that did get used — `Slider`, `Text` — drew from
 * defaults that had nothing to do with the rest of the screen, and everything else was
 * hand-rolled `Box`es. That is the specific reason it "felt like a web app": Android's own
 * chrome was never involved, so none of the platform's shared vocabulary showed up. Ripples
 * were missing, elevation was missing, touch targets were whatever padding happened to be.
 *
 * Same palette, then, but expressed as a real `ColorScheme`, so a `Button` is a Material
 * button that happens to be rust, and it behaves like every other button on the phone.
 *
 * Light only, on purpose: the visual argument is ink on paper and there is no dark paper.
 * Dynamic colour is likewise refused — `ColorScheme.fromSeed` off the wallpaper would make
 * this look like every other Android 12+ app, and the warm palette *is* the identity.
 */
object Warm {
    val Paper = Color(0xFFFBF7F0)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF2A2621)
    val Soft = Color(0xFF7A736A)
    val Rust = Color(0xFFC2603A)
    val RustDeep = Color(0xFF8F3F22)
    val RustPale = Color(0xFFF6E2D9)
    val Ochre = Color(0xFFD9A339)

    /**
     * The hedge colour, and the only thing it is ever used for.
     *
     * Ochre reads well as a bar or a ring and badly as 12sp text on paper, so the darker
     * Amber carries any word that has to be read. One meaning, two weights — not two colours
     * competing for the same job, which is part of why the old screen felt busy.
     */
    val Amber = Color(0xFFC98A1E)
    val OchrePale = Color(0xFFF8EBCF)
    val Sage = Color(0xFF7C8471)
    val Moss = Color(0xFF4E6151)
    val Line = Color(0xFFEDE6DA)
    val Sand = Color(0xFFF3EADC)

    /** Green when the answer is a species; amber when the app stopped short on purpose. */
    fun ringColour(kind: dk.lifelist.core.AnswerKind): Color = when (kind) {
        dk.lifelist.core.AnswerKind.LEAF -> Sage
        dk.lifelist.core.AnswerKind.INDETERMINATE, dk.lifelist.core.AnswerKind.HIGHER_RANK -> Ochre
        dk.lifelist.core.AnswerKind.UNIDENTIFIED -> Soft
    }
}

private val Scheme = lightColorScheme(
    primary = Warm.Rust,
    onPrimary = Color.White,
    primaryContainer = Warm.RustPale,
    onPrimaryContainer = Warm.RustDeep,
    secondary = Warm.Moss,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E7DD),
    onSecondaryContainer = Color(0xFF2E3A2F),
    tertiary = Warm.Ochre,
    onTertiary = Color(0xFF3A2C08),
    tertiaryContainer = Warm.OchrePale,
    onTertiaryContainer = Color(0xFF4A3708),
    background = Warm.Paper,
    onBackground = Warm.Ink,
    surface = Warm.Paper,
    onSurface = Warm.Ink,
    surfaceVariant = Warm.Sand,
    onSurfaceVariant = Warm.Soft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFDFAF5),
    surfaceContainer = Color(0xFFF6F1E8),
    surfaceContainerHigh = Color(0xFFF1EADF),
    surfaceContainerHighest = Color(0xFFEBE3D6),
    outline = Color(0xFFC9BFAF),
    outlineVariant = Warm.Line,
    error = Color(0xFF9B2C1E),
    errorContainer = Color(0xFFF9E0DB),
    onErrorContainer = Color(0xFF5C160C),
)

/**
 * Serif for names, sans for everything that is not a name.
 *
 * A binomial is set in a serif in every field guide ever printed, and the app leans on that;
 * running the interface chrome in the same face would just look like a website from 2006.
 * Platform families rather than bundled files — shipping EB Garamond is an APK-size and
 * licensing decision, not a layout one.
 */
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val WarmTypography = Typography().let { d ->
    d.copy(
        displaySmall = d.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = d.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(
            fontFamily = Serif, fontSize = 27.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.SemiBold, color = Warm.Ink,
        ),
        headlineSmall = d.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = d.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.copy(fontFamily = Sans),
        bodyLarge = d.bodyLarge.copy(fontFamily = Sans, lineHeight = 24.sp),
        bodyMedium = d.bodyMedium.copy(fontFamily = Sans),
        bodySmall = d.bodySmall.copy(fontFamily = Sans),
        labelLarge = d.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        labelMedium = d.labelMedium.copy(fontFamily = Sans),
        labelSmall = d.labelSmall.copy(fontFamily = Sans, letterSpacing = 0.8.sp),
    )
}

/** The Latin name, wherever it appears. Serif, quieter than the common name above it. */
val LatinStyle = TextStyle(fontFamily = Serif, fontSize = 15.sp, color = Warm.Soft)

private val WarmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LifeListTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = WarmTypography,
        shapes = WarmShapes,
        content = content,
    )
}
