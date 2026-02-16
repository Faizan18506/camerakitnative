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

    private val TAG = "CameraActivity"
    private val LENS_GROUP_ID = "b2746ec0-d32d-4f48-94cc-1bb02dd4664f"

    private lateinit var imageProcessorSource: CameraXImageProcessorSource
    private lateinit var cameraKitSession: Session
    private lateinit var progressBar: ProgressBar

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startPreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.CameraKitTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        Log.d(TAG, "CameraActivity started")

        progressBar = findViewById(R.id.progress_bar)
        progressBar.visibility = View.VISIBLE

        // Step 3: Check support
        if (!supported(this)) {
            Toast.makeText(this, "Camera Kit not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Step 4: Initialize processor
        imageProcessorSource = CameraXImageProcessorSource(
            context = this, lifecycleOwner = this
        )

        // Step 7: Handle permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreview()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Step 9 & 10: Initialize Session
        cameraKitSession = Session(context = this) {
            imageProcessorSource(imageProcessorSource)
            attachTo(findViewById(R.id.camera_kit_stub))
        }
        // Section 3 - Step 1 & 2: Apply Lens
        .apply {
            lenses.repository.observe(
                LensesComponent.Repository.QueryCriteria.Available(LENS_GROUP_ID)
            ) { result ->
                result.whenHasFirst { requestedLens ->
                    Log.d(TAG, "Applying lens: ${requestedLens.name}")
                    lenses.processor.apply(requestedLens)
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun startPreview() {
        imageProcessorSource.startPreview(true)
    }

    override fun onDestroy() {
        // Step 3 (Section 3): Close session
        if (::cameraKitSession.isInitialized) {
            cameraKitSession.close()
        }
        super.onDestroy()
    }
}
