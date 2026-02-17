package com.example.camerakitnative

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import java.io.IOException
import java.nio.ByteBuffer

/**
 * RTMP Manager manually orchestrating MediaCodec and RTMP pushing.
 * Ported from the reference @ai folder.
 */
class RtmpManager(private val context: Context, private val connectChecker: ConnectCheckerRtmp) {
    companion object {
        private const val TAG = "RTMP_STREAM"
    }

    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val videoBufferInfo = MediaCodec.BufferInfo()

    private val rtmpClient: RtmpClient = RtmpClient(connectChecker)

    @Volatile
    private var isStreaming = false
    private var videoThread: Thread? = null

    // Video Config (Upright Portrait for 480p)
    private var width = 480
    private var height = 854
    private var bitrate = 800000 // 800 Kbps - Optimized for stability
    private var fps = 30

    // Audio members
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioThread: Thread? = null
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBitrate = 96 * 1024 // 96 kbps audio

    fun configure(width: Int, height: Int, bitrate: Int, fps: Int) {
        this.width = width
        this.height = height
        this.bitrate = bitrate
        this.fps = fps
        Log.d(TAG, "Configured: ${width}x${height} @ $bitrate bps, $fps fps")
    }

    fun initStreaming(): Surface? {
        Log.d(TAG, "Initializing Video Encoder...")
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2s I-frame interval
            format.setInteger(MediaFormat.KEY_LATENCY, 0)

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder?.createInputSurface()

            Log.d(TAG, "Encoder & Surface Ready.")
            inputSurface
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create video encoder: ${e.message}")
            null
        }
    }

    fun startStream(rtmpUrl: String): Boolean {
        if (isStreaming) return true

        Log.d(TAG, "Connecting to: $rtmpUrl")
        isStreaming = true
        
        Thread({
            try {
                rtmpClient.connect(rtmpUrl)
                rtmpClient.setVideoResolution(width, height)
                
                // Start Video Pipeline
                videoEncoder?.start()
                videoThread = Thread({ drainVideoEncoder() }, "VideoEncoderThread")
                videoThread?.start()

                // Start Audio Pipeline
                startAudio()

                Log.d(TAG, "Direct RTMP Pipeline Started")
            } catch (e: Exception) {
                Log.e(TAG, "RTMP Start Exception: ${e.message}")
                stopStream()
            }
        }, "RTMP_Connect").start()

        return true
    }

    private fun drainVideoEncoder() {
        while (isStreaming && videoEncoder != null) {
            try {
                val index = videoEncoder!!.dequeueOutputBuffer(videoBufferInfo, 10000)
                if (index >= 0) {
                    val encodedData = videoEncoder!!.getOutputBuffer(index)
                    if (encodedData != null && videoBufferInfo.size > 0) {
                        encodedData.position(videoBufferInfo.offset)
                        encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)
                        rtmpClient.sendVideo(encodedData, videoBufferInfo)
                    }
                    videoEncoder!!.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = videoEncoder!!.outputFormat
                    val sps = newFormat.getByteBuffer("csd-0")
                    val pps = newFormat.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        rtmpClient.setVideoInfo(sps, pps, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Video Encoder Loop Error: ${e.message}")
                break
            }
        }
    }

    private fun startAudio() {
        try {
            // 1. Audio Encoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder?.start()

            // 2. Audio Record
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBufferSize * 2)
                audioRecord?.startRecording()
            } else {
                Log.e(TAG, "AudioRecord init failed: RECORD_AUDIO permission not granted")
            }

            // 3. Audio Thread
            audioThread = Thread({
                val buffer = ByteArray(4096)
                while (isStreaming && audioEncoder != null) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        try {
                            val inputIndex = audioEncoder!!.dequeueInputBuffer(10000)
                            if (inputIndex >= 0) {
                                val inputBuffer = audioEncoder!!.getInputBuffer(inputIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(buffer, 0, read)
                                audioEncoder!!.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                            }

                            var outputIndex = audioEncoder!!.dequeueOutputBuffer(audioBufferInfo, 10000)
                            while (outputIndex >= 0) {
                                val outputBuffer = audioEncoder!!.getOutputBuffer(outputIndex)
                                if (outputBuffer != null) {
                                    rtmpClient.setAudioInfo(sampleRate, true)
                                    rtmpClient.sendAudio(outputBuffer, audioBufferInfo)
                                }
                                audioEncoder!!.releaseOutputBuffer(outputIndex, false)
                                outputIndex = audioEncoder!!.dequeueOutputBuffer(audioBufferInfo, 10000)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Audio Encode Error: ${e.message}")
                        }
                    }
                }
            }, "AudioThread")
            audioThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Start Audio Failed: ${e.message}")
        }
    }

    fun stopStream() {
        Log.d(TAG, "Stopping Stream...")
        isStreaming = false
        
        try { rtmpClient.disconnect() } catch (ignored: Exception) {}

        videoEncoder?.let {
            try { it.stop() } catch (e: Exception) {}
            try { it.release() } catch (e: Exception) {}
        }
        videoEncoder = null

        audioEncoder?.let {
            try { it.stop() } catch (e: Exception) {}
            try { it.release() } catch (e: Exception) {}
        }
        audioEncoder = null

        audioRecord?.let {
            try { it.stop() } catch (e: Exception) {}
            try { it.release() } catch (e: Exception) {}
        }
        audioRecord = null
        
        inputSurface = null
        Log.d(TAG, "Stream Stopped and Cleaned.")
    }

    fun release() {
        stopStream()
    }
}
