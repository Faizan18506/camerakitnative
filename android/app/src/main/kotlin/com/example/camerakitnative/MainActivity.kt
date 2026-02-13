package com.example.camerakitnative

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    // MethodChannel name must match the one in Flutter code
    private val CHANNEL_NAME = "com.example.camerakitnative/camera_kit"

    // Reference to MethodChannel to prevent garbage collection
    private var methodChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Create MethodChannel
        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME
        )

        // Set up method call handler
        methodChannel?.setMethodCallHandler { call, result ->
            // Handle method calls from Flutter
            when (call.method) {
                "launchCameraKit" -> {
                    val message = launchCameraKit()
                    result.success(message)
                }
                "getCameraStatus" -> {
                    val status = getCameraStatus()
                    result.success(status)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    /**
     * Launch Camera Kit - This is the main method called from Flutter
     * In a real implementation, this would open the camera interface
     */
    private fun launchCameraKit(): String {
        // TODO: Implement actual camera kit launch logic here
        // For now, we return a success message
        
        // Example of what you might do:
        // - Start camera activity
        // - Initialize camera preview
        // - Open camera view
        
        return "Camera Kit launched successfully! (Native Android)"
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
