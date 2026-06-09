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

class ChiliDiseaseDetector(
    private val context: Context,
    private val modelPath: String = "chili_disease_model.tflite",
    private val labelPath: String = "labels.txt",
    private val useGpu: Boolean = false,
    private val inputWidth: Int = 224,
    private val inputHeight: Int = 224,
    private val confidenceThreshold: Float = 0.50f
) {

    companion object {
        private const val TAG = "ChiliDetector"
        private const val MAX_DETECTIONS = 10
        private const val IOU_THRESHOLD = 0.5f
        private const val PIXEL_CHANNELS = 3

        /*
         * Threshold tambahan supaya model tidak mudah menyatakan cabai sehat.
         * Jika prediksi healthy di bawah 90%, hasil dibuat "Tidak Yakin".
         */
        private const val HEALTHY_MIN_CONFIDENCE = 0.90f

        /*
         * Threshold umum untuk penyakit.
         */
        private const val MIN_CLASSIFICATION_CONFIDENCE = 0.50f

        /*
         * Jika selisih top-1 dan top-2 terlalu dekat, model dianggap ragu.
         */
        private const val MIN_TOP_MARGIN = 0.08f
    }

    private val interpreter: Interpreter
    private val labels: List<String>

    private val isQuantized: Boolean

    private var outputScale: Float = 1f
    private var outputZeroPoint: Int = 0

    init {
        val modelBuffer = FileUtil.loadMappedFile(context, modelPath)

        val options = Interpreter.Options().apply {
            numThreads = 4
        }

        interpreter = Interpreter(modelBuffer, options)

        labels = loadLabels()

        val inputTensor = interpreter.getInputTensor(0)
        isQuantized = inputTensor.dataType() == DataType.UINT8

        if (isQuantized) {
            val q = interpreter.getOutputTensor(0).quantizationParams()
            outputScale = q.scale
            outputZeroPoint = q.zeroPoint
            Log.d(TAG, "Model QUANTIZED — scale=$outputScale, zeroPoint=$outputZeroPoint")
        } else {
            Log.d(TAG, "Model FLOAT32")
        }

        Log.d(
            TAG,
            "Labels(${labels.size}): $labels | Input:${inputWidth}x${inputHeight} | GPU:$useGpu"
        )
    }

    private fun loadLabels(): List<String> {
        return try {
            BufferedReader(InputStreamReader(context.assets.open(labelPath)))
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal baca $labelPath: ${e.message}")

            /*
             * Fallback sesuai urutan dataset kamu.
             */
            listOf(
                "Antraknosa",
                "Busuk_Buah",
                "Lalat_Buah",
                "cerocospora",
                "healthy"
            )
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        /*
         * Penting:
         * Jangan pasang ChiliPresenceGate di sini.
         * Gate hanya untuk realtime camera di MainActivity.
         * Kalau dipasang di sini, fitur Analisis Foto bisa ikut rusak.
         */

        val inputShape = interpreter.getInputTensor(0).shape()
        val modelHeight = inputShape[1]
        val modelWidth = inputShape[2]

        val scaled = Bitmap.createScaledBitmap(bitmap, modelWidth, modelHeight, true)
        val inputBuffer = preprocessBitmap(scaled, modelWidth, modelHeight)

        return try {
            when (interpreter.outputTensorCount) {
                1 -> runClassification(inputBuffer)
                else -> runObjectDetection(inputBuffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detect error: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        interpreter.close()
    }

    private fun preprocessBitmap(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): ByteBuffer {
        val bytesPerChannel = if (isQuantized) 1 else Float.SIZE_BYTES

        val buffer = ByteBuffer.allocateDirect(
            width * height * PIXEL_CHANNELS * bytesPerChannel
        ).apply {
            order(ByteOrder.nativeOrder())
        }

        val pixels = IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        for (pixel in pixels) {
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF

            if (isQuantized) {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            } else {
                /*
                 * Normalisasi 0..1.
                 * Pastikan ini sama dengan preprocessing saat training.
                 */
                buffer.putFloat(r / 255.0f)
                buffer.putFloat(g / 255.0f)
                buffer.putFloat(b / 255.0f)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun runClassification(inputBuffer: ByteBuffer): List<DetectionResult> {
        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val numClasses = outputShape.lastOrNull() ?: labels.size

        val scores = FloatArray(numClasses)

        if (isQuantized) {
            val rawOutput = Array(1) { ByteArray(numClasses) }
            interpreter.run(inputBuffer, rawOutput)

            for (i in 0 until numClasses) {
                scores[i] = dequantize(rawOutput[0][i].toInt() and 0xFF)
            }
        } else {
            val rawOutput = Array(1) { FloatArray(numClasses) }
            interpreter.run(inputBuffer, rawOutput)

            for (i in 0 until numClasses) {
                scores[i] = rawOutput[0][i]
            }
        }

        val probabilities = scores.indices.map { index ->
            ClassProbability(
                label = labels.getOrElse(index) { "Unknown" },
                confidence = scores[index]
            )
        }.sortedByDescending { it.confidence }

        probabilities.forEachIndexed { index, item ->
            Log.d(
                TAG,
                "CLASS[$index] ${item.label} = ${String.format("%.4f", item.confidence)}"
            )
        }

        val top1 = probabilities.getOrNull(0) ?: return emptyList()
        val top2 = probabilities.getOrNull(1)

        val top1Label = top1.label
        val top1Normalized = normalizeLabel(top1Label)
        val top1Confidence = top1.confidence

        val margin = if (top2 != null) {
            top1.confidence - top2.confidence
        } else {
            1f
        }

        /*
         * Rule 1:
         * Jika confidence terlalu kecil, jangan paksa pilih penyakit.
         */
        if (top1Confidence < MIN_CLASSIFICATION_CONFIDENCE) {
            return listOf(
                createUncertainResult(
                    confidence = top1Confidence,
                    probabilities = probabilities
                )
            )
        }

        /*
         * Rule 2:
         * Jika top-1 dan top-2 terlalu dekat, model dianggap ragu.
         */
        if (margin < MIN_TOP_MARGIN) {
            return listOf(
                createUncertainResult(
                    confidence = top1Confidence,
                    probabilities = probabilities
                )
            )
        }

        /*
         * Rule 3:
         * Healthy harus sangat yakin.
         * Kalau healthy hanya 70%, 76%, 80%, tampilkan "Tidak Yakin".
         */
        if (isHealthyLabel(top1Normalized) && top1Confidence < HEALTHY_MIN_CONFIDENCE) {
            return listOf(
                createUncertainResult(
                    confidence = top1Confidence,
                    probabilities = probabilities
                )
            )
        }

        val bestIndex = labels.indexOf(top1Label).takeIf { it >= 0 } ?: 0

        return listOf(
            makeResult(
                idx = bestIndex,
                score = top1Confidence,
                allProbabilities = probabilities
            )
        )
    }

    private fun runObjectDetection(inputBuffer: ByteBuffer): List<DetectionResult> {
        val maxDet = 10

        val outBoxes = Array(1) { Array(maxDet) { FloatArray(4) } }
        val outClasses = Array(1) { FloatArray(maxDet) }
        val outScores = Array(1) { FloatArray(maxDet) }
        val outCount = FloatArray(1)

        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputBuffer),
            mapOf(
                0 to outBoxes,
                1 to outClasses,
                2 to outScores,
                3 to outCount
            )
        )

        val results = mutableListOf<DetectionResult>()

        for (i in 0 until outCount[0].toInt().coerceAtMost(maxDet)) {
            val score = outScores[0][i]

            if (score < confidenceThreshold) continue

            val classIdx = outClasses[0][i].toInt()
            val label = labels.getOrElse(classIdx) { "Unknown" }

            val box = outBoxes[0][i]

            results += DetectionResult(
                boundingBox = RectF(
                    box[1],
                    box[0],
                    box[3],
                    box[2]
                ),
                label = label,
                confidence = score,
                classIndex = classIdx,
                description = DiseaseInfo.description(label),
                allProbabilities = listOf(
                    ClassProbability(
                        label = label,
                        confidence = score
                    )
                ),
                hasChiliObject = true
            )
        }

        return applyNMS(results).take(MAX_DETECTIONS)
    }

    private fun makeResult(
        idx: Int,
        score: Float,
        allProbabilities: List<ClassProbability>
    ): DetectionResult {
        val label = labels.getOrElse(idx) { "Unknown" }

        return DetectionResult(
            boundingBox = RectF(0f, 0f, 1f, 1f),
            label = label,
            confidence = score,
            classIndex = idx,
            description = DiseaseInfo.description(label),
            allProbabilities = allProbabilities,
            hasChiliObject = true
        )
    }

    private fun createUncertainResult(
        confidence: Float,
        probabilities: List<ClassProbability>
    ): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(0f, 0f, 1f, 1f),
            label = "tidak_yakin",
            confidence = confidence,
            classIndex = -1,
            description = DiseaseInfo.description("tidak_yakin"),
            allProbabilities = probabilities,
            hasChiliObject = true
        )
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best

            sorted.removeAll {
                iou(best.boundingBox, it.boundingBox) >= IOU_THRESHOLD
            }
        }

        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left)
        val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right)
        val iB = minOf(a.bottom, b.bottom)

        val iArea = maxOf(0f, iR - iL) * maxOf(0f, iB - iT)

        if (iArea == 0f) return 0f

        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)

        return iArea / (aArea + bArea - iArea)
    }

    private fun dequantize(v: Int): Float {
        return (outputScale * (v - outputZeroPoint)).coerceIn(0f, 1f)
    }

    private fun normalizeLabel(label: String): String {
        return label
            .lowercase()
            .trim()
            .replace(" ", "_")
            .replace("-", "_")
            .replace("/", "_")
            .replace("(", "")
            .replace(")", "")
    }

    private fun isHealthyLabel(label: String): Boolean {
        return label == "healthy" ||
                label == "healty" ||
                label == "sehat"
    }
}