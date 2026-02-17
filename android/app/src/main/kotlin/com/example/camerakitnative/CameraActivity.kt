package com.example.camerakitnative

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.snap.camerakit.Session
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.whenHasSome
import com.snap.camerakit.supported
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import com.snap.camerakit.support.permissions.HeadlessFragmentPermissionRequester
import java.io.Closeable

class CameraActivity : AppCompatActivity(), ConnectCheckerRtmp {

    private val TAG = "CameraActivity1"
    private val RTMP_TAG = "RTMP_STREAM"
    private val LENS_GROUP_ID = "b2746ec0-d32d-4f48-94cc-1bb02dd4664f"
    private val RTMP_URL = "rtmp://65.109.37.43:1935/live/test"

    private lateinit var imageProcessorSource: CameraXImageProcessorSource
    private lateinit var cameraKitSession: Session
    private lateinit var progressBar: ProgressBar
    
    private lateinit var lensesRecyclerView: RecyclerView
    private lateinit var lensesAdapter: LensesAdapter
    
    private lateinit var rtmpStreamManager: RtmpStreamManager
    
    private var permissionRequest: Closeable? = null
    private var lensRepositorySubscription: Closeable? = null
    private var isCameraFacingFront = true

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.CameraKitTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        Log.d(TAG, "CameraActivity started - Checking Components")

        if (!supported(this)) {
            Toast.makeText(this, "Camera Kit not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar = findViewById(R.id.progress_bar)
        progressBar.visibility = View.VISIBLE
        lensesRecyclerView = findViewById(R.id.lenses_recycler_view)
        
        rtmpStreamManager = RtmpStreamManager(this, this)
        
        val btnGoLive = findViewById<Button>(R.id.btn_go_live)
        btnGoLive.setOnClickListener {
            handleStreamingToggle()
        }

        findViewById<ImageButton>(R.id.camera_flip_button).setOnClickListener {
            flipCamera()
        }

        lensesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lensesAdapter = LensesAdapter { selectedLens ->
            applyLens(selectedLens)
        }
        lensesRecyclerView.adapter = lensesAdapter

        imageProcessorSource = CameraXImageProcessorSource(
            context = this,
            lifecycleOwner = this
        )

        cameraKitSession = Session(this) {
            imageProcessorSource(imageProcessorSource)
            attachTo(findViewById<ViewStub>(R.id.camera_kit_stub))
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

    private fun handleStreamingToggle() {
        val btnGoLive = findViewById<Button>(R.id.btn_go_live)
        if (rtmpStreamManager.isStreaming()) {
            rtmpStreamManager.stopStream()
            btnGoLive.text = "GO LIVE"
            btnGoLive.setBackgroundColor(0xFFFF0000.toInt())
        } else {
            if (rtmpStreamManager.startStream(RTMP_URL)) {
                btnGoLive.text = "STOP"
                btnGoLive.setBackgroundColor(0xFF00FF00.toInt())
            }
        }
    }

    private fun applyLens(lens: LensesComponent.Lens) {
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
            startPreview()
        }
    }

    private fun getPermissions() {
        val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        permissionRequest = HeadlessFragmentPermissionRequester(this, requiredPermissions.toSet()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true && permissions[Manifest.permission.RECORD_AUDIO] == true) {
                startPreview()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
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
        rtmpStreamManager.release()
        if (::cameraKitSession.isInitialized) {
            cameraKitSession.close()
        }
        super.onDestroy()
    }

    // --- ConnectCheckerRtmp Implementation ---
    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        Log.d(RTMP_TAG, "Connection started: $rtmpUrl")
    }

    override fun onConnectionSuccessRtmp() {
        runOnUiThread { Log.d(RTMP_TAG, "Connected") }
    }

    override fun onConnectionFailedRtmp(reason: String) {
        runOnUiThread {
            Log.e(RTMP_TAG, "Failed: $reason")
            rtmpStreamManager.stopStream()
            val btn = findViewById<Button>(R.id.btn_go_live)
            btn.text = "GO LIVE"
            btn.setBackgroundColor(0xFFFF0000.toInt())
        }
    }

    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() {}
    override fun onAuthErrorRtmp() {}
    override fun onAuthSuccessRtmp() {}
}
