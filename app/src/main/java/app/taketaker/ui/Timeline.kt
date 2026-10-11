package app.taketaker.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.taketaker.AppModel
import app.taketaker.R
import app.taketaker.Transport
import app.taketaker.ViewWindow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

private val RULER = 28.dp
private const val MIN_SPAN = 0.02

/** Tick hierarchy: 0.1 s, 1 s, 10 s, 1 min, 10 min, 1 h (in tenths of a second). */
private val TENTHS = longArrayOf(1, 10, 100, 600, 6000, 36000)
private val TICK_H = floatArrayOf(0.25f, 0.42f, 0.7f, 1f, 1f, 1f)

@Composable
fun Timeline(vm: AppModel, view: ViewWindow, trim: Boolean, tick: State<Long>, modifier: Modifier = Modifier) {
    val tracks = vm.tracks
    val engine = vm.engine
    Row(modifier) {
        Column(Modifier.width(92.dp).fillMaxHeight()) {
            Spacer(Modifier.height(RULER))
            tracks.forEachIndexed { i, t ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.toggleMute(t) }
                        .padding(start = 2.dp, end = 8.dp, top = 8.dp)
                        .alpha(if (t.muted) 0.4f else 1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(C.track(t.color)))
                        Spacer(Modifier.width(7.dp))
                        Text(t.name, style = T.name.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.padding(start = 14.dp)) {
                        Label(if (t.stereo) "st" else "mono", color = C.faint)
                        if (t.muted) Label(" · mute", color = C.rec)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            TimelineCanvas(vm, view, trim, tick)
            if (vm.take == null && vm.transport != Transport.REC) {
                Label("press ● to record", Modifier.align(Alignment.Center), color = C.faint)
            }
        }
        Column(Modifier.width(16.dp).fillMaxHeight().padding(start = 8.dp)) {
            Spacer(Modifier.height(RULER))
            tracks.forEach { t ->
                VMeter(
                    level = { engine?.let { e -> t.chans.maxOf { e.meters.getOrElse(it) { 0f } } } ?: 0f },
                    clip = { engine?.let { e -> t.chans.any { System.currentTimeMillis() - e.clips.getOrElse(it) { 0L } < 1500 } } ?: false },
                    color = C.track(t.color),
                    tick = tick,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TimelineCanvas(vm: AppModel, view: ViewWindow, trim: Boolean, tick: State<Long>) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val mono = remember { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) }
    val major = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono; textSize = with(density) { 10.sp.toPx() }; color = C.text.toArgb()
        }
    }
    val minor = remember { Paint(major).apply { color = C.dim.toArgb() } }
    val wave = remember { Paint().apply { strokeWidth = 1f; isAntiAlias = false } }
    val scratch = remember { Scratch() }
    var lastTap = remember { longArrayOf(0L) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(trim) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val v = view
                    val rate = (vm.take?.rate ?: vm.rate).toDouble()
                    val len = vm.take?.seconds ?: 0.0
                    fun xToFrame(x: Float) = ((v.start + x / w * v.span) * rate).toLong().coerceIn(0, vm.take?.frames ?: 0)
                    fun frameToX(f: Long) = ((f / rate - v.start) / v.span * w).toFloat()
                    fun clamp() {
                        v.span = v.span.coerceIn(MIN_SPAN, max(len * 1.5, 10.0))
                        v.start = v.start.coerceIn(-v.span * 0.05, max(0.0, len - v.span * 0.1))
                    }
                    var handle = 0
                    if (trim && vm.take != null) {
                        val dIn = abs(down.position.x - frameToX(vm.inF))
                        val dOut = abs(down.position.x - frameToX(vm.outF))
                        if (min(dIn, dOut) < 32.dp.toPx()) handle = if (dIn < dOut) 1 else 2
                    }
                    var moved = false
                    var multi = false
                    var travel = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            multi = true; handle = 0
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid()
                            if (zoom != 1f || pan != Offset.Zero) {
                                v.follow = false
                                val tc = v.start + c.x / w * v.span
                                v.span = (v.span / zoom).coerceIn(MIN_SPAN, max(len * 1.5, 10.0))
                                v.start = tc - c.x / w * v.span - pan.x / w * v.span
                                clamp()
                            }
                            ev.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = ev.changes.first { it.pressed }
                            val d = ch.positionChange()
                            travel += abs(d.x) + abs(d.y)
                            if (!moved && travel > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                when (handle) {
                                    1 -> vm.setIn(xToFrame(ch.position.x))
                                    2 -> vm.setOut(xToFrame(ch.position.x))
                                    else -> { v.follow = false; v.start -= d.x / w * v.span; clamp() }
                                }
                                ch.consume()
                            }
                        }
                    }
                    if (!moved && !multi) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap[0] < 300) v.follow = true
                        else if (vm.transport != Transport.REC && vm.take != null) vm.seek(xToFrame(down.position.x))
                        lastTap[0] = now
                    }
                }
            },
    ) {
        tick.value
        draw(vm, view, trim, major, minor, wave, scratch)
    }
}

