package com.example.camerakitnative

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAacData
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import java.nio.ByteBuffer

/**
 * Direct RtmpStreamManager that handles Video/Audio encoding and RTMP 
 * protocol without touching Camera or Screen hardware.
 */
class RtmpStreamManager(private val context: Context, private val connectChecker: ConnectCheckerRtmp) : 
    GetVideoData, GetAacData, GetMicrophoneData {

    private val TAG = "RTMP_STREAM"
    
    // Components
    private val rtmpClient = RtmpClient(connectChecker)
    private val videoEncoder = VideoEncoder(this)
    private val audioEncoder = AudioEncoder(this)
    private val microphoneManager = MicrophoneManager(this)

    private var isStreaming = false
    
    // Stable 480p Configuration
    private val width = 480
    private val height = 854
    private val fps = 30
    private val bitrate = 1200 * 1024 
    private val sampleRate = 32000
    private val isStereo = true

    fun startStream(url: String): Boolean {
        Log.d(TAG, "Initializing Direct Stream to $url")
        
        // 1. Prepare Video Encoder
        videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, 0, 2, FormatVideoEncoder.SURFACE)
        
        // 2. Prepare Audio Encoder
        audioEncoder.prepareAudioEncoder(bitrate / 10, sampleRate, isStereo, 0)
        
        // 3. Connect RTMP
        rtmpClient.setVideoResolution(width, height)
        rtmpClient.setFps(fps)
        rtmpClient.setAudioInfo(sampleRate, isStereo)
        
        rtmpClient.connect(url)
        
        videoEncoder.start()
        audioEncoder.start()
        microphoneManager.start()
        isStreaming = true
        Log.d(TAG, "Direct Stream Started")
        return true
    }

    fun stopStream() {
        if (isStreaming) {
            isStreaming = false
            rtmpClient.disconnect()
            videoEncoder.stop()
            audioEncoder.stop()
            microphoneManager.stop()
            Log.d(TAG, "Direct Stream Stopped")
        }
    }

    fun release() {
        stopStream()
    }

    fun isStreaming(): Boolean = isStreaming
    
    fun getInputSurface(): Surface? {
        return videoEncoder.inputSurface
    }

    // --- Video Callbacks ---
    override fun onVideoFormat(mediaFormat: android.media.MediaFormat) {}
    override fun getVideoData(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        rtmpClient.sendVideo(h264Buffer, info)
    }
    override fun onSpsPpsVps(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
        rtmpClient.setVideoInfo(sps, pps, vps)
    }

    // --- Audio Callbacks ---
    override fun onAudioFormat(mediaFormat: android.media.MediaFormat) {}
    override fun getAacData(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        rtmpClient.sendAudio(aacBuffer, info)
    }

    // --- Microphone Callbacks (Fixing for direct Frame object passing) ---
    override fun inputPCMData(frame: com.pedro.encoder.Frame) {
        // Fix: Pass the frame object directly as required by AudioEncoder.java
        audioEncoder.inputPCMData(frame)
    }
}
