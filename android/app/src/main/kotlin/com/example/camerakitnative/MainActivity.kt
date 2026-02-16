package com.example.camerakitnative

import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val TAG = "MainActivityNative"
    private val CHANNEL_NAME = "com.example.camerakitnative/camera_kit"
    private var methodChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME
        )

        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "launchCameraKit" -> {
                    Log.d(TAG, "launchCameraKit method called from Flutter")
                    try {
                        val intent = Intent(this, CameraActivity::class.java)
                        startActivity(intent)
                        result.success("Camera Activity Started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching CameraActivity: ${e.message}")
                        result.error("LAUNCH_ERROR", e.message, null)
                    }
                }
                "getCameraStatus" -> {
                    result.success("Ready")
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun launchCameraKit(): String {
        return "Legacy method - not used"
    }

    /**
     * Get the current camera status
     */
    private fun getCameraStatus(): String {
        return "Camera is ready"
    }

    override fun onDestroy() {
        // Clean up MethodChannel when activity is destroyed
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        super.onDestroy()
    }
}
