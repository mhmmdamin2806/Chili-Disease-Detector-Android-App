package com.example.chilidisease.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.chilidisease.detector.DetectionResult
import kotlin.math.max

/**
 * DetectionOverlayView
 *
 * Custom View yang menggambar bounding box, label, dan persentase akurasi
 * di atas preview kamera secara real-time.
 *
 * View ini transparan dan di-overlay di atas PreviewView CameraX.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Daftar hasil deteksi yang akan digambar
    private var detections: List<DetectionResult> = emptyList()

    // Dimensi gambar asli dari kamera
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // ── Paint untuk Bounding Box ──
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // ── Paint untuk latar belakang label ──
    private val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 200 // Semi-transparan
    }

    // ── Paint untuk teks label penyakit ──
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // ── Paint untuk teks persentase ──
    private val percentPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // ── Paint untuk corner markers (sudut kotak) ──
    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // ── Paint untuk overlay scan line ──
    private val scanLinePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Ukuran corner marker
    private val CORNER_LENGTH = 30f

    // Padding teks dalam label
    private val TEXT_PADDING = 10f

    // Counter untuk animasi scan line
    private var scanLineY = 0f
    private var scanLineDirection = 1

    companion object {
        // Warna default untuk setiap kelas
        val DISEASE_COLORS = mapOf(
            0 to Color.parseColor("#00C853"), // Sehat = Hijau
            1 to Color.parseColor("#FF6D00"), // Antraknosa = Oranye
            2 to Color.parseColor("#D50000")  // Layu Fusarium = Merah
        )
        val DEFAULT_COLOR = Color.parseColor("#6200EA") // Ungu untuk unknown
    }

    /**
     * Update deteksi baru dan trigger redraw
     *
     * @param results Daftar hasil deteksi dari model
     * @param imgWidth Lebar gambar asli dari kamera
     * @param imgHeight Tinggi gambar asli dari kamera
     */
    fun setDetections(
        results: List<DetectionResult>,
        imgWidth: Int,
        imgHeight: Int
    ) {
        detections = results
        imageWidth = imgWidth
        imageHeight = imgHeight
        // Minta View untuk redraw di UI thread
        invalidate()
    }

    /**
     * Hapus semua deteksi
     */
    fun clearDetections() {
        detections = emptyList()
        invalidate()
    }

    /**
     * Override onDraw - Gambar semua overlay di sini
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty()) {
            drawIdleState(canvas)
            return
        }

        // Gambar setiap hasil deteksi
        for (detection in detections) {
            drawDetection(canvas, detection)
        }
    }

    /**
     * Gambar UI saat tidak ada deteksi (idle state)
     */
    private fun drawIdleState(canvas: Canvas) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Gambar crosshair tipis di tengah
        val crosshairPaint = Paint().apply {
            color = Color.argb(80, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val cx = viewWidth / 2
        val cy = viewHeight / 2
        val crossSize = 40f

        canvas.drawLine(cx - crossSize, cy, cx + crossSize, cy, crosshairPaint)
        canvas.drawLine(cx, cy - crossSize, cx, cy + crossSize, crosshairPaint)

        // Gambar frame guide
        val frameSize = minOf(viewWidth, viewHeight) * 0.6f
        val frameLeft = cx - frameSize / 2
        val frameTop = cy - frameSize / 2
        val frameRight = cx + frameSize / 2
        val frameBottom = cy + frameSize / 2
        val frameRect = RectF(frameLeft, frameTop, frameRight, frameBottom)

        cornerPaint.color = Color.argb(150, 255, 255, 255)
        drawCornerMarkers(canvas, frameRect)
    }

    /**
     * Gambar satu hasil deteksi (bounding box + label)
     */
    private fun drawDetection(canvas: Canvas, detection: DetectionResult) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val color = DISEASE_COLORS[detection.classIndex] ?: DEFAULT_COLOR

        // ── Konversi koordinat normalisasi ke koordinat View ──
        // Perlu memperhitungkan aspect ratio kamera vs tampilan
        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float

        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspect = viewWidth / viewHeight

        if (imageAspect > viewAspect) {
            // Gambar lebih lebar - letter box atas/bawah
            scaleX = viewWidth / imageWidth.toFloat()
            scaleY = viewWidth / imageAspect / viewHeight
            offsetX = 0f
            offsetY = (viewHeight - imageHeight * scaleX / imageAspect) / 2f
        } else {
            // Gambar lebih tinggi - pillar box kiri/kanan
            scaleX = viewHeight * imageAspect / viewWidth
            scaleY = viewHeight / imageHeight.toFloat()
            offsetX = (viewWidth - imageWidth * scaleY * imageAspect) / 2f
            offsetY = 0f
        }

        // Koordinat bounding box dalam View pixels
        val left = detection.boundingBox.left * viewWidth
        val top = detection.boundingBox.top * viewHeight
        val right = detection.boundingBox.right * viewWidth
        val bottom = detection.boundingBox.bottom * viewHeight

        val boxRect = RectF(left, top, right, bottom)

        // ── 1. Gambar shadow box (efek depth) ──
        val shadowPaint = Paint().apply {
            this.color = Color.argb(60, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            RectF(left + 3, top + 3, right + 3, bottom + 3),
            8f, 8f, shadowPaint
        )

        // ── 2. Gambar bounding box utama ──
        boxPaint.color = color
        canvas.drawRoundRect(boxRect, 8f, 8f, boxPaint)

        // ── 3. Gambar corner markers (lebih tegas dari box) ──
        cornerPaint.color = color
        drawCornerMarkers(canvas, boxRect)

        // ── 4. Gambar label penyakit ──
        drawLabel(canvas, detection, boxRect, color)
    }

    /**
     * Gambar corner markers (sudut-sudut kotak) untuk tampilan lebih modern
     */
    private fun drawCornerMarkers(canvas: Canvas, rect: RectF) {
        val cl = CORNER_LENGTH

        // Sudut kiri atas
        canvas.drawLine(rect.left, rect.top + cl, rect.left, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left + cl, rect.top, cornerPaint)

        // Sudut kanan atas
        canvas.drawLine(rect.right - cl, rect.top, rect.right, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cl, cornerPaint)

        // Sudut kiri bawah
        canvas.drawLine(rect.left, rect.bottom - cl, rect.left, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + cl, rect.bottom, cornerPaint)

        // Sudut kanan bawah
        canvas.drawLine(rect.right - cl, rect.bottom, rect.right, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cl, cornerPaint)
    }

    /**
     * Gambar label dengan background semi-transparan
     * Menampilkan: nama penyakit + persentase akurasi
     */
    private fun drawLabel(
        canvas: Canvas,
        detection: DetectionResult,
        boxRect: RectF,
        color: Int
    ) {
        val labelText = detection.label
        val percentText = detection.confidencePercent

        // Ukuran teks
        val labelWidth = labelPaint.measureText(labelText)
        val percentWidth = percentPaint.measureText(percentText)
        val maxTextWidth = max(labelWidth, percentWidth)

        val labelHeight = labelPaint.textSize
        val percentHeight = percentPaint.textSize
        val totalTextHeight = labelHeight + percentHeight + TEXT_PADDING * 3

        val bgWidth = maxTextWidth + TEXT_PADDING * 3
        val bgHeight = totalTextHeight

        // Posisi label: di atas bounding box, atau di bawah jika tidak cukup ruang
        val labelTop: Float
        val labelBottom: Float

        if (boxRect.top > bgHeight + 8f) {
            // Tampilkan di atas box
            labelBottom = boxRect.top - 4f
            labelTop = labelBottom - bgHeight
        } else {
            // Tampilkan di dalam box (bagian atas)
            labelTop = boxRect.top + 4f
            labelBottom = labelTop + bgHeight
        }

        val labelLeft = boxRect.left
        val labelRight = labelLeft + bgWidth

        // ── Background label ──
        labelBackgroundPaint.color = color
        val bgRect = RectF(labelLeft, labelTop, labelRight, labelBottom)
        canvas.drawRoundRect(bgRect, 6f, 6f, labelBackgroundPaint)

        // ── Teks nama penyakit ──
        val textX = labelLeft + TEXT_PADDING
        val labelTextY = labelTop + TEXT_PADDING + labelHeight
        canvas.drawText(labelText, textX, labelTextY, labelPaint)

        // ── Teks persentase ──
        val percentY = labelTextY + TEXT_PADDING + percentHeight - 4f
        canvas.drawText("Akurasi: $percentText", textX, percentY, percentPaint)
    }
}
