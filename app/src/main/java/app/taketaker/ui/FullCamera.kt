package app.taketaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.taketaker.AppModel
import app.taketaker.Transport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * Full-screen camera for framing: the whole frame (fit, so what you see is what's recorded),
 * the live mix as side-scrolling waveforms like the overlay export, timecode, and record.
 */
@Composable
fun FullCamera(vm: AppModel, tick: State<Long>, recButton: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = null) { }) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val view = remember {
            androidx.camera.view.PreviewView(ctx).apply {
                scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
                implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
            }
        }
        // claim the camera feed only once this view is on screen and the thumbnail is gone;
        // claiming it in the factory raced the thumbnail's teardown and left this view black
        androidx.compose.runtime.LaunchedEffect(view) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            vm.camera.preview.setSurfaceProvider(view.surfaceProvider)
        }
        val footage = vm.showFootage()
        if (footage != null) FootageView(vm, footage, Modifier.fillMaxSize()) // playback / scrub: what was filmed
        else AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vm.session.ifBlank { "take" }, style = T.title.copy(color = Color.White))
                    val rate = (vm.take?.rate ?: vm.rate).toDouble()
                    tick.value
                    Text(tc(vm.playhead() / rate), style = T.readout.copy(color = Color.White, fontSize = 26.sp))
                }
                if (vm.transport == Transport.REC) Label("● rec", Modifier.padding(end = 14.dp), color = C.rec)
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                        .clickable { vm.cameraFull = false },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", style = T.name.copy(color = Color.White, fontSize = 18.sp)) }
            }
            Spacer(Modifier.weight(1f))
            ScrollWaves(vm, tick, Modifier.fillMaxWidth().fillMaxHeight(0.38f))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { recButton() }
        }
    }
}

/** Live mix as side-scrolling envelopes: right edge is now, [WINDOW] seconds across. */
@Composable
private fun ScrollWaves(vm: AppModel, tick: State<Long>, modifier: Modifier) {
    val path = remember { Path() }
    val mm = remember { FloatArray(2) }
    Canvas(modifier) {
        tick.value
        val take = vm.take ?: return@Canvas
        val tracks = vm.tracks
        if (tracks.isEmpty()) return@Canvas
        val at = if (vm.transport == Transport.IDLE) take.frames else vm.playhead()
        val laneH = size.height / tracks.size
        val perPx = WINDOW * take.rate / size.width
        val colPx = 2.dp.toPx()
        val cs = (colPx * perPx).toLong().coerceAtLeast(1) // grid pinned to time: no shimmer while scrolling
        tracks.forEachIndexed { ti, t ->
            val subH = laneH / t.chans.size
            t.chans.forEachIndexed { si, ch ->
                drawEnvelope(
                    take.peaks[ch], at, cs, perPx.toFloat(), ti * laneH + subH * (si + 0.5f), subH * 0.42f,
                    if (t.muted) C.faint else vivid(C.track(t.color)), path, mm,
                )
            }
        }
    }
}

private const val WINDOW = 8.0

private fun DrawScope.drawEnvelope(
    peaks: app.taketaker.audio.PeakStore, at: Long, cs: Long, perPx: Float,
    cy: Float, amp: Float, color: Color, path: Path, mm: FloatArray,
) {
    path.reset()
    val tops = ArrayList<Pair<Float, Float>>()
    val bots = ArrayList<Float>()
    var k = at / cs
    while (true) {
        val s0 = k * cs
        val s1 = min(s0 + cs, at)
        if (s1 <= 0) break
        val x = size.width - (at - (s0 + s1) / 2) / perPx
        if (x < -4f) break
        var hi = 0f; var lo = 0f
        if (peaks.minMax(max(s0, 0), s1, mm)) { hi = shape(mm[1]); lo = shape(mm[0]) }
        tops += x to (cy - max(hi, 0f) * amp - 1f)
        bots += cy - min(lo, 0f) * amp + 1f
        k--
    }
    if (tops.size < 2) return
    path.moveTo(tops[0].first, tops[0].second)
    for (q in 1 until tops.size) path.lineTo(tops[q].first, tops[q].second)
    for (q in tops.indices.reversed()) path.lineTo(tops[q].first, bots[q])
    path.close()
    drawPath(path, color)
}

private fun shape(v: Float) = sign(v) * abs(v).pow(0.8f)

/** The soft UI palette, pushed to full value and more saturation so it reads over footage. */
private fun vivid(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    hsv[1] = (hsv[1] * 1.9f).coerceAtMost(0.85f); hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
