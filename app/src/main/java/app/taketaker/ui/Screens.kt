package app.taketaker.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.taketaker.AppModel
import app.taketaker.ExportState
import app.taketaker.Row as SetupRow
import app.taketaker.Transport
import app.taketaker.audio.MicMode
import app.taketaker.audio.SaveMode
import app.taketaker.audio.Source
import kotlinx.coroutines.delay

private val GUTTER = 20.dp

// ============================================================================
// 01 source
// ============================================================================

@Composable
fun SourcesScreen(vm: AppModel, granted: Boolean, requestPermission: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = GUTTER)) {
        StepStrip(0)
        Spacer(Modifier.height(28.dp))
        Text("take taker", style = T.display)
        Spacer(Modifier.height(6.dp))
        Text("pick what to record from.", style = T.body.copy(color = C.dim))
        Spacer(Modifier.height(24.dp))

        if (!granted) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "android files every audio input — usb interfaces included — under microphone access.",
                    style = T.body.copy(color = C.dim),
                )
                GhostButton("grant access", requestPermission)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.sources, key = { it.key }) { s ->
                    SourceCard(
                        s = vm.effective(s),
                        selected = s.key in vm.selected,
                        onClick = { vm.toggleSource(s.key) },
                        onMode = { raw -> vm.setRaw(s, raw) },
                        onMic = { m -> vm.setMic(s, m) },
                    )
                }
                if (vm.sources.isEmpty()) item {
                    Text("no inputs found.\nplug something in.", style = T.data.copy(color = C.faint))
                }
                if (vm.selected.size > 1) item {
                    Label("multiple inputs record side by side · sync is best effort", Modifier.padding(top = 6.dp), color = C.faint)
                }
            }
        }

        val chans = vm.selectedSources.sumOf { it.channels }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("${vm.selected.size} selected")
                Text("$chans ch", style = T.readout)
            }
            PrimaryButton("setup  →", vm::toSetup, enabled = granted && vm.selected.isNotEmpty())
        }
    }
}

