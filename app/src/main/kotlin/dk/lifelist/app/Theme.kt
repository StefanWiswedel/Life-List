package dk.lifelist.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * BUILD.md §7, as code.
 *
 * Light only, and deliberately not `isSystemInDarkTheme()`-aware: the whole visual argument is
 * ink on paper, and there is no dark variant of paper. Both competitors are green-chromed, so
 * green is the one hue this app does not use.
 */
object Ink {
    val Bone = Color(0xFFF7F4ED)
    val Surface = Color(0xFFFDFCF9)
    val Ink = Color(0xFF22201C)
    val InkSoft = Color(0xFF5F5B54)
    val Rust = Color(0xFFA85331)
    val Sage = Color(0xFF7C8471)
    val Ochre = Color(0xFFB8892B)
    val Rule = Color(0xFFD8D2C4)
    val RuleStrong = Color(0xFFB9B2A1)
}

/**
 * Serif for names, monospace for anything numeric or label-like.
 *
 * `FontFamily.Serif` and `.Monospace` are the platform families rather than bundled files —
 * a real build should ship EB Garamond and IBM Plex Mono, but shipping font binaries is a
 * licensing and APK-size decision, not a layout one, and the layout is what wants review now.
 */
object Type {
    val displayName = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, fontWeight = FontWeight.Medium)
    val vernacular = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, color = Ink.InkSoft)
    val body = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Ink.Ink)
    val field = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp, color = Ink.InkSoft)
    val figure = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, color = Ink.Ink)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.InkSoft)
}

@Composable
fun LifeListTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme() // read and ignored, see above
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.Rust,
            background = Ink.Bone,
            surface = Ink.Surface,
            onBackground = Ink.Ink,
            onSurface = Ink.Ink,
        ),
        content = content,
    )
}
