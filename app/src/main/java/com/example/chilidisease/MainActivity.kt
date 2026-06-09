package com.example.chilidisease

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.lifecycleScope
import com.example.chilidisease.camera.CameraController
import com.example.chilidisease.databinding.ActivityMainBinding
import com.example.chilidisease.detector.ChiliPresenceGate
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.detector.DiseaseInfo
import com.example.chilidisease.detector.RoboflowRemoteDetector
import com.example.chilidisease.history.DetectionHistoryManager
import com.example.chilidisease.notification.DiseaseAlertManager
import com.example.chilidisease.utils.AppPreferences
import com.example.chilidisease.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "MainActivity"

        /*
         * Realtime Roboflow jangan terlalu cepat.
         * 3000ms = 1 request setiap 3 detik.
         */
        private const val ROBOFLOW_REALTIME_INTERVAL_MS = 3000L

        /*
         * Update UI "Tidak Ada Cabai" maksimal 1x per 1 detik.
         */
        private const val NO_CHILI_UI_INTERVAL_MS = 1000L
    }

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var appPrefs: AppPreferences
    private lateinit var cameraController: CameraController
    private lateinit var historyManager: DetectionHistoryManager
    private lateinit var alertManager: DiseaseAlertManager
    private lateinit var roboflowDetector: RoboflowRemoteDetector

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isBindingCamera = false

    private var frameW = 1
    private var frameH = 1

    private var lastDetection: DetectionResult? = null

    private var isPausedByUser = false
    private var isOpeningPhotoAnalysis = false

    @Volatile
    private var isRoboflowRequestRunning = false

    private var lastRoboflowRequestTime = 0L
    private var lastNoChiliUpdateTime = 0L

    // ================================================================
    // LIFECYCLE
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appPrefs = AppPreferences(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        historyManager = DetectionHistoryManager(this)
        alertManager = DiseaseAlertManager(this)
        roboflowDetector = RoboflowRemoteDetector(this)

        setupControls()

        binding.tvStatus.text = "Mendeteksi..."
        binding.tvFps.text = "API | 3s"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermIfNeeded()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()

        appPrefs.registerChangeListener(this)

        isOpeningPhotoAnalysis = false
        isPausedByUser = false
        isRoboflowRequestRunning = false

        binding.btnPause.text = "⏸ PAUSE"
        binding.tvStatus.text = "Mendeteksi..."
        binding.tvFps.text = "API | 3s"

        if (hasCameraPermission() && cameraProvider == null && !isBindingCamera) {
            binding.previewView.postDelayed({
                startCamera()
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()

        appPrefs.unregisterChangeListener(this)

        isRoboflowRequestRunning = false

        /*
         * Jangan pakai cameraProvider?.unbindAll() di onPause.
         * CameraX sudah otomatis pause karena bindToLifecycle(this).
         */
    }

    override fun onDestroy() {
        super.onDestroy()

        releaseCamera()

        try {
            roboflowDetector.close()
        } catch (_: Exception) {
        }

        cameraExecutor.shutdown()
    }

    override fun onSharedPreferenceChanged(
        prefs: SharedPreferences?,
        key: String?
    ) {
        // Realtime Roboflow tidak memakai preference inference lokal.
    }

    // ================================================================
    // CONTROLS
    // ================================================================

    private fun setupControls() {
        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnPause.setOnClickListener {
            isPausedByUser = !isPausedByUser

            if (isPausedByUser) {
                pauseDetectionUI()
            } else {
                resumeDetectionUI()
            }
        }

        binding.btnInfo.setOnClickListener {
            showDiseaseInfoDialog()
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, DetectionHistoryActivity::class.java))
        }

        binding.btnShare.setOnClickListener {
            shareCurrentDetection()
        }

        binding.btnOpenGallery.setOnClickListener {
            openPhotoAnalysis(PhotoAnalysisActivity.SOURCE_GALLERY)
        }

        binding.btnTakePhoto.setOnClickListener {
            openPhotoAnalysis(PhotoAnalysisActivity.SOURCE_CAMERA)
        }
    }

    private fun pauseDetectionUI() {
        isPausedByUser = true
        isRoboflowRequestRunning = false

        binding.btnPause.text = "▶ RESUME"
        binding.tvStatus.text = "Deteksi dijeda"
        binding.overlayView.clearDetections()
        binding.cardResult.visibility = View.GONE
        lastDetection = null
    }

    private fun resumeDetectionUI() {
        isPausedByUser = false
        isOpeningPhotoAnalysis = false
        isRoboflowRequestRunning = false
        lastRoboflowRequestTime = 0L

        binding.btnPause.text = "⏸ PAUSE"
        binding.tvStatus.text = "Mendeteksi..."
        binding.tvFps.text = "API | 3s"

        if (hasCameraPermission() && cameraProvider == null && !isBindingCamera) {
            startCamera()
        }
    }

    private fun openPhotoAnalysis(source: String) {
        isOpeningPhotoAnalysis = true
        isPausedByUser = true
        isRoboflowRequestRunning = false

        binding.overlayView.clearDetections()
        binding.cardResult.visibility = View.GONE
        binding.tvStatus.text = "Membuka analisis foto..."
        binding.btnPause.text = "▶ RESUME"
        lastDetection = null

        /*
         * Jangan releaseCamera() di sini.
         * Biarkan lifecycle Activity yang pause kamera agar tidak error Camera2.
         */
        startActivity(
            Intent(this, PhotoAnalysisActivity::class.java).apply {
                putExtra(PhotoAnalysisActivity.EXTRA_SOURCE, source)
            }
        )
    }

    // ================================================================
    // UPDATE UI
    // ================================================================

    private fun updateDetectionUI(results: List<DetectionResult>) {
        if (isPausedByUser || isOpeningPhotoAnalysis) {
            return
        }

        binding.overlayView.setDetections(results, frameW, frameH)

        if (results.isEmpty()) {
            binding.cardResult.visibility = View.GONE
            lastDetection = null
            binding.tvStatus.text = "Mendeteksi..."
            return
        }

        val top = results.maxByOrNull { it.confidence } ?: return

        lastDetection = top
        renderResultCard(top)
        binding.cardResult.visibility = View.VISIBLE

        if (!top.hasChiliObject || top.label.equals("tidak_ada_cabai", ignoreCase = true)) {
            binding.tvStatus.text = "Cabai belum terdeteksi"
            return
        }

        val label = top.label.lowercase()

        binding.tvStatus.text = when {
            isHealthyLabel(label) -> {
                getString(R.string.status_healthy)
            }

            label == "tidak_yakin" || label.contains("tidak yakin") -> {
                "Hasil belum yakin"
            }

            else -> {
                getString(R.string.status_disease)
            }
        }

        if (
            appPrefs.saveHistoryEnabled &&
            top.hasChiliObject &&
            !label.contains("tidak_yakin") &&
            !label.contains("tidak yakin") &&
            top.confidence >= 0.70f
        ) {
            historyManager.record(top)
        }

        if (
            appPrefs.notificationsEnabled &&
            top.hasChiliObject &&
            !isHealthyLabel(label) &&
            !label.contains("tidak_yakin") &&
            !label.contains("tidak yakin")
        ) {
            alertManager.maybeAlert(top)
        }
    }

    private fun renderResultCard(r: DetectionResult) {
        if (!r.hasChiliObject || r.label.equals("tidak_ada_cabai", ignoreCase = true)) {
            binding.tvDiseaseName.text = "Tidak Ada Cabai Terdeteksi"
            binding.tvConfidence.text = "0%"
            binding.tvDescription.text = r.diseaseDescription

            try {
                binding.tvAllProbabilities.text =
                    "Persentase semua kelas akan muncul di sini."
            } catch (_: Exception) {
            }

            binding.cardResult.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.color_unknown)
            )

            return
        }

        binding.tvDiseaseName.text = DiseaseInfo.displayName(r.label)
        binding.tvConfidence.text = r.confidencePercent
        binding.tvDescription.text = r.diseaseDescription

        try {
            binding.tvAllProbabilities.text = buildProbabilityText(r)
        } catch (_: Exception) {
        }

        val label = r.label.lowercase()

        val colorRes = when {
            label == "healthy" || label == "healty" || label == "sehat" -> {
                R.color.color_healthy
            }

            label.contains("antraknosa") ||
                    label.contains("anthraknosa") ||
                    label.contains("patek") -> {
                R.color.color_anthracnose
            }

            label.contains("busuk") -> {
                R.color.color_fusarium
            }

            else -> {
                R.color.color_unknown
            }
        }

        binding.cardResult.setCardBackgroundColor(
            ContextCompat.getColor(this, colorRes)
        )
    }

    private fun buildProbabilityText(result: DetectionResult): String {
        val probabilities = result.allProbabilities

        if (probabilities.isEmpty()) {
            return "Persentase semua kelas belum tersedia."
        }

        val text = probabilities.joinToString("\n") { item ->
            val percent = item.confidence * 100f
            "${DiseaseInfo.displayName(item.label)}: ${String.format("%.1f", percent)}%"
        }

        return "Persentase semua kelas:\n$text"
    }

    private fun isHealthyLabel(label: String): Boolean {
        return label == "healthy" ||
                label == "healty" ||
                label == "sehat"
    }

    private fun createNoChiliResult(): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(0f, 0f, 1f, 1f),
            label = "tidak_ada_cabai",
            confidence = 0f,
            classIndex = -1,
            description = DiseaseInfo.description("tidak_ada_cabai"),
            allProbabilities = emptyList(),
            hasChiliObject = false
        )
    }

    // ================================================================
    // CAMERA
    // ================================================================

    private fun startCamera() {
        if (isBindingCamera || cameraProvider != null) return

        isBindingCamera = true

        ProcessCameraProvider.getInstance(this).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(this).get()
                bindCamera(provider)
            } catch (e: Exception) {
                isBindingCamera = false
                Log.e(TAG, "Gagal start camera: ${e.message}", e)
                showToast("Gagal membuka kamera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        cameraProvider = provider

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        val analysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, FrameAnalyzer())
            }

        try {
            provider.unbindAll()

            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )

            cameraController = CameraController(
                context = this,
                camera = camera,
                previewView = binding.previewView,
                focusRingView = binding.ivFocusRing,
                tvZoomLevel = binding.tvZoomLevel,
                scope = lifecycleScope
            )

            cameraController.attachToPreview()

            isBindingCamera = false

            Log.d(TAG, "✅ Kamera aktif")
        } catch (e: Exception) {
            isBindingCamera = false
            cameraProvider = null
            camera = null

            Log.e(TAG, "Gagal bind kamera: ${e.message}", e)
            showToast("Gagal membuka kamera")
        }
    }

    private fun releaseCamera() {
        try {
            if (::cameraController.isInitialized) {
                cameraController.detach()
            }
        } catch (_: Exception) {
        }

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal melepas kamera: ${e.message}")
        }

        camera = null
        cameraProvider = null
        isBindingCamera = false
        isRoboflowRequestRunning = false
    }

    inner class FrameAnalyzer : ImageAnalysis.Analyzer {

        override fun analyze(imageProxy: ImageProxy) {
            try {
                if (isPausedByUser || isOpeningPhotoAnalysis) {
                    return
                }

                val now = System.currentTimeMillis()

                if (isRoboflowRequestRunning) {
                    return
                }

                if (now - lastRoboflowRequestTime < ROBOFLOW_REALTIME_INTERVAL_MS) {
                    return
                }

                frameW = imageProxy.width
                frameH = imageProxy.height

                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy) ?: return

                /*
                 * Gate lokal ringan untuk mencegah request API saat tidak ada objek cabai.
                 * Ini menghemat API dan mengurangi lag.
                 */
                val hasChili = ChiliPresenceGate.hasChiliObject(bitmap)

                if (!hasChili) {
                    if (now - lastNoChiliUpdateTime > NO_CHILI_UI_INTERVAL_MS) {
                        runOnUiThread {
                            updateDetectionUI(listOf(createNoChiliResult()))
                        }

                        lastNoChiliUpdateTime = now
                    }

                    lastRoboflowRequestTime = now
                    return
                }

                isRoboflowRequestRunning = true
                lastRoboflowRequestTime = now

                runOnUiThread {
                    binding.tvStatus.text = "Menganalisis Roboflow..."
                    binding.tvFps.text = "API | request..."
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val start = System.currentTimeMillis()
                        val results = roboflowDetector.detect(bitmap)
                        val inferenceMs = System.currentTimeMillis() - start

                        withContext(Dispatchers.Main) {
                            if (!isPausedByUser && !isOpeningPhotoAnalysis) {
                                binding.tvFps.text = "API | ${inferenceMs}ms"
                                updateDetectionUI(results)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Roboflow realtime error: ${e.message}", e)

                        withContext(Dispatchers.Main) {
                            if (!isPausedByUser && !isOpeningPhotoAnalysis) {
                                val message = when {
                                    e.message?.contains("401") == true ->
                                        "API Roboflow ditolak. Cek API key."

                                    e.message?.contains("404") == true ->
                                        "Model Roboflow tidak ditemukan."

                                    e.message?.contains("timeout", ignoreCase = true) == true ->
                                        "Koneksi Roboflow timeout."

                                    else ->
                                        "Gagal analisis Roboflow."
                                }

                                binding.tvStatus.text = message
                            }
                        }
                    } finally {
                        isRoboflowRequestRunning = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FrameAnalyzer error: ${e.message}", e)
                isRoboflowRequestRunning = false
            } finally {
                try {
                    imageProxy.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ================================================================
    // ACTIONS
    // ================================================================

    private fun toggleFlash() {
        if (!::cameraController.isInitialized) return

        val isOn = cameraController.toggleTorch()

        binding.btnFlash.text = if (isOn) {
            getString(R.string.btn_flash_on)
        } else {
            getString(R.string.btn_flash)
        }
    }

    private fun shareCurrentDetection() {
        val d = lastDetection ?: run {
            showToast("Belum ada deteksi")
            return
        }

        val text = buildString {
            appendLine("🌶️ Hasil Deteksi Penyakit Cabai (Real-time Roboflow)")
            appendLine()
            appendLine("Penyakit : ${DiseaseInfo.displayName(d.label)}")
            appendLine("Akurasi  : ${d.confidencePercent}")
            appendLine("Info     : ${d.diseaseDescription}")
            appendLine()
            appendLine(buildProbabilityText(d))
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Deteksi Penyakit Cabai")
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Bagikan Hasil"
            )
        )
    }

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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showPermissionDialog()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // optional
    }

    private fun requestCameraPermission() {
        cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestNotifPermIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required_title))
            .setMessage(getString(R.string.permission_required_message))
            .setPositiveButton(getString(R.string.permission_settings)) { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                )

                intent.data = android.net.Uri.fromParts(
                    "package",
                    packageName,
                    null
                )

                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.dialog_close)) { _, _ ->
                finish()
            }
            .show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}