@Composable
private fun SourceCard(
    s: Source, selected: Boolean, onClick: () -> Unit, onMode: (Boolean) -> Unit, onMic: (MicMode) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) C.raised else C.surface)
            .border(1.dp, if (selected) C.text.copy(alpha = 0.5f) else C.line, shape),
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selected) C.text else C.bg)
                .border(1.dp, if (selected) C.text else C.line2, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Glyph(G.CHECK, C.bg, 16.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = T.name.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val known = !s.raw || s.usb != null
            Label(
                if (known) "${s.kind.label} · ${rateLabel(s.nativeRate)}hz · ${if (s.hiRes) "24" else "16"} bit"
                else "${s.kind.label} · format read on open",
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("%02d".format(s.channels), style = T.readout.copy(fontSize = 24.sp, color = if (selected) C.text else C.dim))
            Label("in", color = C.faint)
        }
    }
    if (s.canRaw) {
        Hairline(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 54.dp, end = 18.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("raw", s.raw) { onMode(true) }
            if (s.canSystem) Chip("system", !s.raw) { onMode(false) }
            Spacer(Modifier.width(4.dp))
            Text(
                if (s.raw) "direct usb · every channel" else "via android · may be limited",
                style = T.body.copy(fontSize = 12.sp, color = C.faint),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val mic = s.mic
    if (mic != null) {
        Hairline(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
        Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 18.dp, top = 10.dp, bottom = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MicMode.entries.forEach { m -> Chip(m.label, m == mic) { onMic(m) } }
            }
            Spacer(Modifier.height(6.dp))
            Text(mic.blurb, style = T.body.copy(fontSize = 12.sp, color = C.faint), maxLines = 1)
        }
    }
    }
}

// ============================================================================
// 02 setup
// ============================================================================

@Composable
fun SetupScreen(vm: AppModel, tick: State<Long>) {
    Column(Modifier.fillMaxSize().padding(horizontal = GUTTER)) {
        StepStrip(1, onBack = vm::back)
        Spacer(Modifier.height(20.dp))
        Label("session")
        Spacer(Modifier.height(4.dp))
        InlineField(vm.session, { vm.session = it }, T.display, Modifier.fillMaxWidth(), placeholder = "name")
        Spacer(Modifier.height(10.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("rate", Modifier.width(44.dp))
            val rates = vm.rateOptions
            // a choice only looks like one when there is something to choose
            if (rates.size > 1) rates.forEach { r -> Chip(rateLabel(r), r == vm.rate) { vm.pickRate(r) } }
            else Text("${rateLabel(vm.rate)}hz", style = T.data.copy(color = C.dim))
            Spacer(Modifier.weight(1f))
            Label("${vm.bits} bit flac", color = C.faint)
        }
        Spacer(Modifier.height(18.dp))

        val engine = vm.engine
        val rows = vm.rows
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var i = 0
            var trackNo = 0
            while (i < rows.size) {
                val r = rows[i]
                val src = vm.selectedSources.firstOrNull { it.key == r.sourceId }
                if (r.ch == 0 && src != null) {
                    item(key = "h${r.sourceId}") {
                        Label(
                            "${src.name} · ${if (src.raw) "usb raw" else src.kind.label} · ${src.channels} in",
                            Modifier.padding(top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp),
                        )
                    }
                }
                val next = rows.getOrNull(i + 1)
                val stereo = r.linkNext && next != null && next.sourceId == r.sourceId
                val canLink = next != null && next.sourceId == r.sourceId
                val color = C.track(trackNo)
                if (r.on || (stereo && next!!.on)) trackNo++
                val pair = if (stereo) listOf(r, next!!) else listOf(r)
                item(key = "r${r.global}") {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(C.surface)
                            .border(1.dp, if (stereo) C.line2 else C.line, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        pair.forEachIndexed { k, row ->
                            ChannelRow(
                                row = row,
                                isRight = k == 1,
                                color = color,
                                level = { engine?.meters?.getOrElse(row.global) { 0f } ?: 0f },
                                clip = { engine?.let { System.currentTimeMillis() - it.clips.getOrElse(row.global) { 0L } < 1500 } ?: false },
                                tick = tick,
                                link = if (k == 0 && canLink) ({ vm.toggleLink(r) }) else null,
                                linked = stereo,
                                onToggle = { vm.toggleRow(row) },
                                onRename = { vm.rename(row, it) },
                            )
                        }
                    }
                }
                i += pair.size
            }
            // tucked away: usb sample format for raw sources that offer more than one
            val tunable = vm.selectedSources.filter { it.raw && vm.rawFormats(it.key).size > 1 }
            if (tunable.isNotEmpty()) item(key = "usbfmt") { UsbFormat(vm, tunable) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("tracks")
                Text("${vm.rows.count { it.on }} ch", style = T.readout)
            }
            PrimaryButton("go live  →", vm::toLive, enabled = vm.rows.any { it.on })
        }
    }
}

@Composable
private fun UsbFormat(vm: AppModel, sources: List<Source>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Label(
            "usb format · " + sources.joinToString { "${it.usb?.bits ?: "?"} bit" } + if (open) "  ▾" else "  ▸",
            Modifier.clip(CircleShape).clickable { open = !open }.padding(vertical = 8.dp),
            color = C.faint,
        )
        if (open) sources.forEach { s ->
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.rawFormats(s.key).map { it.bits }.sorted().forEach { b ->
                    Chip("$b bit", s.usb?.bits == b) { vm.pickBits(s, b) }
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    if (sources.size > 1) s.name else "16 is lighter on the device",
                    style = T.body.copy(fontSize = 12.sp, color = C.faint),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    row: SetupRow, isRight: Boolean, color: androidx.compose.ui.graphics.Color,
    level: () -> Float, clip: () -> Boolean, tick: State<Long>,
    link: (() -> Unit)?, linked: Boolean, onToggle: () -> Unit, onRename: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (row.on) C.raised else C.bg)
                .border(1.dp, if (row.on) color.copy(alpha = 0.6f) else C.line, RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text("%02d".format(row.ch + 1), style = T.data.copy(color = if (row.on) C.text else C.faint, fontSize = 12.sp))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).alpha(if (row.on) 1f else 0.35f)) {
            if (isRight) Label("↳ right", color = C.dim)
            else InlineField(row.name, onRename, T.name.copy(fontSize = 16.sp), Modifier.fillMaxWidth(), placeholder = "name")
        }
        Spacer(Modifier.width(10.dp))
        HMeter(level, clip, color, tick, Modifier.width(64.dp).height(4.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (link != null) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (linked) C.text else C.bg)
                        .border(1.dp, if (linked) C.text else C.line2, CircleShape)
                        .clickable(onClick = link),
                    contentAlignment = Alignment.Center,
                ) { Text("st", style = T.label.copy(color = if (linked) C.bg else C.dim, letterSpacing = 0.5.sp)) }
            }
        }
    }
}

