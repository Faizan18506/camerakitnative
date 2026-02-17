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
    private val RTMP_TAG = "RTMP_STREAM"
    private val LENS_GROUP_ID = "b2746ec0-d32d-4f48-94cc-1bb02dd4664f"
    private val RTMP_URL = "rtmp://95.217.67.76:1935/live/test"

    private lateinit var imageProcessorSource: CameraXImageProcessorSource
    private lateinit var cameraKitSession: Session
    private lateinit var progressBar: ProgressBar
    
    private lateinit var lensesRecyclerView: RecyclerView
    private lateinit var lensesAdapter: LensesAdapter
    
    private lateinit var rtmpStreamManager: RtmpStreamManager
    private var streamReplicator: StreamReplicator? = null
    
    private var permissionRequest: Closeable? = null
    private var lensRepositorySubscription: Closeable? = null
    private var isCameraFacingFront = true
    
    private var isLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.CameraKitTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        Log.d(TAG, "CameraActivity started - Direct RTMP Mode")

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
        btnGoLive.setOnClickListener { handleStreamingToggle() }

        findViewById<ImageButton>(R.id.camera_flip_button).setOnClickListener { flipCamera() }

        lensesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lensesAdapter = LensesAdapter { selectedLens -> applyLens(selectedLens) }
        lensesRecyclerView.adapter = lensesAdapter

        imageProcessorSource = CameraXImageProcessorSource(context = this, lifecycleOwner = this)
        streamReplicator = StreamReplicator(480, 854)

        cameraKitSession = Session(this) {
            imageProcessorSource(imageProcessorSource)
            attachTo(findViewById<ViewStub>(R.id.camera_kit_stub))
        }

        cameraKitSession.processor.connectOutput(object : ImageProcessor.Output {
            override val purpose: ImageProcessor.Output.Purpose = ImageProcessor.Output.Purpose.PREVIEW
            override fun writeFrame(): ImageProcessor.Output.Frame {
                return object : ImageProcessor.Output.Frame {
                    override val timestamp: Long get() = System.nanoTime()
                    override fun recycle() {}
                }
            }
            // Real-time link
            fun onFrame(frame: ImageProcessor.Output.Frame) {
                if (isLive) {
                    try {
                        val textureIdField = frame.javaClass.getDeclaredField("textureId")
                        textureIdField.isAccessible = true
                        val textureId = textureIdField.get(frame) as Int
                        streamReplicator?.render(textureId, frame.timestamp)
                    } catch (e: Exception) {}
                }
            }
        })

        getPermissions()

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

    private fun handleStreamingToggle() {
        val btnGoLive = findViewById<Button>(R.id.btn_go_live)
        if (isLive) {
            isLive = false
            rtmpStreamManager.stopStream()
            btnGoLive.text = "GO LIVE"
            btnGoLive.setBackgroundColor(0xFFFF0000.toInt())
        } else {
            Log.d(RTMP_TAG, "Go Live Clicked - Starting Direct Stream")
            if (rtmpStreamManager.startStream(RTMP_URL)) {
                val streamSurface = rtmpStreamManager.getInputSurface()
                if (streamSurface != null) {
                    streamReplicator?.setup(null, streamSurface)
                    isLive = true
                    btnGoLive.text = "STOP"
                    btnGoLive.setBackgroundColor(0xFF00FF00.toInt())
                } else {
                    rtmpStreamManager.stopStream()
                    Toast.makeText(this, "Surface Acquisition Failed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Connect Failed", Toast.LENGTH_SHORT).show()
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
            startPreview()
        }
    }

    private fun getPermissions() {
        val requiredPerms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        permissionRequest = HeadlessFragmentPermissionRequester(this, requiredPerms.toSet()) { perms ->
            if (perms[Manifest.permission.CAMERA] == true && perms[Manifest.permission.RECORD_AUDIO] == true) {
                startPreview()
            } else {
                Toast.makeText(this, "Permissions Needed", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startPreview() { imageProcessorSource.startPreview(isCameraFacingFront) }

    override fun onDestroy() {
        permissionRequest?.close()
        lensRepositorySubscription?.close()
        rtmpStreamManager.release()
        streamReplicator?.release()
        if (::cameraKitSession.isInitialized) cameraKitSession.close()
        super.onDestroy()
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) {}
    override fun onConnectionSuccessRtmp() {
        runOnUiThread { Toast.makeText(this, "LIVE!", Toast.LENGTH_SHORT).show() }
    }
    override fun onConnectionFailedRtmp(reason: String) {
        runOnUiThread {
            isLive = false
            rtmpStreamManager.stopStream()
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
