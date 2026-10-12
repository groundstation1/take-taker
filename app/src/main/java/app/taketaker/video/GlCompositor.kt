package app.taketaker.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws decoded video frames (arriving on [decoderSurface]) into an encoder's input surface,
 * with an overlay bitmap screen-blended on top: black in the overlay leaves the video untouched,
 * bright pixels glow over it. Must be used from one thread (the one that created it).
 */
internal class GlCompositor(encoderSurface: Surface, private val w: Int, private val h: Int) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    private val videoTex: Int
    private val overlayTex: Int
    private val videoProg: Int
    private val overlayProg: Int
    private val st = FloatArray(16)

    private val frameThread = HandlerThread("frames").apply { start() }
    private val lock = Object()
    private var frameReady = false
    private val surfaceTexture: SurfaceTexture
    val decoderSurface: Surface

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        videoTex = tex[0]; overlayTex = tex[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        params(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        params(GLES20.GL_TEXTURE_2D)

        videoProg = program(VERT, "#extension GL_OES_EGL_image_external : require\n" + FRAG.replace("sampler2D", "samplerExternalOES"))
        overlayProg = program(VERT, FRAG)

        surfaceTexture = SurfaceTexture(videoTex).apply {
            setOnFrameAvailableListener({ synchronized(lock) { frameReady = true; lock.notifyAll() } }, Handler(frameThread.looper))
        }
        decoderSurface = Surface(surfaceTexture)
    }

    private fun params(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Waits for the decoder's frame and latches it. False on timeout. */
    fun awaitFrame(): Boolean {
        synchronized(lock) {
            val until = System.currentTimeMillis() + 2500
            while (!frameReady) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            frameReady = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(st)
        return true
    }

    /** Video (turned upright by [rotation], the container's rotation-degrees) + screen-blended [overlay]. */
    fun draw(rotation: Int, overlay: Bitmap) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        quad(videoProg, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex, videoCoords(rotation), st)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR) // screen
        quad(overlayProg, GLES20.GL_TEXTURE_2D, overlayTex, OVERLAY_COORDS, IDENTITY)
    }

    fun swap(ptsNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        decoderSurface.release()
        surfaceTexture.release()
        frameThread.quitSafely()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun quad(prog: Int, target: Int, tex: Int, coords: FloatBuffer, texMatrix: FloatArray) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POSITIONS)
        GLES20.glEnableVertexAttribArray(aTex)
        coords.position(0)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSt"), 1, false, texMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "s"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(it) }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val VERT = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uSt; varying vec2 v;" +
            "void main(){ gl_Position = aPos; v = (uSt * aTex).xy; }"
        const val FRAG = "precision mediump float; varying vec2 v; uniform sampler2D s;" +
            "void main(){ gl_FragColor = texture2D(s, v); }"

        fun fb(vararg f: Float): FloatBuffer =
            ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(f); position(0) }

        // triangle strip: bottom-left, bottom-right, top-left, top-right
        val POSITIONS = fb(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        /** Bitmap row 0 is the top, so flip v. */
        val OVERLAY_COORDS = fb(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        /** Texture coords per screen corner (BL, BR, TL, TR) that turn the frame upright. */
        fun videoCoords(rotation: Int): FloatBuffer = when ((rotation % 360 + 360) % 360) {
            90 -> fb(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
            180 -> fb(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
            270 -> fb(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
            else -> fb(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        }
    }
}
