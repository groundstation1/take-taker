package app.taketaker.video

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

enum class Facing(val label: String, val selector: CameraSelector) {
    BACK("back", CameraSelector.DEFAULT_BACK_CAMERA),
    FRONT("front", CameraSelector.DEFAULT_FRONT_CAMERA),
}

/**
 * Video-only capture that runs next to the audio engine. It never touches audio: the take's
 * audio comes from the engine, and the two are lined up at export using [startNanos].
 */
class CameraRig(private val ctx: Context) {
    val preview: Preview = Preview.Builder().build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    private val capture = VideoCapture.withOutput(recorder)
    private var provider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    var bound: Facing? = null; private set

    fun bind(owner: LifecycleOwner, facing: Facing, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                p.bindToLifecycle(owner, facing.selector, preview, capture)
                bound = facing
            } catch (e: Exception) {
                bound = null
                onError("camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun unbind() {
        stop()
        provider?.unbindAll()
        bound = null
    }

    /**
     * Starts writing [file]; [onStarted] gets System.nanoTime() of the moment the recorder
     * reported it was running, [onFinished] the file (or null on failure).
     */
    @SuppressLint("MissingPermission")
    fun start(file: File, onStarted: (Long) -> Unit, onFinished: (File?) -> Unit) {
        if (bound == null) return
        stop()
        recording = capture.output
            .prepareRecording(ctx, FileOutputOptions.Builder(file).build()) // no .withAudioEnabled(): audio is the engine's job
            .start(ContextCompat.getMainExecutor(ctx)) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> onStarted(System.nanoTime())
                    is VideoRecordEvent.Finalize -> onFinished(if (file.exists() && file.length() > 0) file else null)
                }
            }
    }

    fun stop() {
        recording?.stop()
        recording = null
    }
}
