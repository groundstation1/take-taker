package app.taketaker.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.taketaker.R

object C {
    val bg = Color(0xFF0C0C0D)
    val surface = Color(0xFF141416)
    val raised = Color(0xFF1B1B1F)
    val line = Color(0xFF26262B)
    val line2 = Color(0xFF34343A)
    val text = Color(0xFFECEAE4)
    val dim = Color(0xFF8E8D93)
    val faint = Color(0xFF56555C)
    val rec = Color(0xFFFF5A36)

    /** Muted, distinguishable lane colors. */
    val tracks = listOf(
        Color(0xFFE0B88E), // sand
        Color(0xFF9CC4A0), // sage
        Color(0xFF8FB5DE), // sky
        Color(0xFFBBA5DE), // lilac
        Color(0xFFDE9C9C), // rose
        Color(0xFFCEC98A), // olive
        Color(0xFF8ED2CB), // teal
        Color(0xFFDDA9CA), // orchid
    )

    fun track(i: Int) = tracks[i % tracks.size]
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Grotesk = FontFamily(
    variable(R.font.space_grotesk, 300),
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 700),
)

val Mono = FontFamily(
    variable(R.font.jetbrains_mono, 300),
    variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 500),
    variable(R.font.jetbrains_mono, 700),
)

object T {
    val display = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.W500, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, color = C.text)
    val title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.W500, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp, color = C.text)
    val body = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 22.sp, color = C.text)
    val name = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp, color = C.text)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W500, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = C.dim)
    val data = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp, color = C.text)
    val readout = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W400, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp, color = C.text)
    val timecode = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W300, fontSize = 50.sp, lineHeight = 54.sp, letterSpacing = (-2).sp, color = C.text)
}

@Composable
fun TakeTakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.bg, surface = C.surface, primary = C.text, onPrimary = C.bg,
            onBackground = C.text, onSurface = C.text,
        ),
    ) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(C.text, C.text.copy(alpha = 0.25f)),
            content = content,
        )
    }
}
