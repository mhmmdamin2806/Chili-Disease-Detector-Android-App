package com.example.chilidisease

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.chilidisease.databinding.ActivityPhotoAnalysisBinding
import com.example.chilidisease.detector.RoboflowRemoteDetector
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.detector.DiseaseInfo
import com.example.chilidisease.history.DetectionHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoAnalysisActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhotoAnalysisActivity"

        const val EXTRA_SOURCE = "source"
        const val SOURCE_GALLERY = "gallery"
        const val SOURCE_CAMERA = "camera"
    }

    private lateinit var binding: ActivityPhotoAnalysisBinding
    private lateinit var detector: RoboflowRemoteDetector
    private lateinit var historyManager: DetectionHistoryManager

    private var capturedPhotoUri: Uri? = null
    private var resultBitmap: Bitmap? = null
    private var lastResults: List<DetectionResult> = emptyList()
    private var isDetectorReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPhotoAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarPhoto)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.photo_analysis_title)

        historyManager = DetectionHistoryManager(this)

        setupButtons()
        initDetectorAndOpenSource()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "PhotoAnalysisActivity resumed")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "PhotoAnalysisActivity paused")
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            if (::detector.isInitialized) {
                detector.close()
            }
        } catch (_: Exception) {
        }

        try {
            resultBitmap?.recycle()
        } catch (_: Exception) {
        }

        resultBitmap = null
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initDetectorAndOpenSource() {
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                detector = RoboflowRemoteDetector(this@PhotoAnalysisActivity)
                isDetectorReady = true

                withContext(Dispatchers.Main) {
                    showLoading(false)

                    when (intent.getStringExtra(EXTRA_SOURCE)) {
                        SOURCE_GALLERY -> pickFromGallery()
                        SOURCE_CAMERA -> capturePhoto()
                        else -> {
                            binding.tvPhotoHint.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal init detector: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showToast("Model tidak ditemukan. Pastikan file .tflite ada di assets/")
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnPickGallery.setOnClickListener {
            pickFromGallery()
        }

        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }

        binding.btnAnalyzeAgain.setOnClickListener {
            resultBitmap?.let {
                analyzeAndDisplay(it)
            } ?: showToast("Pilih foto terlebih dahulu")
        }

        binding.btnShareResult.setOnClickListener {
            shareResult()
        }

        binding.btnSaveResult.setOnClickListener {
            saveResultToGallery()
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processImageUri(uri)
        } else {
            showToast("Tidak ada gambar yang dipilih")
        }
    }

    private fun pickFromGallery() {
        galleryLauncher.launch("image/*")
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            capturePhoto()
        } else {
            showToast("Izin kamera diperlukan")
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = capturedPhotoUri

        if (success && uri != null) {
            processImageUri(uri)
        } else {
            showToast("Foto dibatalkan atau kamera tidak bisa dibuka")
        }
    }

    private fun capturePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            val timestamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            ).format(Date())

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "CABAI_$timestamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/ChiliDetector"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (uri == null) {
                showToast("Gagal membuat file foto")
                return
            }

            capturedPhotoUri = uri
            captureLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e(TAG, "capturePhoto error: ${e.message}", e)
            showToast("Gagal membuka kamera: ${e.message}")
        }
    }

    private fun processImageUri(uri: Uri) {
        showLoading(true)
        binding.tvPhotoHint.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeBitmapFromUri(uri)
                    ?: throw IOException("Gagal decode gambar")

                withContext(Dispatchers.Main) {
                    analyzeAndDisplay(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error memproses gambar: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showToast("Gagal membuka gambar: ${e.message}")
                }
            }
        }
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val rotation = contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)

                when (
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }

            val maxSize = 1920
            var sampleSize = 1

            while (
                opts.outWidth / sampleSize > maxSize ||
                opts.outHeight / sampleSize > maxSize
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val rawBitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            if (rotation != 0f) {
                val matrix = Matrix().apply {
                    postRotate(rotation)
                }

                Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true
                ).also {
                    if (it !== rawBitmap) {
                        rawBitmap.recycle()
                    }
                }
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeBitmapFromUri error: ${e.message}", e)
            null
        }
    }

    private fun analyzeAndDisplay(bitmap: Bitmap) {
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!isDetectorReady || !::detector.isInitialized) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        showToast("Model belum siap, coba lagi")
                    }
                    return@launch
                }

                val start = System.currentTimeMillis()
                val results = detector.detect(bitmap)
                val inferenceMs = System.currentTimeMillis() - start

                val resultImage = drawResultsOnBitmap(bitmap, results)

                withContext(Dispatchers.Main) {
                    resultBitmap = resultImage
                    lastResults = results

                    binding.ivPhoto.setImageBitmap(resultImage)

                    showLoading(false)
                    updateResultCard(results, inferenceMs)

                    results.maxByOrNull { it.confidence }?.let { top ->
                        if (top.confidence >= 0.60f && top.hasChiliObject) {
                            historyManager.record(top)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyzeAndDisplay error: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    showLoading(false)

                    val message = when {
                        e.message?.contains("401") == true ->
                            "API Roboflow ditolak. Periksa API key, workspace, dan model version."

                        e.message?.contains("404") == true ->
                            "Model Roboflow tidak ditemukan. Periksa MODEL_ID dan MODEL_VERSION."

                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            "Koneksi ke Roboflow timeout. Periksa internet."

                        else ->
                            "Analisis gagal: ${e.message}"
                    }

                    showToast(message)
                }
            }
        }
    }

    private fun drawResultsOnBitmap(
        source: Bitmap,
        results: List<DetectionResult>
    ): Bitmap {
        val w = source.width.toFloat()
        val h = source.height.toFloat()

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val baseStrokeWidth = (minOf(w, h) * 0.007f).coerceAtLeast(3f)
        val cornerLen = minOf(w, h) * 0.04f
        val textSize = (minOf(w, h) * 0.045f).coerceIn(24f, 72f)
        val subTextSize = textSize * 0.75f
        val padding = textSize * 0.3f
        val cornerStroke = baseStrokeWidth * 2f

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = baseStrokeWidth
        }

        val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = cornerStroke
            strokeCap = Paint.Cap.ROUND
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 210
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = subTextSize
        }

        if (results.isEmpty()) {
            drawNoDetectionMessage(canvas, w, h, textSize, padding)
            return output
        }

        for (result in results) {
            val color = getColorByLabel(result.label)

            val left = result.boundingBox.left * w
            val top = result.boundingBox.top * h
            val right = result.boundingBox.right * w
            val bottom = result.boundingBox.bottom * h
            val rect = RectF(left, top, right, bottom)

            boxPaint.color = color
            cornerPaint.color = color
            bgPaint.color = color

            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            canvas.drawLine(left, top + cornerLen, left, top, cornerPaint)
            canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)

            canvas.drawLine(right - cornerLen, top, right, top, cornerPaint)
            canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)

            canvas.drawLine(left, bottom - cornerLen, left, bottom, cornerPaint)
            canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)

            canvas.drawLine(right - cornerLen, bottom, right, bottom, cornerPaint)
            canvas.drawLine(right, bottom, right, bottom - cornerLen, cornerPaint)

            val labelText = DiseaseInfo.displayName(result.label)
            val confidenceText = "Akurasi: ${result.confidencePercent}"

            val textWidth = maxOf(
                labelPaint.measureText(labelText),
                subPaint.measureText(confidenceText)
            )

            val textHeight = textSize + subTextSize + padding * 3

            val labelTop = if (top > textHeight + 8f) {
                top - textHeight - 4f
            } else {
                bottom + 4f
            }

            val labelRect = RectF(
                left,
                labelTop,
                left + textWidth + padding * 2,
                labelTop + textHeight
            )

            canvas.drawRoundRect(labelRect, 8f, 8f, bgPaint)

            canvas.drawText(
                labelText,
                left + padding,
                labelTop + padding + textSize,
                labelPaint
            )

            canvas.drawText(
                confidenceText,
                left + padding,
                labelTop + padding + textSize + subTextSize + padding * 0.5f,
                subPaint
            )
        }

        return output
    }

    private fun drawNoDetectionMessage(
        canvas: Canvas,
        width: Float,
        height: Float,
        textSize: Float,
        padding: Float
    ) {
        val noDetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val bgNoDetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val msg = "Tidak ada penyakit terdeteksi"
        val tw = noDetPaint.measureText(msg)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawRoundRect(
            RectF(
                cx - tw / 2 - padding * 2,
                cy - textSize - padding,
                cx + tw / 2 + padding * 2,
                cy + padding
            ),
            12f,
            12f,
            bgNoDetPaint
        )

        canvas.drawText(msg, cx, cy, noDetPaint)
    }

    private fun updateResultCard(
        results: List<DetectionResult>,
        inferenceMs: Long
    ) {
        if (results.isEmpty()) {
            binding.cardPhotoResult.visibility = View.GONE
            binding.tvNoDetection.visibility = View.VISIBLE
            binding.btnShareResult.visibility = View.VISIBLE
            binding.btnSaveResult.visibility = View.VISIBLE
            return
        }

        binding.tvNoDetection.visibility = View.GONE
        binding.cardPhotoResult.visibility = View.VISIBLE
        binding.btnShareResult.visibility = View.VISIBLE
        binding.btnSaveResult.visibility = View.VISIBLE

        val top = results.maxByOrNull { it.confidence } ?: return

        binding.tvPhotoDiseaseName.text = DiseaseInfo.displayName(top.label)
        binding.tvPhotoConfidence.text = "Akurasi: ${top.confidencePercent}"
        binding.tvPhotoDescription.text = top.diseaseDescription
        binding.tvInferenceTime.text = "Waktu analisis: ${inferenceMs}ms"

        if (top.allProbabilities.isNotEmpty()) {
            val allDetections = top.allProbabilities.joinToString("\n") {
                "• ${DiseaseInfo.displayName(it.label)} (${String.format("%.1f", it.confidence * 100f)}%)"
            }

            binding.tvAllDetections.text = "Semua kelas:\n$allDetections"
            binding.tvAllDetections.visibility = View.VISIBLE
        } else if (results.size > 1) {
            val allDetections = results.joinToString("\n") {
                "• ${DiseaseInfo.displayName(it.label)} (${it.confidencePercent})"
            }

            binding.tvAllDetections.text = "Semua deteksi:\n$allDetections"
            binding.tvAllDetections.visibility = View.VISIBLE
        } else {
            binding.tvAllDetections.visibility = View.GONE
        }

        val colorRes = getColorResByLabel(top.label)

        binding.cardPhotoResult.setCardBackgroundColor(
            ContextCompat.getColor(this, colorRes)
        )
    }

    private fun getColorByLabel(labelRaw: String): Int {
        val label = labelRaw.lowercase()

        return when {
            label == "healthy" || label == "healty" || label == "sehat" ->
                Color.parseColor("#00C853")

            label.contains("antraknosa") ||
                    label.contains("anthraknosa") ||
                    label.contains("patek") ->
                Color.parseColor("#FF6D00")

            label.contains("busuk") ->
                Color.parseColor("#D50000")

            label.contains("lalat") ->
                Color.parseColor("#6200EA")

            label.contains("cercospora") ||
                    label.contains("cerocospora") ->
                Color.parseColor("#6200EA")

            else ->
                Color.parseColor("#6200EA")
        }
    }

    private fun getColorResByLabel(labelRaw: String): Int {
        val label = labelRaw.lowercase()

        return when {
            label == "healthy" || label == "healty" || label == "sehat" ->
                R.color.color_healthy

            label.contains("antraknosa") ||
                    label.contains("anthraknosa") ||
                    label.contains("patek") ->
                R.color.color_anthracnose

            label.contains("busuk") ->
                R.color.color_fusarium

            else ->
                R.color.color_unknown
        }
    }

    private fun shareResult() {
        val bitmap = resultBitmap ?: run {
            showToast("Tidak ada hasil untuk dibagikan")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(
                    cacheDir,
                    "hasil_deteksi_${System.currentTimeMillis()}.jpg"
                )

                file.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }

                val uri = FileProvider.getUriForFile(
                    this@PhotoAnalysisActivity,
                    "com.example.chilidisease.fileprovider",
                    file
                )

                val top = lastResults.maxByOrNull { it.confidence }

                val text = if (top != null) {
                    buildString {
                        appendLine("🌶️ Hasil Deteksi Penyakit Cabai")
                        appendLine("Penyakit : ${DiseaseInfo.displayName(top.label)}")
                        appendLine("Akurasi  : ${top.confidencePercent}")
                        appendLine("Info     : ${top.diseaseDescription}")
                    }
                } else {
                    "🌶️ Tidak ada penyakit terdeteksi"
                }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, "Hasil Deteksi Penyakit Cabai")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(intent, "Bagikan Hasil"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "shareResult error: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    showToast("Gagal membagikan: ${e.message}")
                }
            }
        }
    }

    private fun saveResultToGallery() {
        val bitmap = resultBitmap ?: run {
            showToast("Tidak ada hasil untuk disimpan")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.getDefault()
                ).format(Date())

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "DETEKSI_CABAI_$timestamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/ChiliDetector"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )

                uri?.let { targetUri ->
                    contentResolver.openOutputStream(targetUri)?.use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(targetUri, values, null, null)
                    }
                }

                withContext(Dispatchers.Main) {
                    showToast("✅ Foto tersimpan di Galeri/ChiliDetector")
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveResultToGallery error: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    showToast("Gagal menyimpan: ${e.message}")
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE

        binding.progressPhoto.visibility = visibility
        binding.tvLoadingMsg.visibility = visibility

        if (show) {
            binding.cardPhotoResult.visibility = View.GONE
            binding.tvNoDetection.visibility = View.GONE
            binding.btnShareResult.visibility = View.GONE
            binding.btnSaveResult.visibility = View.GONE
            binding.btnAnalyzeAgain.visibility = View.GONE
        } else {
            binding.btnAnalyzeAgain.visibility = View.VISIBLE
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}