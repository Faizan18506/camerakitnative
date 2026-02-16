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
        
        // Setup Custom Carousel
        lensesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lensesAdapter = LensesAdapter { selectedLens ->
            Log.d(TAG, "Applying lens: ${selectedLens.name}")
            applyLens(selectedLens)
        }
        lensesRecyclerView.adapter = lensesAdapter

        // Initialize CameraX source
        imageProcessorSource = CameraXImageProcessorSource(
            context = this, lifecycleOwner = this
        )

        // Initialize Session - Following 1.46.0 Sample
        cameraKitSession = Session(this) {
            imageProcessorSource(imageProcessorSource)
            attachTo(findViewById(R.id.camera_kit_stub))
        }

        // Handle Permissions using HeadlessFragmentPermissionRequester from support-permissions
        getPermissions()

        // Observe Repository - Following 1.46.0 Sample
        lensRepositorySubscription = cameraKitSession.lenses.repository.observe(
            LensesComponent.Repository.QueryCriteria.Available(setOf(LENS_GROUP_ID))
        ) { result ->
            result.whenHasSome { lenses ->
                Log.d(TAG, "Lenses loaded: ${lenses.size}")
                runOnUiThread {
                    lensesAdapter.submitList(lenses)
                    progressBar.visibility = View.GONE
                    
                    // Apply first lens by default if desired
                    if (lenses.isNotEmpty()) {
                        applyLens(lenses.first())
                    }
                }
            }
        }
    }

    private fun applyLens(lens: LensesComponent.Lens) {
        cameraKitSession.lenses.processor.apply(lens) { success ->
            if (success) {
                runOnUiThread {
                    lensesAdapter.select(lens)
                }
            }
        }
    }

    private fun getPermissions() {
        val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
        permissionRequest = HeadlessFragmentPermissionRequester(this, requiredPermissions.toSet()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true) {
                startPreview()
            } else {
                Log.e(TAG, "Camera permission denied")
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
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