// ============================================================================
// 03 take — live
// ============================================================================

@Composable
fun LiveScreen(vm: AppModel, tick: State<Long>) {
    var saving by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed != null) { delay(2600); armed = null } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth > 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = GUTTER)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LiveHeader(vm, tick)
                    Timeline(vm, vm.liveView, false, tick, Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp))
                }
                Spacer(Modifier.width(20.dp))
                Column(
                    Modifier.width(200.dp).fillMaxHeight().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { TransportControls(vm, armed, { armed = it }, { saving = true }, vertical = true) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = GUTTER)) {
                LiveHeader(vm, tick)
                Timeline(vm, vm.liveView, false, tick, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                TransportControls(vm, armed, { armed = it }, { saving = true }, vertical = false)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (saving) SaveSheet(vm) { saving = false; vm.closeExport() }
    }
}

@Composable
private fun LiveHeader(vm: AppModel, tick: State<Long>) {
    StepStrip(2, onBack = if (vm.transport != Transport.REC) vm::back else null)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(vm.session.ifBlank { "take" }, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Label("${rateLabel(vm.rate)}hz · ${vm.bits} bit · ${vm.tracks.size} tracks", color = C.faint)
        }
        StateChip(vm, tick)
    }
    Spacer(Modifier.height(4.dp))
    // big timecode: recording length, playhead, or cursor
    val rate = (vm.take?.rate ?: vm.rate).toDouble()
    Row(verticalAlignment = Alignment.Bottom) {
        TickText(tick, T.timecode.copy(color = if (vm.transport == Transport.REC) C.text else C.text)) {
            tc(vm.playhead() / rate)
        }
        Spacer(Modifier.weight(1f))
        val take = vm.take
        if (take != null && vm.transport != Transport.REC) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                Label(if (vm.trimmed) "selection" else "length")
                Text(tc((vm.outF - vm.inF) / rate), style = T.data.copy(color = C.dim))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Text whose content is re-read every frame without recomposing the parent. */
@Composable
private fun TickText(tick: State<Long>, style: androidx.compose.ui.text.TextStyle, text: () -> String) {
    tick.value
    Text(text(), style = style, maxLines = 1)
}

@Composable
private fun StateChip(vm: AppModel, tick: State<Long>) {
    val (label, color) = when (vm.transport) {
        Transport.REC -> "rec" to C.rec
        Transport.PLAY -> "play" to C.text
        Transport.IDLE -> (if (vm.take != null && !vm.saved) "unsaved" else if (vm.saved) "saved" else "ready") to C.dim
    }
    Row(
        Modifier.clip(CircleShape).border(1.dp, color.copy(alpha = 0.5f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.transport == Transport.REC) {
            Canvas(Modifier.size(7.dp)) {
                tick.value
                val on = (System.currentTimeMillis() / 600) % 2 == 0L
                drawCircle(C.rec.copy(alpha = if (on) 1f else 0.25f))
            }
            Spacer(Modifier.width(7.dp))
        }
        Label(label, color = color)
    }
}

@Composable
private fun TransportControls(
    vm: AppModel, armed: String?, arm: (String?) -> Unit, onSave: () -> Unit, vertical: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val rec = vm.transport == Transport.REC
    val hasTake = vm.take != null
    val needsConfirm = hasTake && !vm.saved && !rec

    val onRec = {
        if (needsConfirm && armed != "rec") arm("rec")
        else { arm(null); haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.record() }
    }
    val onRestart = {
        if (armed != "restart") arm("restart")
        else { arm(null); haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.restart() }
    }
    val hint = when (armed) {
        "rec" -> "tap ● again · discards unsaved take"
        "restart" -> if (rec) "tap again · discard & restart" else "tap again · discard take"
        else -> when {
            rec -> "recording"
            !hasTake -> "pinch to zoom · double-tap to follow"
            vm.saved -> "saved · ● starts a new take"
            else -> "tap timeline to place cursor"
        }
    }

    val restartBtn = @Composable {
        RoundButton(
            60.dp, onRestart, enabled = rec || hasTake,
            border = if (armed == "restart") C.rec else C.line2,
        ) { Glyph(G.RESTART, if (armed == "restart") C.rec else C.text, 22.dp) }
    }
    val recBtn = @Composable { RecButton(rec, armed == "rec", onRec) }
    val playBtn = @Composable {
        RoundButton(60.dp, vm::togglePlay, enabled = hasTake && !rec) {
            Glyph(if (vm.transport == Transport.PLAY) G.STOP else G.PLAY, C.text, 22.dp)
        }
    }
    val trimBtn = @Composable { m: Modifier ->
        GhostButton("trim", vm::toTrim, m, enabled = hasTake && !rec)
    }
    val saveBtn = @Composable { m: Modifier ->
        GhostButton("save", onSave, m, enabled = hasTake && !rec, color = if (hasTake && !vm.saved) C.text else C.dim)
    }

    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            recBtn()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { restartBtn(); playBtn() }
            trimBtn(Modifier.fillMaxWidth())
            saveBtn(Modifier.fillMaxWidth())
            Label(hint, color = if (armed != null) C.rec else C.faint)
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) { restartBtn(); recBtn(); playBtn() }
            Spacer(Modifier.height(10.dp))
            Label(hint, color = if (armed != null) C.rec else C.faint)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                trimBtn(Modifier.weight(1f))
                saveBtn(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecButton(recording: Boolean, armed: Boolean, onClick: () -> Unit) {
    val morph by animateFloatAsState(if (recording) 1f else 0f, tween(180), label = "rec")
    Box(
        Modifier.size(84.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawCircle(if (recording || armed) C.rec else C.line2, w / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            // circle → rounded square
            val side = w * (0.62f - 0.24f * morph)
            val r = side / 2 * (1f - 0.7f * morph)
            drawRoundRect(
                C.rec,
                Offset((w - side) / 2, (w - side) / 2),
                Size(side, side),
                CornerRadius(r),
            )
        }
    }
}

// ============================================================================
// trim
// ============================================================================

@Composable
fun TrimScreen(vm: AppModel, tick: State<Long>) {
    val rate = (vm.take?.rate ?: vm.rate).toDouble()
    Column(Modifier.fillMaxSize().padding(horizontal = GUTTER)) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = vm::back), contentAlignment = Alignment.Center) {
                Glyph(G.BACK, C.text, 18.dp)
            }
            Label("trim", Modifier.padding(start = 4.dp), color = C.text)
            Spacer(Modifier.weight(1f))
            Label("reset", Modifier.clip(CircleShape).clickable(onClick = vm::resetTrim).padding(10.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Readout("in", tc(vm.inF / rate), Modifier.weight(1f))
            Readout("out", tc(vm.outF / rate), Modifier.weight(1f))
            Readout("length", tc((vm.outF - vm.inF) / rate), Modifier.weight(1f), color = C.dim)
        }
        Spacer(Modifier.height(14.dp))
        Timeline(vm, vm.trimView, true, tick, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Label("drag the handles · pinch to zoom · double-tap to fit", Modifier.align(Alignment.CenterHorizontally), color = C.faint)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(56.dp, vm::playSelection) {
                Glyph(if (vm.transport == Transport.PLAY) G.STOP else G.PLAY, C.text, 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            PrimaryButton("done", vm::back, Modifier.weight(1f))
        }
    }
}

// ============================================================================
// save sheet
// ============================================================================

@Composable
private fun SaveSheet(vm: AppModel, onClose: () -> Unit) {
    var mode by remember { mutableStateOf<SaveMode?>(null) }
    var name by remember { mutableStateOf(vm.defaultFileName()) }
    val export = vm.export
    val busy = export is ExportState.Running

    Box(
        Modifier.fillMaxSize()
            .background(C.bg.copy(alpha = 0.72f))
            .clickable(enabled = !busy, indication = null, interactionSource = null) { onClose() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(C.surface)
                .border(1.dp, C.line, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clickable(indication = null, interactionSource = null) { }
                .padding(GUTTER),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(C.line2))
            Spacer(Modifier.height(18.dp))
            when {
                export is ExportState.Running -> Progress(vm.exportFraction)
                export is ExportState.Done -> Done(export.where, onClose)
                export is ExportState.Failed -> {
                    Label("failed", color = C.rec)
                    Spacer(Modifier.height(8.dp))
                    Text(export.why, style = T.data.copy(color = C.dim))
                    Spacer(Modifier.height(16.dp))
                    GhostButton("back", { vm.closeExport() }, Modifier.fillMaxWidth())
                }
                mode == null -> {
                    Label("save as")
                    Spacer(Modifier.height(12.dp))
                    SaveMode.entries.forEach { m ->
                        OptionRow(m, vm.tracks.size) { mode = m }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                else -> {
                    Label("${mode!!.title} · filename")
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.bg)
                            .border(1.dp, C.line2, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 16.dp),
                    ) { InlineField(name, { name = it }, T.data.copy(fontSize = 17.sp), Modifier.fillMaxWidth()) }
                    Spacer(Modifier.height(8.dp))
                    val slugged = app.taketaker.audio.slug(name)
                    Text(
                        if (mode == SaveMode.MIX) "→ Music/TakeTaker/$slugged.mp3" else "→ Music/TakeTaker/$slugged/",
                        style = T.data.copy(color = C.faint, fontSize = 12.sp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("back", { mode = null }, Modifier.height(56.dp))
                        PrimaryButton("save", { vm.save(mode!!, name) }, Modifier.weight(1f), enabled = name.isNotBlank())
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OptionRow(m: SaveMode, tracks: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.raised)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.title, style = T.name.copy(fontSize = 17.sp))
            Spacer(Modifier.height(2.dp))
            Text(m.blurb, style = T.body.copy(fontSize = 13.sp, color = C.dim))
        }
        Label(
            when (m) {
                SaveMode.MULTI -> "${tracks}× flac"
                SaveMode.MULTI_MIX -> "${tracks}× flac + mp3"
                SaveMode.MIX -> "mp3"
            },
            color = C.faint,
        )
    }
}

@Composable
private fun ColumnScope.Progress(f: Float) {
    Label("writing")
    Spacer(Modifier.height(6.dp))
    Text("%d%%".format((f * 100).toInt()), style = T.timecode.copy(fontSize = 40.sp))
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(2.dp).background(C.line)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(2.dp).background(C.text))
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.Done(where: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(G.CHECK, C.text, 26.dp)
        Spacer(Modifier.width(8.dp))
        Text("saved", style = T.title)
    }
    Spacer(Modifier.height(8.dp))
    Text(where, style = T.data.copy(color = C.dim))
    Spacer(Modifier.height(18.dp))
    PrimaryButton("done", onClose, Modifier.fillMaxWidth())
}