private class Scratch {
    var pts = FloatArray(4 * 2048)
    val mm = FloatArray(2)
}

private fun shape(v: Float) = sign(v) * abs(v).pow(0.8f)

private fun DrawScope.draw(vm: AppModel, v: ViewWindow, trim: Boolean, major: Paint, minor: Paint, wave: Paint, s: Scratch) {
    val take = vm.take
    val rec = vm.transport == Transport.REC
    val len = take?.seconds ?: 0.0
    val rate = (take?.rate ?: vm.rate).toDouble()
    val w = size.width
    val h = size.height
    val rulerH = RULER.toPx()

    // ---- window
    if (v.follow) {
        when {
            take == null -> { v.span = 10.0; v.start = 0.0 }
            rec && !trim -> {
                v.span = (len * 1.15).coerceIn(10.0, 300.0)
                v.start = if (len > v.span * 0.92) len - v.span * 0.92 else 0.0
            }
            trim -> { v.span = max(len * 1.06, 0.5); v.start = -len * 0.03 }
            else -> { v.span = max(len * 1.04, 10.0); v.start = 0.0 }
        }
    } else if (vm.transport == Transport.PLAY || rec) {
        val ph = vm.playhead() / rate
        if (ph > v.start + v.span || ph < v.start) v.start = ph - v.span * 0.1 // page along
    }
    val pxPerSec = (w / v.span).toFloat()
    fun x(sec: Double) = ((sec - v.start) * pxPerSec).toFloat()

    // ---- ruler + grid
    drawLine(C.line, Offset(0f, rulerH), Offset(w, rulerH), 1.dp.toPx())
    val minPx = 7.dp.toPx(); val labelPx = 46.dp.toPx()
    var lf = TENTHS.indexOfFirst { it / 10.0 * pxPerSec >= minPx }.let { if (it < 0) TENTHS.lastIndex else it }
    val il = TENTHS.indexOfFirst { it / 10.0 * pxPerSec >= labelPx }.let { if (it < 0) TENTHS.lastIndex else it }
    if (lf > il) lf = il
    val step = TENTHS[lf] / 10.0
    val k0 = max(0L, floor(v.start / step).toLong())
    val k1 = ceil((v.start + v.span) / step).toLong()
    val tickZone = rulerH * 0.42f
    for (k in k0..k1) {
        val tt = k * TENTHS[lf]
        var rank = lf
        while (rank + 1 < TENTHS.size && tt % TENTHS[rank + 1] == 0L) rank++
        val xx = x(tt / 10.0)
        if (xx < -50 || xx > w + 1) continue
        val col = when { rank >= 3 -> C.text; rank == 2 -> C.dim; else -> C.faint }
        drawLine(col, Offset(xx, rulerH - tickZone * TICK_H[rank]), Offset(xx, rulerH), 1.dp.toPx())
        if (rank >= 3) drawLine(C.text.copy(alpha = 0.08f), Offset(xx, rulerH), Offset(xx, h), 1.dp.toPx())
        else if (rank == 2) drawLine(C.text.copy(alpha = 0.035f), Offset(xx, rulerH), Offset(xx, h), 1.dp.toPx())
        if (rank >= il) drawIntoCanvas {
            val label = tickLabel(tt, il)
            val p = if (rank >= 3) major else minor
            val lx = xx + 4.dp.toPx()
            if (lx + p.measureText(label) <= w) it.nativeCanvas.drawText(label, lx, rulerH * 0.42f, p)
        }
    }

    // ---- lanes
    val tracks = vm.tracks
    val n = max(1, tracks.size)
    val laneH = (h - rulerH) / n
    val pad = 3.dp.toPx()
    val xStart = max(0f, x(0.0)).toInt()
    val xEnd = min(w, x(len)).toInt()
    val samplesPerPx = rate / pxPerSec
    if (s.pts.size < (w.toInt() + 2) * 4) s.pts = FloatArray((w.toInt() + 2) * 4)
    tracks.forEachIndexed { i, t ->
        val top = rulerH + i * laneH + pad
        val lh = laneH - 2 * pad
        drawRoundRect(C.surface.copy(alpha = 0.7f), Offset(0f, top), Size(w, lh), CornerRadius(10.dp.toPx()))
        val subs = t.chans.size
        val subH = lh / subs
        wave.color = (if (t.muted) C.faint else C.track(t.color)).toArgb()
        for (si in 0 until subs) {
            val cy = top + subH * (si + 0.5f)
            drawLine(C.line, Offset(0f, cy), Offset(w, cy), 1f)
            val peaks = take?.peaks?.getOrNull(t.chans[si]) ?: continue
            val amp = subH * 0.46f
            var k = 0
            for (px in xStart until xEnd) {
                val s0 = ((v.start + px / pxPerSec) * rate).toLong()
                val s1 = s0 + max(1.0, samplesPerPx).toLong()
                if (!peaks.minMax(s0, s1, s.mm)) continue
                val y1 = cy - shape(s.mm[1]) * amp
                var y2 = cy - shape(s.mm[0]) * amp
                if (y2 - y1 < 1f) y2 = y1 + 1f
                s.pts[k++] = px.toFloat(); s.pts[k++] = y1; s.pts[k++] = px.toFloat(); s.pts[k++] = y2
            }
            if (k > 0) drawIntoCanvas { it.nativeCanvas.drawLines(s.pts, 0, k, wave) }
        }
    }

    // ---- in/out
    if (take != null && !rec && (trim || vm.trimmed)) {
        val xi = x(vm.inF / rate)
        val xo = x(vm.outF / rate)
        val shade = C.bg.copy(alpha = 0.66f)
        if (xi > 0) drawRect(shade, Offset(0f, rulerH), Size(xi, h - rulerH))
        if (xo < w) drawRect(shade, Offset(xo, rulerH), Size(w - xo, h - rulerH))
        for (xx in floatArrayOf(xi, xo)) {
            drawLine(C.text, Offset(xx, rulerH), Offset(xx, h), 1.5.dp.toPx())
            if (trim) {
                val tw = 18.dp.toPx(); val th = 36.dp.toPx()
                drawRoundRect(C.text, Offset(xx - tw / 2, h - th), Size(tw, th), CornerRadius(6.dp.toPx()))
                for (g in -1..1) {
                    val gy = h - th / 2 + g * 5.dp.toPx()
                    drawLine(C.bg, Offset(xx - 4.dp.toPx(), gy), Offset(xx + 4.dp.toPx(), gy), 1.5.dp.toPx())
                }
            }
        }
    }

    // ---- heads
    if (take != null || rec) {
        val f = vm.playhead()
        val xx = x(f / rate)
        when (vm.transport) {
            Transport.REC -> {
                drawLine(C.rec, Offset(xx, rulerH), Offset(xx, h), 2.dp.toPx())
                drawCircle(C.rec, 4.dp.toPx(), Offset(xx, rulerH))
            }
            Transport.PLAY -> {
                drawLine(C.text, Offset(xx, rulerH), Offset(xx, h), 2.dp.toPx())
                drawCircle(C.text, 4.dp.toPx(), Offset(xx, rulerH))
            }
            Transport.IDLE -> if (f > 0) {
                drawLine(C.text.copy(alpha = 0.45f), Offset(xx, rulerH), Offset(xx, h), 1.dp.toPx())
                drawCircle(C.text.copy(alpha = 0.45f), 3.dp.toPx(), Offset(xx, rulerH))
            }
        }
    }
}

private fun tickLabel(tenths: Long, labelLevel: Int): String {
    val totalSec = tenths / 10
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    return if (labelLevel == 0) "$base.${tenths % 10}" else base
}
