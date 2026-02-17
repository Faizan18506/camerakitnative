package com.example.camerakitnative

import android.content.Context
import android.util.Log
import android.view.Surface
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.view.OpenGlView

/**
 * Advanced RtmpStreamManager that handles streaming from a custom Surface.
 */
class RtmpStreamManager(private val context: Context, private val connectChecker: ConnectCheckerRtmp) {

    private val TAG = "RTMP_STREAM"
    private var rtmpCamera: com.pedro.rtplibrary.rtmp.RtmpCamera1? = null
    private var isStreaming = false
    
    private val width = 720
    private val height = 1280
    private val fps = 30
    private val bitrate = 2000 * 1024

    init {
        val dummyView = OpenGlView(context)
        rtmpCamera = com.pedro.rtplibrary.rtmp.RtmpCamera1(dummyView, connectChecker)
    }

    fun startStream(url: String): Boolean {
        Log.d(TAG, "Starting RTMP Stream to $url")
        // Fix: 5th parameter is rotation (Int), not isEchoCancellation (Boolean) in this version
        if (rtmpCamera!!.prepareVideo(width, height, fps, bitrate, 0, 0)) {
            if (rtmpCamera!!.prepareAudio()) {
                rtmpCamera!!.startStream(url)
                isStreaming = true
                return true
            }
        }
        return false
    }

    fun stopStream() {
        if (isStreaming) {
            rtmpCamera?.stopStream()
            isStreaming = false
        }
    }

    fun release() {
        stopStream()
        rtmpCamera = null
    }

    fun isStreaming(): Boolean = isStreaming
    
    /**
     * Get the Input Surface from Pedro's library via reflection to avoid visibility issues.
     */
    fun getInputSurface(): Surface? {
        return try {
            val getEncoderMethod = rtmpCamera?.javaClass?.getMethod("getVideoEncoder")
            val encoder = getEncoderMethod?.invoke(rtmpCamera)
            val getInputSurfaceMethod = encoder?.javaClass?.getMethod("getInputSurface")
            getInputSurfaceMethod?.invoke(encoder) as? Surface
        } catch (e: Exception) {
            Log.e(TAG, "Reflection error: ${e.message}")
            null
        }
    }
}
