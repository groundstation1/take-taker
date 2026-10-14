package app.taketaker.ui

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.taketaker.AppModel
import app.taketaker.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.abs

/**
 * The take's recorded footage, following the timeline: plays (muted) with audio playback, and
 * otherwise shows the frame at [AppModel.scrub] (cursor / trim handle being dragged).
 * Letterboxed to the footage's aspect inside whatever box it's given.
 */
@Composable
fun FootageView(vm: AppModel, file: File, modifier: Modifier = Modifier) {
    val take = vm.take ?: return
    val aspect = remember(file) {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(file.path)
            val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
            val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
            val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) h / w else w / h
        } catch (_: Exception) { 9f / 16f } finally { m.release() }
    }
    var ready by remember(file) { mutableStateOf(false) }
    val player = remember(file) {
        MediaPlayer().apply {
            setDataSource(file.path)
            setVolume(0f, 0f) // the audio is the take's, not the camera's
            setOnPreparedListener { ready = true }
        }
    }
    DisposableEffect(player) { onDispose { player.runCatching { stop(); release() } } }

    /** Footage time (ms) for a take frame: video started [Take.videoOffsetUs] after the audio. */
    fun videoMs(frame: Long) = (frame * 1000L / take.rate - take.videoOffsetUs / 1000).coerceAtLeast(0)

    LaunchedEffect(player, ready, vm.transport) {
        if (!ready) return@LaunchedEffect
        if (vm.transport == Transport.PLAY) {
            player.seekTo(videoMs(vm.playhead()), MediaPlayer.SEEK_CLOSEST)
            player.start()
            while (isActive) { // keep it within a few frames of the audio
                delay(400)
                val want = videoMs(vm.playhead())
                if (abs(player.currentPosition - want) > 150) player.seekTo(want, MediaPlayer.SEEK_CLOSEST)
            }
        } else {
            if (player.isPlaying) player.pause()
            var shown = -1L
            while (isActive) { // follow scrubbing, at most ~12 seeks a second
                val want = videoMs(vm.scrub)
                if (want != shown) { player.seekTo(want, MediaPlayer.SEEK_CLOSEST); shown = want }
                delay(80)
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            player.setSurface(Surface(st))
                            player.prepareAsync()
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.aspectRatio(aspect),
        )
    }
}

/** The take has finished footage to show instead of the live camera. */
fun AppModel.showFootage(): File? =
    take?.video?.takeIf { transport != Transport.REC && it.exists() }
