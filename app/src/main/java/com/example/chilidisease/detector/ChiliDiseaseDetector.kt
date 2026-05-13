package com.example.chilidisease.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detektor penyakit cabai berbasis TFLite.
 */
class ChiliDiseaseDetector(
    private val context: Context,
    private val modelPath: String          = "chili_disease_model.tflite",
    private val labelPath: String          = "labels.txt",
    private val useGpu: Boolean            = false,
    private val inputWidth: Int            = 224,
    private val inputHeight: Int           = 224,
    private val confidenceThreshold: Float = 0.50f
) {

    companion object {
        private const val TAG            = "ChiliDetector"
        private const val MAX_DETECTIONS = 10
        private const val IOU_THRESHOLD  = 0.5f
        private const val PIXEL_CHANNELS = 3
    }

    private val interpreter: Interpreter
    private val labels: List<String>

    /** true jika input tensor bertipe UINT8 (quantized model) */
    private val isQuantized: Boolean

    private var outputScale: Float   = 1f
    private var outputZeroPoint: Int = 0

    init {
        val modelBuffer = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(modelBuffer, options)

        labels = loadLabels()

        val inputTensor = interpreter.getInputTensor(0)
        isQuantized = (inputTensor.dataType() == DataType.UINT8)

        if (isQuantized) {
            val q = interpreter.getOutputTensor(0).quantizationParams()
            outputScale     = q.scale
            outputZeroPoint = q.zeroPoint
            Log.d(TAG, "Model QUANTIZED — scale=$outputScale, zeroPoint=$outputZeroPoint")
        } else {
            Log.d(TAG, "Model FLOAT32")
        }

        Log.d(TAG, "Labels(${labels.size}): $labels | Input:${inputWidth}x${inputHeight} | GPU:$useGpu")
    }

    private fun loadLabels(): List<String> {
        return try {
            BufferedReader(InputStreamReader(context.assets.open(labelPath)))
                .readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal baca $labelPath: ${e.message}")
            listOf("Sehat", "Antraknosa (Patek)", "Layu Fusarium")
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val scaled   = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val inputBuf = preprocessBitmap(scaled)
        return when (interpreter.outputTensorCount) {
            1    -> runClassification(inputBuf)
            else -> runObjectDetection(inputBuf)
        }
    }

    fun close() = interpreter.close()

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = if (isQuantized) 1 else Float.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(
            inputWidth * inputHeight * PIXEL_CHANNELS * bytesPerChannel
        ).apply { order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8)  and 0xFF
            val b =  pixel         and 0xFF
            if (isQuantized) {
                buffer.put(r.toByte()); buffer.put(g.toByte()); buffer.put(b.toByte())
            } else {
                buffer.putFloat(r / 255.0f); buffer.putFloat(g / 255.0f); buffer.putFloat(b / 255.0f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun runClassification(inputBuffer: ByteBuffer): List<DetectionResult> {
        val numClasses = labels.size
        val results    = mutableListOf<DetectionResult>()

        if (isQuantized) {
            val rawOutput = Array(1) { ByteArray(numClasses) }
            interpreter.run(inputBuffer, rawOutput)
            rawOutput[0].forEachIndexed { idx, rawByte ->
                val score = dequantize(rawByte.toInt() and 0xFF)
                if (score >= confidenceThreshold) results += makeResult(idx, score)
            }
        } else {
            val rawOutput = Array(1) { FloatArray(numClasses) }
            interpreter.run(inputBuffer, rawOutput)
            rawOutput[0].forEachIndexed { idx, score ->
                if (score >= confidenceThreshold) results += makeResult(idx, score)
            }
        }
        return results.sortedByDescending { it.confidence }.take(MAX_DETECTIONS)
    }

    private fun runObjectDetection(inputBuffer: ByteBuffer): List<DetectionResult> {
        val maxDet     = 10
        val outBoxes   = Array(1) { Array(maxDet) { FloatArray(4) } }
        val outClasses = Array(1) { FloatArray(maxDet) }
        val outScores  = Array(1) { FloatArray(maxDet) }
        val outCount   = FloatArray(1)

        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputBuffer),
            mapOf(0 to outBoxes, 1 to outClasses, 2 to outScores, 3 to outCount)
        )

        val results = mutableListOf<DetectionResult>()
        for (i in 0 until outCount[0].toInt().coerceAtMost(maxDet)) {
            val score = outScores[0][i]
            if (score < confidenceThreshold) continue
            val classIdx = outClasses[0][i].toInt()
            val box      = outBoxes[0][i]
            results += DetectionResult(
                boundingBox = RectF(box[1], box[0], box[3], box[2]),
                label       = labels.getOrElse(classIdx) { "Unknown" },
                confidence  = score,
                classIndex  = classIdx
            )
        }
        return applyNMS(results).take(MAX_DETECTIONS)
    }

    private fun makeResult(idx: Int, score: Float) = DetectionResult(
        boundingBox = RectF(0f, 0f, 1f, 1f),
        label       = labels.getOrElse(idx) { "Unknown" },
        confidence  = score,
        classIndex  = idx
    )

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep   = mutableListOf<DetectionResult>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) >= IOU_THRESHOLD }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left); val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right); val iB = minOf(a.bottom, b.bottom)
        val iArea = maxOf(0f, iR - iL) * maxOf(0f, iB - iT)
        if (iArea == 0f) return 0f
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        return iArea / (aArea + bArea - iArea)
    }

    private fun dequantize(v: Int): Float =
        (outputScale * (v - outputZeroPoint)).coerceIn(0f, 1f)
}