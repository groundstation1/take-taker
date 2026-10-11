package app.taketaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.log10

// ---- text ----------------------------------------------------------------

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = C.dim) =
    Text(text.uppercase(), modifier, style = T.label.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)

/** "01 SOURCE — 02 SETUP — 03 TAKE", current step lit. */
@Composable
fun StepStrip(step: Int, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Row(modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Glyph(G.BACK, C.text, 18.dp) }
            Spacer(Modifier.width(4.dp))
        }
        listOf("source", "setup", "take").forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.padding(horizontal = 8.dp).width(14.dp).height(1.dp).background(C.line2))
            Label("%02d %s".format(i + 1, s), color = if (i == step) C.text else C.faint)
        }
    }
}

// ---- buttons -------------------------------------------------------------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Color = C.text) {
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) accent else C.raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.name.copy(fontSize = T.body.fontSize, color = if (enabled) C.bg else C.faint))
    }
}

@Composable
fun GhostButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, color: Color = C.text, height: Dp = 48.dp,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (enabled) color.copy(alpha = 0.35f) else C.line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.name.copy(color = if (enabled) color else C.faint))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (selected) C.text else Color.Transparent)
            .border(1.dp, if (selected) C.text else C.line2, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.data.copy(color = if (selected) C.bg else C.dim, fontSize = T.label.fontSize * 1.2f)) }
}

@Composable
fun RoundButton(size: Dp, onClick: () -> Unit, enabled: Boolean = true, border: Color = C.line2, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(border, this.size.width / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        Box(Modifier.alpha(if (enabled) 1f else 0.35f)) { content() }
    }
}

// ---- glyphs --------------------------------------------------------------

enum class G { BACK, PLAY, STOP, RESTART, CHECK, LINK }

@Composable
fun Glyph(g: G, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) { drawGlyph(g, color) }
}

fun DrawScope.drawGlyph(g: G, color: Color) {
    val w = size.width
    val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    when (g) {
        G.BACK -> drawPath(Path().apply {
            moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.32f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f)
        }, color, style = s)
        G.PLAY -> drawPath(Path().apply {
            moveTo(w * 0.28f, w * 0.18f); lineTo(w * 0.84f, w * 0.5f); lineTo(w * 0.28f, w * 0.82f); close()
        }, color)
        G.STOP -> drawRoundRect(color, Offset(w * 0.22f, w * 0.22f), Size(w * 0.56f, w * 0.56f), CornerRadius(w * 0.06f))
        G.RESTART -> {
            drawArc(color, -60f, 300f, false, Offset(w * 0.2f, w * 0.2f), Size(w * 0.6f, w * 0.6f), style = s)
            drawPath(Path().apply {
                moveTo(w * 0.62f, w * 0.1f); lineTo(w * 0.8f, w * 0.26f); lineTo(w * 0.56f, w * 0.34f)
            }, color, style = s)
        }
        G.CHECK -> drawPath(Path().apply {
            moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.72f); lineTo(w * 0.8f, w * 0.3f)
        }, color, style = s)
        G.LINK -> {
            drawRoundRect(color, Offset(w * 0.08f, w * 0.32f), Size(w * 0.5f, w * 0.36f), CornerRadius(w * 0.18f), style = s)
            drawRoundRect(color, Offset(w * 0.42f, w * 0.32f), Size(w * 0.5f, w * 0.36f), CornerRadius(w * 0.18f), style = s)
        }
    }
}

// ---- meters --------------------------------------------------------------

/** Linear peak to 0..1 on a -60..0 dB scale. */
fun meterPos(peak: Float): Float =
    if (peak <= 0.001f) 0f else ((20f * log10(peak) + 60f) / 60f).coerceIn(0f, 1f)

@Composable
fun HMeter(level: () -> Float, clip: () -> Boolean, color: Color, tick: State<Long>, modifier: Modifier) {
    Canvas(modifier) {
        tick.value
        val r = CornerRadius(size.height / 2)
        drawRoundRect(C.line, cornerRadius = r)
        val p = meterPos(level())
        if (p > 0f) drawRoundRect(if (clip()) C.rec else color, size = Size(size.width * p, size.height), cornerRadius = r)
    }
}

@Composable
fun VMeter(level: () -> Float, clip: () -> Boolean, color: Color, tick: State<Long>, modifier: Modifier) {
    Canvas(modifier) {
        tick.value
        val w = size.width
        drawRoundRect(C.line, cornerRadius = CornerRadius(w / 2))
        val p = meterPos(level())
        if (p > 0f) {
            val h = size.height * p
            drawRoundRect(color, Offset(0f, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
        if (clip()) drawRoundRect(C.rec, size = Size(w, w * 1.5f), cornerRadius = CornerRadius(w / 2))
    }
}

// ---- fields --------------------------------------------------------------

@Composable
fun InlineField(
    value: String, onChange: (String) -> Unit, style: TextStyle,
    modifier: Modifier = Modifier, placeholder: String = "",
) {
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(C.rec),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = C.faint))
                inner()
            }
        },
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) =
    Box(modifier.height(1.dp).background(C.line))

@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, color: Color = C.text) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        Text(value, style = T.readout.copy(color = color), maxLines = 1)
    }
}

// ---- formatting ----------------------------------------------------------

/** 00:00.00 / 1:02:03.4 */
fun tc(sec: Double): String {
    val s = sec.coerceAtLeast(0.0)
    val h = (s / 3600).toInt()
    val m = ((s % 3600) / 60).toInt()
    val ss = s % 60
    return if (h > 0) "%d:%02d:%04.1f".format(Locale.US, h, m, ss) else "%02d:%05.2f".format(Locale.US, m, ss)
}

fun rateLabel(r: Int): String =
    if (r % 1000 == 0) "${r / 1000}k" else "%.1fk".format(Locale.US, r / 1000.0)
