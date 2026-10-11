package app.taketaker

import android.app.Application
import android.content.Intent
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
import app.taketaker.audio.Exporter
import app.taketaker.audio.Player
import app.taketaker.audio.SaveMode
import app.taketaker.audio.Source
import app.taketaker.audio.Sources
import app.taketaker.audio.Take
import app.taketaker.audio.Track
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { SOURCES, SETUP, LIVE, TRIM }
enum class Transport { IDLE, REC, PLAY }

/** One input channel in setup. [linkNext] pairs it with the following channel as a stereo track. */
data class Row(
    val sourceId: Int,
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
    data class Done(val where: String) : ExportState
    data class Failed(val why: String) : ExportState
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val am = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val workDir = File(app.cacheDir, "takes").apply { deleteRecursively(); mkdirs() }

    var screen by mutableStateOf(Screen.SOURCES); private set
    var sources by mutableStateOf(emptyList<Source>()); private set
    var selected by mutableStateOf(emptyList<Int>()); private set
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

    val liveView = ViewWindow()
    val trimView = ViewWindow()
    private var player: Player? = null

    val selectedSources get() = selected.mapNotNull { id -> sources.firstOrNull { it.id == id } }
    val rateOptions get() = (selectedSources.map { it.nativeRate }.ifEmpty { listOf(48000) }).distinct().sorted()
    val bits get() = if (selectedSources.any { it.hiRes }) 24 else 16
    val trimmed get() = take != null && (inF > 0 || outF < (take?.frames ?: 0))

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = rescan()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val gone = removed.map { it.id }.toSet()
            val lost = selectedSources.filter { it.id in gone }
            rescan()
            if (lost.isNotEmpty() && screen != Screen.SOURCES) {
                message = "${lost.joinToString { it.name }} disconnected"
                if (transport == Transport.REC) stopRec()
            }
        }
    }

    init {
        am.registerAudioDeviceCallback(deviceCallback, main)
    }

    fun rescan() {
        sources = Sources.scan(am)
        if (screen == Screen.SOURCES) selected = selected.filter { id -> sources.any { it.id == id } }
        if (!autoPicked && selected.isEmpty()) {
            sources.firstOrNull { it.kind == Source.Kind.USB }?.let { selected = listOf(it.id); autoPicked = true }
        }
    }

    private var autoPicked = false

    fun toggleSource(id: Int) {
        selected = if (id in selected) selected - id else selected + id
    }

    // ---- setup -------------------------------------------------------------

    fun toSetup() {
        val old = rows.associateBy { it.sourceId to it.ch }
        var g = 0
        rows = selectedSources.flatMap { s ->
            (0 until s.channels).map { c ->
                val prev = old[s.id to c]
                val name = prev?.name ?: if (s.channels == 1 || (s.kind == Source.Kind.MIC)) s.handle else "${s.handle} ${c + 1}"
                Row(s.id, c, g++, name, prev?.on ?: true,
                    prev?.linkNext ?: (s.channels == 2 && c == 0 && s.kind != Source.Kind.USB))
            }
        }
        if (rate !in rateOptions) rate = rateOptions.max()
        if (session.isBlank()) session = "take"
        startEngine()
        screen = Screen.SETUP
    }

    fun pickRate(r: Int) {
        if (r == rate) return
        rate = r
        startEngine()
    }

    private fun startEngine() {
        stopPlay()
        discardTake()
        engine?.release()
        engine = Engine(workDir, selectedSources, rate) { msg -> main.post { message = msg } }.also { it.start() }
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
        take = e.startTake()
        saved = false
        inF = 0; outF = 0; cursor = 0
        liveView.follow = true
        transport = Transport.REC
        setService(true)
    }

    fun stopRec() {
        val t = engine?.stopTake()
        transport = Transport.IDLE
        setService(false)
        if (t != null) { take = t; inF = 0; outF = t.frames }
    }

    /** While recording: throw away and start over. Otherwise: clear the take. */
    fun restart() {
        if (transport == Transport.REC) {
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

    fun save(mode: SaveMode, name: String) {
        val t = take ?: return
        stopPlay()
        val snapshot = tracks
        val (a, b) = inF to outF
        val fileName = app.taketaker.audio.slug(name)
        export = ExportState.Running(0f)
        exportFraction = 0f
        Thread({
            try {
                val where = Exporter(getApplication()).export(t, snapshot, a, b, mode, fileName, bits) { f ->
                    main.post { exportFraction = f }
                }
                main.post { export = ExportState.Done(where); saved = true }
            } catch (e: Throwable) {
                main.post { export = ExportState.Failed(e.message ?: e.javaClass.simpleName) }
            }
        }, "export").start()
    }

    fun closeExport() { export = null }

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
        player?.stop()
        engine?.release()
        setService(false)
    }
}
