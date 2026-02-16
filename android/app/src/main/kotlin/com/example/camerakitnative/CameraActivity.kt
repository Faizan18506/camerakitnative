package com.example.camerakitnative

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.snap.camerakit.Session
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.whenHasSome
import com.snap.camerakit.supported
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import com.snap.camerakit.support.permissions.HeadlessFragmentPermissionRequester
import java.io.Closeable

class CameraActivity : AppCompatActivity() {

    private val TAG = "CameraActivity1"
    private val LENS_GROUP_ID = "b2746ec0-d32d-4f48-94cc-1bb02dd4664f"

    private lateinit var imageProcessorSource: CameraXImageProcessorSource
    private lateinit var cameraKitSession: Session
    private lateinit var progressBar: ProgressBar
    
    private lateinit var lensesRecyclerView: RecyclerView
    private lateinit var lensesAdapter: LensesAdapter
    
    private var permissionRequest: Closeable? = null
    private var lensRepositorySubscription: Closeable? = null
    private var isCameraFacingFront = true

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.CameraKitTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        Log.d(TAG, "CameraActivity started (Reference: Sample 1.46.0)")

        if (!supported(this)) {
            Toast.makeText(this, "Camera Kit not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar = findViewById(R.id.progress_bar)
        progressBar.visibility = View.VISIBLE
        lensesRecyclerView = findViewById(R.id.lenses_recycler_view)
        
        // Setup Flip Button
        findViewById<ImageButton>(R.id.camera_flip_button).setOnClickListener {
            flipCamera()
        }

        lensesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lensesAdapter = LensesAdapter { selectedLens ->
            applyLens(selectedLens)
        }
        lensesRecyclerView.adapter = lensesAdapter

        imageProcessorSource = CameraXImageProcessorSource(
            context = this, lifecycleOwner = this
        )

        cameraKitSession = Session(this) {
            imageProcessorSource(imageProcessorSource)
            attachTo(findViewById(R.id.camera_kit_stub))
        }

        getPermissions()

        lensRepositorySubscription = cameraKitSession.lenses.repository.observe(
            LensesComponent.Repository.QueryCriteria.Available(setOf(LENS_GROUP_ID))
        ) { result ->
            result.whenHasSome { lenses ->
                runOnUiThread {
                    lensesAdapter.submitList(lenses)
                    progressBar.visibility = View.GONE
                    if (lenses.isNotEmpty()) {
                        applyLens(lenses.first())
                    }
                }
            }
        }
    }

    private fun applyLens(lens: LensesComponent.Lens) {
        // Auto-flip camera if lens doesn't match current facing - Following Sample Logic
        val usingCorrectCamera = isCameraFacingFront.xor(lens.facingPreference != LensesComponent.Lens.Facing.FRONT)
        if (!usingCorrectCamera) {
            flipCamera()
        }

        cameraKitSession.lenses.processor.apply(lens) { success ->
            if (success) {
                runOnUiThread {
                    lensesAdapter.select(lens)
                }
            }
        }
    }

    private fun flipCamera() {
        runOnUiThread {
            isCameraFacingFront = !isCameraFacingFront
            imageProcessorSource.startPreview(isCameraFacingFront)
            Log.d(TAG, "Camera flipped. Front: $isCameraFacingFront")
        }
    }

    private fun getPermissions() {
        val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
        permissionRequest = HeadlessFragmentPermissionRequester(this, requiredPermissions.toSet()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true) {
                startPreview()
            } else {
                finish()
            }
        }
    }

    private fun startPreview() {
        imageProcessorSource.startPreview(isCameraFacingFront)
    }

    override fun onDestroy() {
        permissionRequest?.close()
        lensRepositorySubscription?.close()
        if (::cameraKitSession.isInitialized) {
            cameraKitSession.close()
        }
        super.onDestroy()
    }
}
