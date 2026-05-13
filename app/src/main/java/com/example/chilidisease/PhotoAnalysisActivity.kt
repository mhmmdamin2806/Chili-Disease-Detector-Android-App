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
import com.example.chilidisease.detector.ChiliDiseaseDetector
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.history.DetectionHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PhotoAnalysisActivity
 *
 * Activity untuk analisis gambar statis (bukan real-time kamera).
 * Mendukung dua sumber gambar:
 *   1. Galeri / File manager (ACTION_GET_CONTENT)
 *   2. Kamera — ambil foto langsung (MediaStore + FileProvider)
 *
 * Alur:
 *   Pilih sumber → Decode Bitmap → TFLite → Gambar hasil di atas foto → Tampilkan
 */
class PhotoAnalysisActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhotoAnalysisActivity"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_GALLERY = "gallery"
        const val SOURCE_CAMERA  = "camera"
    }

    private lateinit var binding: ActivityPhotoAnalysisBinding
    private lateinit var detector: ChiliDiseaseDetector
    private lateinit var historyManager: DetectionHistoryManager

    // URI foto yang diambil kamera (disimpan ke MediaStore)
    private var capturedPhotoUri: Uri? = null

    // Bitmap terakhir (untuk share)
    private var resultBitmap: Bitmap? = null
    private var lastResults: List<DetectionResult> = emptyList()

    // ================================================================
    // LIFECYCLE
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarPhoto)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.photo_analysis_title)
        }

        historyManager = DetectionHistoryManager(this)

        initDetector()
        setupButtons()

        when (intent.getStringExtra(EXTRA_SOURCE)) {
            SOURCE_GALLERY -> pickFromGallery()
            SOURCE_CAMERA  -> capturePhoto()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::detector.isInitialized) detector.close()
        resultBitmap?.recycle()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ================================================================
    // INIT
    // ================================================================

    private fun initDetector() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                detector = ChiliDiseaseDetector(this@PhotoAnalysisActivity)
                Log.d(TAG, "✅ Detector siap")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Gagal init detector: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Model tidak ditemukan. Pastikan file .tflite ada di assets/")
                }
            }
        }
    }

    // ================================================================
    // BUTTON SETUP
    // ================================================================

    private fun setupButtons() {
        // Ambil ulang dari galeri
        binding.btnPickGallery.setOnClickListener { pickFromGallery() }

        // Ambil ulang foto kamera
        binding.btnCapture.setOnClickListener { capturePhoto() }

        // Analisis ulang gambar yang sudah ada
        binding.btnAnalyzeAgain.setOnClickListener {
            resultBitmap?.let { analyzeAndDisplay(it) }
                ?: showToast("Pilih foto terlebih dahulu")
        }

        // Share hasil
        binding.btnShareResult.setOnClickListener { shareResult() }

        // Simpan ke galeri
        binding.btnSaveResult.setOnClickListener { saveResultToGallery() }
    }

    // ================================================================
    // GALLERY PICKER
    // ================================================================

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageUri(it) }
    }

    private fun pickFromGallery() {
        galleryLauncher.launch("image/*")
    }

    // ================================================================
    // CAMERA CAPTURE
    // ================================================================

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) capturePhoto() else showToast("Izin kamera diperlukan")
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            capturedPhotoUri?.let { processImageUri(it) }
        } else {
            showToast("Foto dibatalkan")
        }
    }

    private fun capturePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // Buat URI di MediaStore (tidak butuh WRITE_EXTERNAL_STORAGE di API 29+)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CABAI_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChiliDetector")
            }
        }

        capturedPhotoUri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        capturedPhotoUri?.let { uri ->
            captureLauncher.launch(uri)
        } ?: showToast("Gagal membuat file foto")
    }

    // ================================================================
    // IMAGE PROCESSING
    // ================================================================

    /**
     * Decode URI → Bitmap → Analisis → Tampilkan
     */
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
                Log.e(TAG, "Error memproses gambar: ${e.message}")
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showToast("Gagal membuka gambar: ${e.message}")
                }
            }
        }
    }

    /**
     * Decode URI ke Bitmap dengan:
     * - Downscale otomatis jika gambar terlalu besar (max 1920px)
     * - Koreksi rotasi dari EXIF metadata
     */
    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            // Baca EXIF untuk rotasi
            val rotation = contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else                                 -> 0f
                }
            } ?: 0f

            // Baca ukuran asli dulu
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }

            // Hitung inSampleSize agar tidak OOM
            val maxSize = 1920
            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxSize || opts.outHeight / sampleSize > maxSize) {
                sampleSize *= 2
            }

            // Decode sesungguhnya
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val raw = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            // Rotasi jika perlu
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { if (it !== raw) raw.recycle() }
            } else raw

        } catch (e: Exception) {
            Log.e(TAG, "decodeBitmapFromUri error: ${e.message}")
            null
        }
    }

    /**
     * Jalankan TFLite → gambar bounding box di atas foto → tampilkan di ImageView
     */
    private fun analyzeAndDisplay(bitmap: Bitmap) {
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!::detector.isInitialized) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        showToast("Model belum siap, coba lagi")
                    }
                    return@launch
                }

                // Inferensi
                val t0 = System.currentTimeMillis()
                val results = detector.detect(bitmap)
                val ms = System.currentTimeMillis() - t0
                Log.d(TAG, "Inferensi: ${ms}ms | Deteksi: ${results.size}")

                // Gambar hasil di atas bitmap asli
                val resultBmp = drawResultsOnBitmap(bitmap, results)

                withContext(Dispatchers.Main) {
                    // Simpan untuk share/save
                    this@PhotoAnalysisActivity.resultBitmap = resultBmp
                    this@PhotoAnalysisActivity.lastResults   = results

                    // Tampilkan gambar hasil
                    binding.ivPhoto.setImageBitmap(resultBmp)
                    showLoading(false)
                    updateResultCard(results, ms)

                    // Simpan ke riwayat jika ada deteksi
                    results.maxByOrNull { it.confidence }?.let { top ->
                        if (top.confidence >= 0.60f) historyManager.record(top)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyzeAndDisplay error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showToast("Analisis gagal: ${e.message}")
                }
            }
        }
    }

    // ================================================================
    // DRAW RESULTS ON BITMAP
    // ================================================================

    /**
     * Gambar bounding box + label langsung di atas foto asli.
     * Mengembalikan Bitmap baru (bitmap asli tidak dimodifikasi).
     */
    private fun drawResultsOnBitmap(
        source: Bitmap,
        results: List<DetectionResult>
    ): Bitmap {
        val w = source.width.toFloat()
        val h = source.height.toFloat()

        // Buat salinan bitmap agar asli tidak berubah
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        // Scale font & stroke agar proporsional dengan ukuran gambar
        val baseStrokeWidth = (minOf(w, h) * 0.007f).coerceAtLeast(3f)
        val cornerLen       = minOf(w, h) * 0.04f
        var textSize        = (minOf(w, h) * 0.045f).coerceIn(24f, 72f)
        val subTextSize     = textSize * 0.75f
        val padding         = textSize * 0.3f
        val cornerStroke    = baseStrokeWidth * 2f

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = baseStrokeWidth
        }
        val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = cornerStroke
            strokeCap   = Paint.Cap.ROUND
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 210
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.WHITE
            this.textSize  = textSize
            typeface  = Typeface.DEFAULT_BOLD
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.WHITE
            this.textSize  = subTextSize
        }

        if (results.isEmpty()) {
            // Tampilkan pesan "tidak terdeteksi" di tengah
            val noDetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color         = Color.WHITE
                textSize      = textSize
                typeface      = Typeface.DEFAULT_BOLD
                textAlign     = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            val bgNoDetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(160, 0, 0, 0)
                style = Paint.Style.FILL
            }
            val msg = "Tidak ada penyakit terdeteksi"
            val tw  = noDetPaint.measureText(msg)
            val cx  = w / 2f
            val cy  = h / 2f
            canvas.drawRoundRect(
                RectF(cx - tw / 2 - padding * 2, cy - textSize - padding,
                      cx + tw / 2 + padding * 2, cy + padding),
                12f, 12f, bgNoDetPaint
            )
            canvas.drawText(msg, cx, cy, noDetPaint)
            return output
        }

        for (r in results) {
            val color = when (r.classIndex) {
                0    -> Color.parseColor("#00C853")
                1    -> Color.parseColor("#FF6D00")
                2    -> Color.parseColor("#D50000")
                else -> Color.parseColor("#6200EA")
            }

            // Koordinat box di pixel
            val left   = r.boundingBox.left   * w
            val top    = r.boundingBox.top    * h
            val right  = r.boundingBox.right  * w
            val bottom = r.boundingBox.bottom * h
            val rect   = RectF(left, top, right, bottom)

            // ── Kotak utama ──
            boxPaint.color = color
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            // ── Corner markers ──
            cornerPaint.color = color
            // TL
            canvas.drawLine(left, top + cornerLen, left, top, cornerPaint)
            canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
            // TR
            canvas.drawLine(right - cornerLen, top, right, top, cornerPaint)
            canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)
            // BL
            canvas.drawLine(left, bottom - cornerLen, left, bottom, cornerPaint)
            canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)
            // BR
            canvas.drawLine(right - cornerLen, bottom, right, bottom, cornerPaint)
            canvas.drawLine(right, bottom, right, bottom - cornerLen, cornerPaint)

            // ── Label ──
            val labelText = r.label
            val confText  = "Akurasi: ${r.confidencePercent}"
            val lw = maxOf(labelPaint.measureText(labelText), subPaint.measureText(confText))
            val lh = textSize + subTextSize + padding * 3

            val labelTop = if (top > lh + 8f) top - lh - 4f else bottom + 4f
            val labelRect = RectF(left, labelTop, left + lw + padding * 2, labelTop + lh)

            bgPaint.color = color
            canvas.drawRoundRect(labelRect, 8f, 8f, bgPaint)

            canvas.drawText(labelText, left + padding, labelTop + padding + textSize, labelPaint)
            canvas.drawText(confText, left + padding, labelTop + padding + textSize + subTextSize + padding * 0.5f, subPaint)
        }

        return output
    }

    // ================================================================
    // UI UPDATES
    // ================================================================

    private fun updateResultCard(results: List<DetectionResult>, inferenceMs: Long) {
        if (results.isEmpty()) {
            binding.cardPhotoResult.visibility = View.GONE
            binding.tvNoDetection.visibility   = View.VISIBLE
            binding.btnShareResult.visibility  = View.VISIBLE
            binding.btnSaveResult.visibility   = View.VISIBLE
            return
        }

        binding.tvNoDetection.visibility   = View.GONE
        binding.cardPhotoResult.visibility = View.VISIBLE
        binding.btnShareResult.visibility  = View.VISIBLE
        binding.btnSaveResult.visibility   = View.VISIBLE

        val top = results.maxByOrNull { it.confidence }!!

        binding.tvPhotoDiseaseName.text  = top.label
        binding.tvPhotoConfidence.text   = "Akurasi: ${top.confidencePercent}"
        binding.tvPhotoDescription.text  = top.diseaseDescription
        binding.tvInferenceTime.text     = "Waktu analisis: ${inferenceMs}ms"

        // Tampilkan semua deteksi jika lebih dari 1
        if (results.size > 1) {
            val all = results.joinToString("\n") { "• ${it.label} (${it.confidencePercent})" }
            binding.tvAllDetections.text       = "Semua deteksi:\n$all"
            binding.tvAllDetections.visibility = View.VISIBLE
        } else {
            binding.tvAllDetections.visibility = View.GONE
        }

        val colorRes = when (top.classIndex) {
            0    -> R.color.color_healthy
            1    -> R.color.color_anthracnose
            2    -> R.color.color_fusarium
            else -> R.color.color_unknown
        }
        binding.cardPhotoResult.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    // ================================================================
    // SHARE & SAVE
    // ================================================================

    private fun shareResult() {
        val bmp = resultBitmap ?: run { showToast("Tidak ada hasil untuk dibagikan"); return }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Simpan sementara ke cache
                val file = File(cacheDir, "hasil_deteksi_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                val uri = FileProvider.getUriForFile(
                    this@PhotoAnalysisActivity,
                    "com.example.chilidisease.fileprovider",
                    file
                )

                val top  = lastResults.maxByOrNull { it.confidence }
                val text = if (top != null) buildString {
                    appendLine("🌶️ Hasil Deteksi Penyakit Cabai")
                    appendLine("Penyakit : ${top.label}")
                    appendLine("Akurasi  : ${top.confidencePercent}")
                    appendLine("Info     : ${top.diseaseDescription}")
                } else "🌶️ Tidak ada penyakit terdeteksi"

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
                withContext(Dispatchers.Main) { showToast("Gagal membagikan: ${e.message}") }
            }
        }
    }

    private fun saveResultToGallery() {
        val bmp = resultBitmap ?: run { showToast("Tidak ada hasil untuk disimpan"); return }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "DETEKSI_CABAI_$timestamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChiliDetector")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { u ->
                    contentResolver.openOutputStream(u)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(u, values, null, null)
                    }
                }

                withContext(Dispatchers.Main) {
                    showToast("✅ Foto tersimpan di Galeri/ChiliDetector")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Gagal menyimpan: ${e.message}") }
            }
        }
    }

    // ================================================================
    // UI HELPERS
    // ================================================================

    private fun showLoading(show: Boolean) {
        // progressPhoto dan tvLoadingMsg adalah view terpisah di dalam loadingContainer
        // Kita kontrol visibility parent (loadingContainer) + children
        val vis = if (show) View.VISIBLE else View.GONE
        binding.progressPhoto.visibility = vis
        binding.tvLoadingMsg.visibility  = vis
        if (show) {
            binding.cardPhotoResult.visibility = View.GONE
            binding.tvNoDetection.visibility   = View.GONE
            binding.btnShareResult.visibility  = View.GONE
            binding.btnSaveResult.visibility   = View.GONE
            binding.btnAnalyzeAgain.visibility = View.GONE
        }
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
