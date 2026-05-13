package com.example.chilidisease.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YoloV8Detector
 *
 * Parser khusus untuk model YOLOv8 yang diekspor ke TFLite.
 *
 * Format output YOLOv8 TFLite berbeda dari SSD:
 * - Output tensor: [1, (4 + num_classes), num_anchors]
 *   Misal: [1, 7, 8400] untuk 3 kelas (4 koordinat box + 3 score kelas)
 * - Koordinat: cx, cy, w, h (center format, dinormalisasi)
 * - Tidak ada objectness score terpisah (berbeda dari YOLOv5)
 *
 * Gunakan kelas ini sebagai PENGGANTI ChiliDiseaseDetector jika
 * model Anda adalah YOLOv8 yang diexport dengan:
 * ```
 * model.export(format='tflite', imgsz=640)
 * ```
 */
class YoloV8Detector(
    private val context: Context,
    private val modelPath: String = "chili_disease_model.tflite",
    private val labelPath: String = "labels.txt",
    private val inputSize: Int = 640,       // YOLOv8 default: 640
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "YoloV8Detector"
        private const val NUM_ANCHORS = 8400  // YOLOv8n default untuk 640x640
        private const val BYTES_PER_FLOAT = 4
    }

    private var interpreter: Interpreter? = null
    private val labels = mutableListOf<String>()
    private val numClasses: Int get() = labels.size
    private lateinit var inputBuffer: ByteBuffer

    // Output buffer: [1, (4 + numClasses), NUM_ANCHORS]
    private lateinit var outputBuffer: Array<Array<FloatArray>>

    init {
        loadLabels()
        initInterpreter()
        allocateBuffers()

    }

    // ================================================================
    // SETUP
    // ================================================================

    private fun loadLabels() {
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(labelPath)))
            reader.lineSequence().forEach { line ->
                if (line.trim().isNotEmpty()) labels.add(line.trim())
            }
            reader.close()
        } catch (e: Exception) {
            labels.addAll(listOf("Sehat", "Antraknosa (Patek)", "Layu Fusarium"))
        }
        Log.d(TAG, "Label dimuat: $labels")
    }

    private fun initInterpreter() {
        val options = Interpreter.Options().apply { numThreads = 4 }
        val modelBuffer = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(modelBuffer, options)

        // Log info tensor
        val inputShape = interpreter!!.getInputTensor(0).shape()
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        Log.d(TAG, "YOLOv8 Input shape: ${inputShape.toList()}")
        Log.d(TAG, "YOLOv8 Output shape: ${outputShape.toList()}")
    }

    private fun allocateBuffers() {
        // Input: [1, inputSize, inputSize, 3] float32
        inputBuffer = ByteBuffer.allocateDirect(
            1 * inputSize * inputSize * 3 * BYTES_PER_FLOAT
        ).apply { order(ByteOrder.nativeOrder()) }

        // Output: [1, (4 + numClasses), NUM_ANCHORS]
        // Dinamis berdasarkan shape output aktual
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val rows = outputShape[1]   // 4 + numClasses
        val cols = outputShape[2]   // 8400

        outputBuffer = Array(1) { Array(rows) { FloatArray(cols) } }
        Log.d(TAG, "Output buffer: [1, $rows, $cols]")
    }

    // ================================================================
    // DETEKSI
    // ================================================================

    /**
     * Jalankan deteksi YOLOv8 pada bitmap
     */
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        preprocessBitmap(resized)

        interpreter!!.run(inputBuffer, outputBuffer)

        return parseYoloOutput(outputBuffer[0], bitmap.width, bitmap.height)
    }

    /**
     * Normalisasi bitmap ke float [0, 1]
     */
    private fun preprocessBitmap(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)           // B
        }
    }

    /**
     * Parse output tensor YOLOv8
     *
     * Format output: [rows=(4+numClasses), cols=NUM_ANCHORS]
     * - rows[0..3]: cx, cy, w, h (dinormalisasi 0-1)
     * - rows[4..4+numClasses-1]: skor kelas
     */
    @Suppress("UNUSED_PARAMETER")
    private fun parseYoloOutput(
        output: Array<FloatArray>,
        originalWidth: Int,   // reserved: untuk koordinat piksel absolut jika diperlukan
        originalHeight: Int   // reserved: untuk koordinat piksel absolut jika diperlukan
    ): List<DetectionResult> {
        val numAnchors = output[0].size
        val candidates = mutableListOf<DetectionResult>()

        for (anchorIdx in 0 until numAnchors) {
            // Koordinat box (format center: cx, cy, w, h)
            val cx = output[0][anchorIdx]
            val cy = output[1][anchorIdx]
            val w  = output[2][anchorIdx]
            val h  = output[3][anchorIdx]

            // Cari kelas dengan skor tertinggi
            var maxScore = 0f
            var maxClassIdx = 0
            for (classIdx in 0 until numClasses) {
                val score = output[4 + classIdx][anchorIdx]
                if (score > maxScore) {
                    maxScore = score
                    maxClassIdx = classIdx
                }
            }

            // Filter berdasarkan threshold
            if (maxScore < confidenceThreshold) continue

            // Konversi center format → corner format
            val left   = (cx - w / 2f).coerceIn(0f, 1f)
            val top    = (cy - h / 2f).coerceIn(0f, 1f)
            val right  = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            // Skip box terlalu kecil
            if (right - left < 0.01f || bottom - top < 0.01f) continue

            val label = labels.getOrElse(maxClassIdx) { "Tidak Diketahui" }

            candidates.add(
                DetectionResult(
                    boundingBox = RectF(left, top, right, bottom),
                    label = label,
                    confidence = maxScore,
                    classIndex = maxClassIdx
                )
            )
        }

        Log.v(TAG, "Kandidat sebelum NMS: ${candidates.size}")
        return applyNMS(candidates)
    }

    // ================================================================
    // NON-MAXIMUM SUPPRESSION
    // ================================================================

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        // Kelompokkan per kelas lalu terapkan NMS
        val result = mutableListOf<DetectionResult>()
        val byClass = detections.groupBy { it.classIndex }

        for ((_, classDetections) in byClass) {
            val sorted = classDetections.sortedByDescending { it.confidence }
            val suppressed = BooleanArray(sorted.size)

            for (i in sorted.indices) {
                if (suppressed[i]) continue
                result.add(sorted[i])

                for (j in i + 1 until sorted.size) {
                    if (suppressed[j]) continue
                    if (computeIoU(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold) {
                        suppressed[j] = true
                    }
                }
            }
        }

        Log.v(TAG, "Deteksi setelah NMS: ${result.size}")
        return result
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = aArea + bArea - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    // ================================================================
    // CLEANUP
    // ================================================================

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}