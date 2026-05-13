package com.example.chilidisease

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.chilidisease.camera.CameraController
import com.example.chilidisease.databinding.ActivityMainBinding
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.history.DetectionHistoryManager
import com.example.chilidisease.notification.DiseaseAlertManager
import com.example.chilidisease.utils.AppPreferences
import com.example.chilidisease.utils.FpsCounter
import com.example.chilidisease.utils.ImageUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import com.example.chilidisease.detector.ChiliDiseaseDetector
import kotlinx.coroutines.launch

/**
 * MainActivity — Activity kamera real-time.
 *
 * Fitur:
 *   • Deteksi real-time via CameraX + TFLite
 *   • Tap-to-focus + Pinch-to-zoom
 *   • Flash toggle
 *   • Pause / Resume
 *   • Buka galeri untuk analisis foto → PhotoAnalysisActivity
 *   • Ambil foto langsung → PhotoAnalysisActivity
 *   • Riwayat deteksi
 *   • Notifikasi penyakit
 */
class MainActivity : AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CameraViewModel by viewModels()
    private lateinit var chiliDiseaseDetector: ChiliDiseaseDetector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var appPrefs: AppPreferences
    private lateinit var cameraController: CameraController
    private lateinit var historyManager: DetectionHistoryManager
    private lateinit var alertManager: DiseaseAlertManager

    private var camera: Camera? = null
    private var frameW = 1
    private var frameH = 1
    private val fpsCounter = FpsCounter()
    private var lastDetection: DetectionResult? = null
    private var inferenceIntervalMs = 80L

    // ================================================================
    // LIFECYCLE
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appPrefs          = AppPreferences(this)
        cameraExecutor    = Executors.newSingleThreadExecutor()
        historyManager    = DetectionHistoryManager(this)
        alertManager      = DiseaseAlertManager(this)
        inferenceIntervalMs = appPrefs.inferenceIntervalMs
        chiliDiseaseDetector = ChiliDiseaseDetector(this)

        viewModel.initModel(appPrefs.buildModelConfig())
        observeViewModel()
        setupControls()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermIfNeeded()
        }

        if (hasCameraPermission()) startCamera() else requestCameraPermission()
    }



    override fun onResume() {
        super.onResume()
        appPrefs.registerChangeListener(this)
        inferenceIntervalMs = appPrefs.inferenceIntervalMs
    }

    override fun onPause() {
        super.onPause()
        appPrefs.unregisterChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraController.isInitialized) cameraController.detach()
        cameraExecutor.shutdown()
    }

    override fun onSharedPreferenceChanged(prefs: android.content.SharedPreferences?, key: String?) {
        when (key) {
            SettingsActivity.PrefsKey.MODEL_TYPE,
            SettingsActivity.PrefsKey.CONFIDENCE_THRESHOLD,
            SettingsActivity.PrefsKey.USE_GPU -> {
                viewModel.initModel(appPrefs.buildModelConfig())
                showToast("Model diperbarui")
            }
            SettingsActivity.PrefsKey.INFERENCE_INTERVAL -> {
                inferenceIntervalMs = appPrefs.inferenceIntervalMs
            }
        }
    }

    // ================================================================
    // OBSERVE VIEWMODEL
    // ================================================================

    private fun observeViewModel() {
        viewModel.modelReady.observe(this) { ready ->
            if (ready) binding.tvStatus.text = getString(R.string.status_detecting)
        }

        viewModel.detections.observe(this) { results ->
            updateDetectionUI(results)
        }

        viewModel.fps.observe(this) { fps ->
            binding.tvFps.text = getString(R.string.fps_format, fps)
        }

        viewModel.inferenceMs.observe(this) { ms ->
            if (ms > 0L) {
                binding.tvFps.text = "${binding.tvFps.text} | ${ms}ms"
            }
        }

        viewModel.errorMessage.observe(this) { msg ->
            msg?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                binding.tvStatus.text = getString(R.string.status_error)
                viewModel.clearError()
            }
        }

        viewModel.isDetecting.observe(this) { detecting ->
            binding.btnPause.text = if (detecting)
                getString(R.string.btn_pause) else getString(R.string.btn_resume)
            binding.tvStatus.text = if (detecting)
                getString(R.string.status_detecting) else getString(R.string.status_paused)
            if (!detecting) {
                binding.overlayView.clearDetections()
                binding.cardResult.visibility = View.GONE
            }
        }

        val daftarAset = assets.list("")
        android.util.Log.e("CEK_ASSET_SAYA", "Isi folder assets di HP ini: ${daftarAset?.joinToString(", ")}")
    }

    // ================================================================
    // CONTROLS
    // ================================================================

    private fun setupControls() {
        binding.btnFlash.setOnClickListener   { toggleFlash() }
        binding.btnPause.setOnClickListener   { viewModel.toggleDetection() }
        binding.btnInfo.setOnClickListener    { showDiseaseInfoDialog() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, DetectionHistoryActivity::class.java))
        }
        binding.btnShare.setOnClickListener   { shareCurrentDetection() }

        binding.btnOpenGallery.setOnClickListener {
            startActivity(
                Intent(this, PhotoAnalysisActivity::class.java).apply {
                    putExtra(PhotoAnalysisActivity.EXTRA_SOURCE, PhotoAnalysisActivity.SOURCE_GALLERY)
                }
            )
        }

        binding.btnTakePhoto.setOnClickListener {
            startActivity(
                Intent(this, PhotoAnalysisActivity::class.java).apply {
                    putExtra(PhotoAnalysisActivity.EXTRA_SOURCE, PhotoAnalysisActivity.SOURCE_CAMERA)
                }
            )
        }
    }

    // ================================================================
    // UPDATE UI
    // ================================================================

    private fun updateDetectionUI(results: List<DetectionResult>) {
        binding.overlayView.setDetections(results, frameW, frameH)

        if (results.isEmpty()) {
            binding.cardResult.visibility = View.GONE
            lastDetection = null
            if (viewModel.isDetecting.value == true)
                binding.tvStatus.text = getString(R.string.status_detecting)
            return
        }

        val top = results.maxByOrNull { it.confidence }!!
        lastDetection = top
        renderResultCard(top)
        binding.cardResult.visibility = View.VISIBLE
        binding.tvStatus.text = if (top.classIndex == 0)
            getString(R.string.status_healthy) else getString(R.string.status_disease)

        if (appPrefs.saveHistoryEnabled && top.confidence >= 0.70f)
            historyManager.record(top)

        if (appPrefs.notificationsEnabled)
            alertManager.maybeAlert(top)
    }

    private fun renderResultCard(r: DetectionResult) {
        binding.tvDiseaseName.text  = r.label
        binding.tvConfidence.text   = r.confidencePercent
        binding.tvDescription.text  = r.diseaseDescription

        val colorRes = when (r.classIndex) {
            0    -> R.color.color_healthy
            1    -> R.color.color_anthracnose
            2    -> R.color.color_fusarium
            else -> R.color.color_unknown
        }
        binding.cardResult.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    // ================================================================
    // CAMERAX
    // ================================================================

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            bindCamera(ProcessCameraProvider.getInstance(this).get())
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(cameraExecutor, FrameAnalyzer()) }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
            cameraController = CameraController(
                context       = this,
                camera        = camera,
                previewView   = binding.previewView,
                focusRingView = binding.ivFocusRing,
                tvZoomLevel   = binding.tvZoomLevel,
                scope         = lifecycleScope
            )
            cameraController.attachToPreview()
            Log.d(TAG, "✅ Kamera aktif")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal bind kamera: ${e.message}")
            showToast("Gagal membuka kamera")
        }
    }

    // ================================================================
    // FRAME ANALYZER
    // ================================================================

    inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        private var lastTime = 0L

        override fun analyze(imageProxy: ImageProxy) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastTime < inferenceIntervalMs) return
                if (viewModel.isDetecting.value != true) return

                frameW = imageProxy.width
                frameH = imageProxy.height

                val bmp = ImageUtils.imageProxyToBitmap(imageProxy)
                viewModel.submitFrame(bmp, frameW, frameH)
                fpsCounter.tick()
                lastTime = now
            } catch (e: Exception) {
                Log.e(TAG, "Frame error: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }
    }

    // ================================================================
    // FLASH
    // ================================================================

    private fun toggleFlash() {
        if (!::cameraController.isInitialized) return
        val isOn = cameraController.toggleTorch()
        binding.btnFlash.text = if (isOn)
            getString(R.string.btn_flash_on) else getString(R.string.btn_flash)
    }

    // ================================================================
    // SHARE
    // ================================================================

    private fun shareCurrentDetection() {
        val d = lastDetection ?: run { showToast("Belum ada deteksi"); return }
        val text = buildString {
            appendLine("🌶️ Hasil Deteksi Penyakit Cabai (Real-time)")
            appendLine()
            appendLine("Penyakit : ${d.label}")
            appendLine("Akurasi  : ${d.confidencePercent}")
            appendLine("Info     : ${d.diseaseDescription}")
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Deteksi Penyakit Cabai")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Bagikan Hasil"
        ))
    }

    // ================================================================
    // INFO DIALOG
    // ================================================================

    private fun showDiseaseInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_info_title))
            .setMessage(getString(R.string.dialog_info_message))
            .setPositiveButton(getString(R.string.dialog_close), null)
            .setNeutralButton("Pengaturan") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .show()
    }

    // ================================================================
    // PERMISSIONS
    // ================================================================

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else showPermissionDialog() }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* opsional */ }

    private fun requestCameraPermission() =
        cameraPermLauncher.launch(Manifest.permission.CAMERA)

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required_title))
            .setMessage(getString(R.string.permission_required_message))
            .setPositiveButton(getString(R.string.permission_settings)) { _, _ ->
                val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = android.net.Uri.fromParts("package", packageName, null)
                startActivity(i)
            }
            .setNegativeButton(getString(R.string.dialog_close)) { _, _ -> finish() }
            .show()
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
