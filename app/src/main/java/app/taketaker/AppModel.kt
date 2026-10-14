package app.taketaker

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import app.taketaker.audio.Engine
import app.taketaker.audio.VideoOpt
import app.taketaker.video.CameraRig
import app.taketaker.video.Facing
import app.taketaker.audio.MicMode
import app.taketaker.audio.Exporter
import app.taketaker.audio.Player
import app.taketaker.audio.SaveMode
import app.taketaker.audio.Source
import app.taketaker.audio.Sources
import app.taketaker.audio.Take
import app.taketaker.audio.Track
import app.taketaker.audio.RawSpec
import app.taketaker.audio.UsbRaw
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { SOURCES, SETUP, LIVE, TRIM }
enum class Transport { IDLE, REC, PLAY }

/** One input channel in setup. [linkNext] pairs it with the following channel as a stereo track. */
data class Row(
    val sourceId: String,
    val ch: Int,
    val global: Int,
    val name: String,
    val on: Boolean = true,
    val linkNext: Boolean = false,
)

/** Visible time window of a timeline, in seconds. Mutated from draw/gesture code, not observed. */
class ViewWindow {
    var follow = true
    var start = 0.0
    var span = 10.0
}

sealed interface ExportState {
    data class Running(val progress: Float) : ExportState
    data class Done(val result: app.taketaker.audio.ExportResult, val zipping: Boolean = false) : ExportState
    data class Failed(val why: String) : ExportState
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val am = app.getSystemService(AudioManager::class.java)
    private val um = app.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val workDir = File(app.cacheDir, "takes").apply { deleteRecursively(); mkdirs() }

    var screen by mutableStateOf(Screen.SOURCES); private set
    var sources by mutableStateOf(emptyList<Source>()); private set
    var selected by mutableStateOf(emptyList<String>()); private set
    /** Per USB source: capture raw (true) or through Android (false). Absent = raw when possible. */
    var rawPref by mutableStateOf(emptyMap<String, Boolean>()); private set
    private var specs = emptyMap<String, List<RawSpec>>()
    /** Per raw source: chosen bit depth (absent = deepest the device offers). */
    var rawBits by mutableStateOf(emptyMap<String, Int>()); private set
    var session by mutableStateOf("take")
    var rate by mutableStateOf(48000); private set
    var rows by mutableStateOf(emptyList<Row>()); private set
    var tracks by mutableStateOf(emptyList<Track>()); private set
    var engine by mutableStateOf<Engine?>(null); private set
    var take by mutableStateOf<Take?>(null); private set
    var transport by mutableStateOf(Transport.IDLE); private set
    var inF by mutableLongStateOf(0L); private set
    var outF by mutableLongStateOf(0L); private set
    var cursor by mutableLongStateOf(0L)
    var saved by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var export by mutableStateOf<ExportState?>(null); private set
    var exportFraction by mutableFloatStateOf(0f); private set

    /** Video recorded alongside takes; null = no camera. */
    var facing by mutableStateOf<Facing?>(null); private set
    val camera = CameraRig(app)

