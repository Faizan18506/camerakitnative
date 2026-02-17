package com.example.camerakitnative

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * SurfaceReplicator
 * Mirrors frames from Camera Kit (inputSurface) to:
 * 1. Mobile Preview (full screen)
 * 2. RTMP Encoder (480p)
 */
class SurfaceReplicator(
    private val previewSurface: Surface?,
    private val encoderSurface: Surface?,
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "SurfaceReplicator"
    }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglPreviewSurface = EGL14.EGL_NO_SURFACE
    private var eglEncoderSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId = -1
    private var sourceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null // Surface for Camera Kit

    private var program = 0
    private var aPositionLocation = -1
    private var aTexCoordLocation = -1
    private var uTextureMatrixLocation = -1

    private var glThread: Thread? = null
    @Volatile
    private var running = false
    private val surfaceLock = Object()

    // Full screen quad
    private val VERTICES = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
    // Flipped V-coordinates for upright preview
    private val TEX_COORDS_FLIPPED = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTICES).apply { position(0) }
    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS_FLIPPED.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORDS_FLIPPED).apply { position(0) }
    private val textureMatrix = FloatArray(16)

    fun awaitInputSurface(timeoutMs: Long): Surface? {
        val start = System.currentTimeMillis()
        synchronized(surfaceLock) {
            while (inputSurface == null && running) {
                val elapsed = System.currentTimeMillis() - start
                if (timeoutMs > 0 && elapsed >= timeoutMs) break
                try { surfaceLock.wait(50) } catch (ignored: InterruptedException) {}
            }
            return inputSurface
        }
    }

    fun start() {
        if (running) return
        running = true
        glThread = Thread({ glLoop() }, "SurfaceReplicator-GL")
        glThread?.start()
    }

    fun stop() {
        running = false
        glThread?.join(500)
        glThread = null
    }

    private fun glLoop() {
        try {
            initEgl()
            initTargets()
            initSourceTexture()
            initShader()

            if (program == 0) return

            var lastTimeNs = System.nanoTime()
            val frameIntervalNs = (1000000000 / 30).toLong() // Target 30 FPS

            while (running) {
                val now = System.nanoTime()
                if (now - lastTimeNs < frameIntervalNs) {
                    Thread.sleep(5)
                    continue
                }
                lastTimeNs = now

                sourceTexture?.let {
                    try {
                        it.updateTexImage()
                        it.getTransformMatrix(textureMatrix)
                    } catch (e: Exception) { return@let }
                }

                if (eglPreviewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, eglPreviewSurface, eglPreviewSurface, eglContext)
                    // Use actual surface dimensions for preview to fill screen
                    val surfaceWidth = IntArray(1)
                    val surfaceHeight = IntArray(1)
                    EGL14.eglQuerySurface(eglDisplay, eglPreviewSurface, EGL14.EGL_WIDTH, surfaceWidth, 0)
                    EGL14.eglQuerySurface(eglDisplay, eglPreviewSurface, EGL14.EGL_HEIGHT, surfaceHeight, 0)
                    drawFrame(surfaceWidth[0], surfaceHeight[0])
                    EGL14.eglSwapBuffers(eglDisplay, eglPreviewSurface)
                }

                if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext)
                    // Encoder uses the configured streaming resolution
                    drawFrame(width, height)
                    EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GL Loop error: ${e.message}")
        } finally {
            releaseEgl()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, IntArray(1), 0)
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    }

    private fun initTargets() {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        if (previewSurface != null) eglPreviewSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, previewSurface, attribs, 0)
        if (encoderSurface != null) eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, attribs, 0)
    }

    private fun initSourceTexture() {
        val currentSurface = if (eglPreviewSurface != EGL14.EGL_NO_SURFACE) eglPreviewSurface else eglEncoderSurface
        if (currentSurface == EGL14.EGL_NO_SURFACE) return // Nothing to render to

        EGL14.eglMakeCurrent(eglDisplay, currentSurface, currentSurface, eglContext)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        sourceTexture = SurfaceTexture(oesTextureId)
        sourceTexture?.setDefaultBufferSize(width, height)
        inputSurface = Surface(sourceTexture)
        
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
    }

    private fun initShader() {
        val vs = "attribute vec4 aPosition; attribute vec2 aTexCoord; uniform mat4 uTextureMatrix; varying vec2 vTexCoord; void main() { gl_Position = aPosition; vTexCoord = (uTextureMatrix * vec4(aTexCoord, 0.0, 1.0)).xy; }"
        val fs = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }"
        
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)

        aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
    }

    private fun compile(type: Int, code: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, code)
        GLES20.glCompileShader(s)
        return s
    }

    private fun drawFrame(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLocation)
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glUniformMatrix4fv(uTextureMatrixLocation, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releaseEgl() {
        sourceTexture?.release()
        inputSurface?.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglPreviewSurface)
            EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }
}
