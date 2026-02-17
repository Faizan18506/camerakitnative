package com.example.camerakitnative

import android.opengl.*
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class StreamReplicator(
    private val width: Int,
    private val height: Int
) {
    private val TAG = "RTMP_STREAM"

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurfaceDisplay = EGL14.EGL_NO_SURFACE
    private var eglSurfaceStream = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null

    private val vertexShaderCode = """
        attribute vec4 position;
        attribute vec2 inputTextureCoordinate;
        varying vec2 textureCoordinate;
        void main() {
            gl_Position = position;
            textureCoordinate = inputTextureCoordinate;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform samplerExternalOES videoTexture;
        void main() {
            gl_FragColor = texture2D(videoTexture, textureCoordinate);
        }
    """.trimIndent()

    private val vertices = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
    private val textureCoords = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)

    fun setup(displaySurface: Surface?, streamSurface: Surface?) {
        // Correcting EGL14 usage
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        if (displaySurface != null) {
            eglSurfaceDisplay = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], displaySurface, intArrayOf(EGL14.EGL_NONE), 0)
        }
        if (streamSurface != null) {
            eglSurfaceStream = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], streamSurface, intArrayOf(EGL14.EGL_NONE), 0)
        }

        EGL14.eglMakeCurrent(eglDisplay, 
            if (eglSurfaceDisplay != EGL14.EGL_NO_SURFACE) eglSurfaceDisplay else eglSurfaceStream,
            if (eglSurfaceDisplay != EGL14.EGL_NO_SURFACE) eglSurfaceDisplay else eglSurfaceStream, 
            eglContext)
        initShaders()
    }

    private fun initShaders() {
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode))
        GLES20.glAttachShader(program, loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode))
        GLES20.glLinkProgram(program)

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
        vertexBuffer?.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoords)
        textureBuffer?.position(0)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun render(textureId: Int, timestampNs: Long) {
        if (eglSurfaceDisplay != EGL14.EGL_NO_SURFACE) renderToSurface(eglSurfaceDisplay, textureId, timestampNs)
        if (eglSurfaceStream != EGL14.EGL_NO_SURFACE) renderToSurface(eglSurfaceStream, textureId, timestampNs)
    }

    private fun renderToSurface(surface: EGLSurface, textureId: Int, timestampNs: Long) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val posHandle = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        val texHandle = GLES20.glGetAttribLocation(program, "inputTextureCoordinate")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "videoTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        if (surface == eglSurfaceStream) EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, timestampNs)
        EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurfaceDisplay != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurfaceDisplay)
        if (eglSurfaceStream != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurfaceStream)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }
}
