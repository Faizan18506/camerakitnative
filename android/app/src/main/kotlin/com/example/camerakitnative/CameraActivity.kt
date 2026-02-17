package com.example.camerakitnative

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.snap.camerakit.ImageProcessor
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
    private val LENS_GROUP_ID = "b2746ec0-d32d-4f48-94cc-1bb02dd4664f"
    private val RTMP_URL = "rtmp://95.217.67.76:1935/live/test"

    private lateinit var imageProcessorSource: CameraXImageProcessorSource
    private lateinit var cameraKitSession: Session
    private lateinit var progressBar: ProgressBar
    
    private lateinit var lensesRecyclerView: RecyclerView
    private lateinit var lensesAdapter: LensesAdapter
    
    // New simplified architecture
    private lateinit var rtmpManager: RtmpManager
    private var surfaceReplicator: SurfaceReplicator? = null
    
    private var permissionRequest: Closeable? = null
    private var lensRepositorySubscription: Closeable? = null
    private var isCameraFacingFront = true
    private var isLive = false
    
    private var displaySurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.CameraKitTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        if (!supported(this)) {
            Toast.makeText(this, "Camera Kit not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar = findViewById(R.id.progress_bar)
        progressBar.visibility = View.VISIBLE
        lensesRecyclerView = findViewById(R.id.lenses_recycler_view)
        
        rtmpManager = RtmpManager(this, this)
        
        val btnGoLive = findViewById<Button>(R.id.btn_go_live)
        btnGoLive.setOnClickListener { handleStreamingToggle() }

        findViewById<ImageButton>(R.id.camera_flip_button).setOnClickListener { flipCamera() }

        lensesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lensesAdapter = LensesAdapter { selectedLens -> applyLens(selectedLens) }
        lensesRecyclerView.adapter = lensesAdapter

        imageProcessorSource = CameraXImageProcessorSource(context = this, lifecycleOwner = this)

        // Setup the manual SurfaceView for preview
        val surfaceView = findViewById<SurfaceView>(R.id.manual_preview_view)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                displaySurface = holder.surface
                initPipeline()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                displaySurface = null
                stopPipeline()
            }
        })

        getPermissions()
    }

    private fun initPipeline() {
        // 1. Initialize RtmpManager Video Encoder to get its Surface
        val encoderSurface = rtmpManager.initStreaming()
        
        // 2. Setup SurfaceReplicator (Main Preview + Encoder)
        surfaceReplicator?.stop()
        surfaceReplicator = SurfaceReplicator(displaySurface, encoderSurface, 480, 854)
        surfaceReplicator?.start()

        // 3. Get the "Input" surface that Replicator provides, which Camera Kit will draw into
        val replicatorInput = surfaceReplicator?.awaitInputSurface(2000)
        
        cameraKitSession = Session(this) {
            imageProcessorSource(imageProcessorSource)
        }

        // 4. Connect Camera Kit output to the Replicator Input Surface
        if (replicatorInput != null) {
            cameraKitSession.processor.connectOutput(object : ImageProcessor.Output.BackedBySurface(
                replicatorInput, 
                ImageProcessor.Output.Purpose.RECORDING
            ) {
                override fun writeFrame(): ImageProcessor.Output.Frame {
                    return object : ImageProcessor.Output.Frame {
                        override val timestamp: Long get() = System.nanoTime()
                        override fun recycle() {}
                    }
                }
            })
        }

        // Observe Lenses
        lensRepositorySubscription = cameraKitSession.lenses.repository.observe(
            LensesComponent.Repository.QueryCriteria.Available(setOf(LENS_GROUP_ID))
        ) { result ->
            result.whenHasSome { lenses ->
                runOnUiThread {
                    lensesAdapter.submitList(lenses)
                    progressBar.visibility = View.GONE
                    if (lenses.isNotEmpty()) applyLens(lenses.first())
                }
            }
        }
    }

    private fun stopPipeline() {
        surfaceReplicator?.stop()
        surfaceReplicator = null
        if (::cameraKitSession.isInitialized) cameraKitSession.close()
    }

    private fun handleStreamingToggle() {
        val btnGoLive = findViewById<Button>(R.id.btn_go_live)
        if (isLive) {
            isLive = false
            rtmpManager.stopStream()
            btnGoLive.text = "GO LIVE"
            btnGoLive.setBackgroundColor(0xFFFF0000.toInt())
        } else {
            if (rtmpManager.startStream(RTMP_URL)) {
                isLive = true
                btnGoLive.text = "STOP"
                btnGoLive.setBackgroundColor(0xFF00FF00.toInt())
            }
        }
    }

    private fun applyLens(lens: LensesComponent.Lens) {
        val usingCorrectCamera = isCameraFacingFront.xor(lens.facingPreference != LensesComponent.Lens.Facing.FRONT)
        if (!usingCorrectCamera) flipCamera()
        cameraKitSession.lenses.processor.apply(lens) { success ->
            if (success) runOnUiThread { lensesAdapter.select(lens) }
        }
    }

    private fun flipCamera() {
        runOnUiThread {
            isCameraFacingFront = !isCameraFacingFront
            imageProcessorSource.startPreview(isCameraFacingFront)
        }
    }

    private fun getPermissions() {
        val requiredPerms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        permissionRequest = HeadlessFragmentPermissionRequester(this, requiredPerms.toSet()) { perms ->
            if (perms[Manifest.permission.CAMERA] == true && perms[Manifest.permission.RECORD_AUDIO] == true) {
                imageProcessorSource.startPreview(isCameraFacingFront)
            } else {
                Toast.makeText(this, "Permissions Needed", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        permissionRequest?.close()
        lensRepositorySubscription?.close()
        rtmpManager.release()
        stopPipeline()
        super.onDestroy()
    }

    // RTMP Callbacks
    override fun onConnectionStartedRtmp(rtmpUrl: String) {}
    override fun onConnectionSuccessRtmp() {
        runOnUiThread { Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show() }
    }
    override fun onConnectionFailedRtmp(reason: String) {
        runOnUiThread {
            isLive = false
            rtmpManager.stopStream()
            findViewById<Button>(R.id.btn_go_live).apply {
                text = "GO LIVE"; setBackgroundColor(0xFFFF0000.toInt())
            }
            Toast.makeText(this, "Error: $reason", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() {}
    override fun onAuthErrorRtmp() {}
    override fun onAuthSuccessRtmp() {}
}