    /** Physical orientation, quantized; time held per orientation is summed while filming. */
    @Volatile private var deviceDegrees = 0
    private val heldMs = LongArray(4)
    private var heldSince = 0L
    private val orientation = object : android.view.OrientationEventListener(app) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            val q = ((o + 45) / 90 % 4) * 90
            if (q != deviceDegrees) {
                accrueHeld()
                deviceDegrees = q
            }
        }
    }

    private fun accrueHeld() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (heldSince > 0) heldMs[deviceDegrees / 90] += now - heldSince
        heldSince = if (transport == Transport.REC) now else 0L
    }

    fun pickCamera(f: Facing?) {
        facing = f
        if (f != null) orientation.enable() else orientation.disable()
        if (f == null) camera.unbind()
    }

    val liveView = ViewWindow()
    val trimView = ViewWindow()
    private var player: Player? = null

    /** A source as it will be captured, raw or system, per the user's choice. */
    fun effective(s: Source): Source {
        if (s.kind == Source.Kind.MIC) {
            val m = micPref[s.key] ?: MicMode.STEREO
            return s.copy(mic = m, channels = minOf(m.channels, maxOf(s.channels, 1)))
        }
        val wantRaw = s.canRaw && (rawPref[s.key] ?: true) || !s.canSystem
        if (!wantRaw) return s
        // default to the lightest format at full channel count: class-1 USB devices are small
        // gadgets, and their deeper formats often just pad 16-bit audio (the FM-1 does)
        val spec = rawFormats(s.key).let { fs -> fs.firstOrNull { it.bits == rawBits[s.key] } ?: fs.lastOrNull() }
        return if (spec != null) s.asRaw(spec) else s.copy(raw = true)
    }

    /** Formats of a raw source at its full channel count, deepest first. */
    fun rawFormats(key: String): List<RawSpec> {
        val all = specs[key].orEmpty()
        val ch = all.maxOfOrNull { it.channels } ?: return emptyList()
        return all.filter { it.channels == ch }.distinctBy { it.bits }
    }

    /** Lower bit depths mean less USB work for the device; useful when it is near its CPU limit. */
    fun pickBits(s: Source, bits: Int) {
        if (effective(s).usb?.bits == bits) return
        rawBits = rawBits + (s.key to bits)
        startEngine()
    }

    val selectedSources get() = selected.mapNotNull { k -> sources.firstOrNull { it.key == k } }.map(::effective)
    val rateOptions: List<Int> get() {
        val chosen = selectedSources
        if (chosen.isEmpty()) return listOf(48000)
        return chosen.map { it.supportedRates.ifEmpty { listOf(it.nativeRate) }.toSet() }
            .reduce { common, next -> common intersect next }.sorted()
    }
    val bits get() = if (selectedSources.any { it.hiRes }) 24 else 16
    val trimmed get() = take != null && (inF > 0 || outF < (take?.frames ?: 0))

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = rescan()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val before = selected.toSet()
            rescan()
            val lost = before.filter { k -> sources.none { it.key == k } }.mapNotNull { k -> engine?.sources?.firstOrNull { it.key == k } }
            if (lost.isNotEmpty() && screen != Screen.SOURCES) {
                message = "${lost.joinToString { it.name }} disconnected"
                if (transport == Transport.REC) stopRec()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_USB_PERMISSION -> {
                    val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val key = pendingPermission
                    pendingPermission = null
                    if (key != null && !ok) {
                        rawPref = rawPref + (key to false)
                        message = "no usb access · using system mode"
                    }
                    if (key != null) toSetup()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    specs = emptyMap()
                    val before = selected.toSet()
                    rescan()
                    if (screen != Screen.SOURCES && before.any { k -> sources.none { it.key == k } }) {
                        message = "usb device disconnected"
                        if (transport == Transport.REC) stopRec()
                    }
                }
            }
        }
    }
    private var pendingPermission: String? = null

    init {
        am.registerAudioDeviceCallback(deviceCallback, main)
        ContextCompat.registerReceiver(
            app, usbReceiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    companion object {
        const val ACTION_USB_PERMISSION = "app.taketaker.USB_PERMISSION"
    }

    fun setRaw(s: Source, raw: Boolean) {
        rawPref = rawPref + (s.key to raw)
    }

    /** Built-in mic recording preset per mic source (absent = stereo). */
    var micPref by mutableStateOf(emptyMap<String, MicMode>()); private set

    fun setMic(s: Source, m: MicMode) {
        micPref = micPref + (s.key to m)
    }

    fun rescan() {
        val found = Sources.scan(am, um)
        specs = specs.filterKeys { key -> found.any { it.key == key } }
        sources = found
        if (screen == Screen.SOURCES) selected = selected.filter { k -> sources.any { it.key == k } }
        if (!autoPicked && selected.isEmpty()) {
            sources.firstOrNull { it.kind == Source.Kind.USB }?.let { selected = listOf(it.key); autoPicked = true }
        }
    }

    private var autoPicked = false

    fun toggleSource(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    /**
     * Raw sources need USB permission and their descriptors before we know channel layout.
     * Returns false while a permission dialog is up (toSetup re-runs when it is answered).
     */
    private fun prepareRaw(): Boolean {
        for (k in selected) {
            val s = sources.firstOrNull { it.key == k } ?: continue
            if (!effective(s).raw || specs.containsKey(k)) continue
            val dev: UsbDevice = s.usbDevice ?: continue
            if (!um.hasPermission(dev)) {
                pendingPermission = k
                val ctx = getApplication<Application>()
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                um.requestPermission(dev, pi)
                return false
            }
            val spec: List<RawSpec>
            try {
                spec = UsbRaw.probe(um, dev)
            } catch (e: Exception) {
                message = "${s.name}: ${e.message ?: "USB probe failed"}"
                android.util.Log.w("TakeTaker", "raw probe failed: ${s.name}", e)
                return false
            }
            if (spec.isEmpty()) {
                message = "${s.name}: no supported USB PCM capture format"
                android.util.Log.w("TakeTaker", message!!)
                return false
            } else {
                specs = specs + (k to spec)
            }
        }
        return true
    }

    // ---- setup -------------------------------------------------------------

    fun toSetup() {
        if (!prepareRaw()) return
        if (selectedSources.isEmpty()) { message = "nothing to record from"; return }
        if (rateOptions.isEmpty()) { message = "selected inputs have no common sample rate"; return }
        val old = rows.associateBy { it.sourceId to it.ch }
        var g = 0
        rows = selectedSources.flatMap { s ->
            (0 until s.channels).map { c ->
                val prev = old[s.key to c]
                val name = prev?.name ?: if (s.channels == 1 || (s.kind == Source.Kind.MIC)) s.handle else "${s.handle} ${c + 1}"
                Row(s.key, c, g++, name, prev?.on ?: true,
                    prev?.linkNext ?: (s.channels == 2 && c == 0 && s.kind != Source.Kind.USB))
            }
        }
        if (rate !in rateOptions) rate = rateOptions.max()
        if (session.isBlank()) session = "take"
        startEngine()
        screen = Screen.SETUP
    }

    fun pickRate(r: Int) {
        if (r !in rateOptions) { message = "$r Hz is not supported by every selected input"; return }
        if (r == rate) return
        rate = r
        startEngine()
    }

    private fun startEngine() {
        stopPlay()
        discardTake()
        engine?.release()
        selectedSources.forEach { s ->
            android.util.Log.i("TakeTaker", "capture ${s.name}: ${if (s.raw) "raw" else "system"} " +
                "${s.channels} channels at $rate Hz")
        }
        engine = Engine(workDir, um, selectedSources, rate) { msg -> main.post { message = msg } }.also { it.start() }
    }

    fun rename(row: Row, name: String) = updateRow(row) { it.copy(name = name) }
    fun toggleRow(row: Row) = updateRow(row) { it.copy(on = !it.on) }
    fun toggleLink(row: Row) = updateRow(row) { it.copy(linkNext = !it.linkNext) }

    private fun updateRow(row: Row, f: (Row) -> Row) {
        rows = rows.map { if (it.global == row.global) f(it) else it }
    }

    /** Index of the row that a row is the right half of, or -1. */
    fun pairedWithPrev(i: Int): Boolean =
        i > 0 && rows[i - 1].linkNext && rows[i - 1].sourceId == rows[i].sourceId

    private fun buildTracks(): List<Track> {
        val out = mutableListOf<Track>()
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            val next = rows.getOrNull(i + 1)
            val stereo = r.linkNext && next != null && next.sourceId == r.sourceId
            if (r.on) {
                val chans = if (stereo && next!!.on) listOf(r.global, next.global) else listOf(r.global)
                out += Track(r.name.ifBlank { "track ${out.size + 1}" }, chans, out.size)
            }
            if (stereo && !r.on && next!!.on) out += Track(next.name.ifBlank { r.name }, listOf(next.global), out.size)
            i += if (stereo) 2 else 1
        }
        return out
    }

    fun toLive() {
        val old = tracks.associateBy { it.chans }
        tracks = buildTracks().map { t -> t.copy(muted = old[t.chans]?.muted ?: false) }
        liveView.follow = true
        screen = Screen.LIVE
    }

    fun back() {
        when (screen) {
            Screen.TRIM -> { stopPlay(); screen = Screen.LIVE }
            Screen.LIVE -> if (transport != Transport.REC) { stopPlay(); screen = Screen.SETUP }
            Screen.SETUP -> {
                discardTake()
                engine?.release(); engine = null
                screen = Screen.SOURCES
            }
            Screen.SOURCES -> Unit
        }
    }

    // ---- transport ---------------------------------------------------------

    fun toggleMute(t: Track) {
        tracks = tracks.map { if (it.chans == t.chans) it.copy(muted = !it.muted) else it }
    }

    fun record() {
        if (transport == Transport.REC) { stopRec(); return }
        val e = engine ?: return
        stopPlay()
        discardTake()
        val t = e.startTake()
        take = t
        export = null // a previous save result belongs to the previous take
        if (facing != null && camera.bound != null) {
            t.videoPending = true
            orientation.enable()
            heldMs.fill(0); heldSince = android.os.SystemClock.elapsedRealtime()
            t.videoStartDegrees = deviceDegrees
            t.videoFront = facing == Facing.FRONT
            camera.start(
                File(t.dir, "video.mp4"),
                deviceDegrees,
                onStarted = { ns -> t.videoOffsetUs = (ns - t.startNanos) / 1000 },
                onFinished = { f -> t.video = f; t.videoPending = false },
            )
        }
        saved = false
        inF = 0; outF = 0; cursor = 0
        liveView.follow = true
        transport = Transport.REC
        setService(true)
    }

    fun stopRec() {
        take?.let { t ->
            if (t.videoPending || t.video != null) {
                accrueHeld()
                t.videoDominantDegrees = heldMs.indices.maxBy { heldMs[it] } * 90
            }
        }
        camera.stop()
        val t = engine?.stopTake()
        transport = Transport.IDLE
        setService(false)
        if (t != null) { take = t; inF = 0; outF = t.frames }
    }

    /** While recording: throw away and start over. Otherwise: clear the take. */
    fun restart() {
        if (transport == Transport.REC) {
            camera.stop()
            engine?.stopTake()?.delete()
            take = null
            transport = Transport.IDLE
            record()
        } else {
            stopPlay()
            discardTake()
        }
    }

    private fun discardTake() {
        camera.stop()
        export = null
        take?.delete()
        take = null
        saved = false
        inF = 0; outF = 0; cursor = 0
    }

    fun togglePlay() {
        if (transport == Transport.PLAY) { stopPlay(); return }
        val t = take ?: return
        if (transport == Transport.REC) return
        val from = if (cursor in inF until outF) cursor else inF
        play(t, from, outF)
    }

    fun seek(f: Long) {
        val playing = transport == Transport.PLAY
        if (playing) stopPlay()
        cursor = f
        if (playing) togglePlay()
    }

    fun playSelection() {
        if (transport == Transport.PLAY) { stopPlay(); return }
        val t = take ?: return
        play(t, inF, outF)
    }

    private fun play(t: Take, from: Long, to: Long) {
        val p = player ?: Player(t) { tracks }.also { player = it }
        transport = Transport.PLAY
        p.play(from, to) { main.post { if (transport == Transport.PLAY) transport = Transport.IDLE } }
    }

    fun stopPlay() {
        if (transport == Transport.PLAY) {
            cursor = player?.position() ?: cursor
            transport = Transport.IDLE
        }
        player?.stop()
        player = null
    }

    /** Frame shown by the playhead. */
    fun playhead(): Long = when (transport) {
        Transport.PLAY -> player?.position() ?: cursor
        Transport.REC -> take?.frames ?: 0
        Transport.IDLE -> cursor
    }

    // ---- trim --------------------------------------------------------------

    fun toTrim() {
        if (take == null || transport == Transport.REC) return
        stopPlay()
        trimView.follow = true
        screen = Screen.TRIM
    }

    fun setIn(f: Long) { inF = f.coerceIn(0, outF - 1) }
    fun setOut(f: Long) { outF = f.coerceIn(inF + 1, take?.frames ?: f) }
    fun resetTrim() { inF = 0; outF = take?.frames ?: 0 }

    // ---- save --------------------------------------------------------------

    fun defaultFileName(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(take?.startedAt ?: System.currentTimeMillis()))
        return "${app.taketaker.audio.slug(session)}_$ts"
    }

    /** [video]: null = no MP4. */
    fun save(mode: SaveMode, name: String, normalize: Boolean, video: app.taketaker.audio.VideoKind?, turn: Int = 0) {
        val t = take ?: return
        stopPlay()
        val snapshot = tracks
        val (a, b) = inF to outF
        val fileName = app.taketaker.audio.slug(name)
        export = ExportState.Running(0f)
        exportFraction = 0f
        Thread({
            try {
                // footage finalizes asynchronously after stop; give it a moment
                var waited = 0
                while (t.videoPending && waited < 5000) { Thread.sleep(50); waited += 50 }
                val opt = video?.let { VideoOpt(session.ifBlank { "take" }, it, turn) }
                val result = Exporter(getApplication()).export(t, snapshot, a, b, mode, fileName, bits, normalize, opt) { f ->
                    main.post { exportFraction = f }
                }
                lastName = fileName
                main.post { export = ExportState.Done(result); saved = true }
            } catch (e: Throwable) {
                main.post { export = ExportState.Failed(e.message ?: e.javaClass.simpleName) }
            }
        }, "export").start()
    }

    fun closeExport() {
        stopPreview()
        export = null
    }

    // ---- mixdown preview (plays the saved file, not the take) ------------

    var previewing by mutableStateOf(false); private set
    private var preview: android.media.MediaPlayer? = null

    fun togglePreview() {
        if (previewing) { stopPreview(); return }
        val mix = (export as? ExportState.Done)?.result?.mix ?: return
        stopPlay()
        preview = android.media.MediaPlayer().apply {
            setDataSource(getApplication(), mix.uri)
            setOnCompletionListener { stopPreview() }
            prepare()
            start()
        }
        previewing = true
    }

    private fun stopPreview() {
        preview?.runCatching { stop(); release() }
        preview = null
        previewing = false
    }

    fun shareMix() = (export as? ExportState.Done)?.result?.mix?.let { share(it.uri, it.mime) }
    fun shareVideo() = (export as? ExportState.Done)?.result?.video?.let { share(it.uri, it.mime) }

    private var lastName = "take"

    /** Mixdown and video open in whatever the phone uses for audio / video. */
    fun openVideo() = (export as? ExportState.Done)?.result?.video?.let { open(it.uri, it.mime) }
    fun openMix() = (export as? ExportState.Done)?.result?.mix?.let { open(it.uri, it.mime) }

    private fun open(uri: android.net.Uri, mime: String) {
        val ctx = getApplication<Application>()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(view) } catch (_: android.content.ActivityNotFoundException) { message = "nothing installed to open $mime" }
    }

    /** Builds the zip only now, when asked, then hands it to the share sheet. */
    fun shareZip() {
        val d = export as? ExportState.Done ?: return
        if (d.zipping) return
        export = d.copy(zipping = true)
        val ctx = getApplication<Application>()
        Thread({
            try {
                val zip = Exporter(ctx).zip(d.result.files, lastName)
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
                main.post { export = d; share(uri, "application/zip") }
            } catch (e: Throwable) {
                main.post { export = d; message = "zip failed: ${e.message}" }
            }
        }, "zip").start()
    }

    private fun share(uri: android.net.Uri, mime: String) {
        val ctx = getApplication<Application>()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun setService(on: Boolean) {
        val ctx = getApplication<Application>()
        val i = Intent(ctx, RecService::class.java)
        try {
            if (on) ctx.startForegroundService(i) else ctx.stopService(i)
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        player?.stop()
        engine?.release()
        camera.unbind()
        orientation.disable()
        setService(false)
    }
}
