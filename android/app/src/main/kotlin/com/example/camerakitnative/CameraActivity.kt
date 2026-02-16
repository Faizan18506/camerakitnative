package com.example.camerakitnative

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.snap.camerakit.Session
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.whenHasFirst
import com.snap.camerakit.supported
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource

class CameraActivity : AppCompatActivity() {

    private val TAG = "CameraActivity1"
    private val LENS_GROUP_ID = "b2746ec0-d32d-4f48-94cc-1bb02dd4664f"

    private lateinit var imageProcessorSource: CameraXImageProcessorSource
    private lateinit var cameraKitSession: Session
    private lateinit var progressBar: ProgressBar

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startPreview()
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.CameraKitTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        progressBar = findViewById(R.id.progress_bar)
        progressBar.visibility = View.VISIBLE

        if (!supported(this)) {
            Toast.makeText(this, "Camera Kit not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        imageProcessorSource = CameraXImageProcessorSource(
            context = this, lifecycleOwner = this
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreview()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        try {
            cameraKitSession = Session(context = this) {
                imageProcessorSource(imageProcessorSource)
                attachTo(findViewById(R.id.camera_kit_stub))
            }

            cameraKitSession.lenses.repository.observe(
                LensesComponent.Repository.QueryCriteria.Available(LENS_GROUP_ID)
            ) { result ->
                val lensList = result.lenses
                Log.d(TAG, "Lenses found: ${lensList.size}")

                // Null-safe search for an interesting lens
                val targetLens = lensList.find { it.name?.contains("Distort", ignoreCase = true) == true } 
                                 ?: if (lensList.size > 3) lensList[3] else lensList.firstOrNull()

                targetLens?.let { lens ->
                    Log.d(TAG, "Applying lens: ${lens.name} (ID: ${lens.id})")
                    cameraKitSession.lenses.processor.apply(lens)
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Setup error: ${e.message}")
        }
    }

    private fun startPreview() {
        imageProcessorSource.startPreview(true)
    }

    override fun onDestroy() {
        if (::cameraKitSession.isInitialized) {
            cameraKitSession.close()
        }
        super.onDestroy()
    }
}

// Helper to handle sealed class result
private val LensesComponent.Repository.Result.lenses: List<LensesComponent.Lens>
    get() = when (this) {
        is LensesComponent.Repository.Result.Some -> lenses
        else -> emptyList()
    }
