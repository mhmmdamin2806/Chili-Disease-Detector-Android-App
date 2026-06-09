package com.example.chilidisease.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import kotlin.math.max

class RoboflowRemoteDetector(
    private val context: Context
) {

    companion object {
        private const val TAG = "RoboflowRemoteDetector"
    }

    private val api: RoboflowApi

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(RoboflowConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(RoboflowApi::class.java)
    }

    suspend fun detect(bitmap: Bitmap): List<DetectionResult> {
        val resizedBitmap = resizeBitmap(bitmap, maxSize = 1024)
        val base64Image = bitmapToBase64(resizedBitmap)

        val requestBody = RequestBody.create(
            "application/x-www-form-urlencoded".toMediaType(),
            base64Image
        )

        val response = api.infer(
            modelId = RoboflowConfig.MODEL_ID,
            versionId = RoboflowConfig.MODEL_VERSION,
            apiKey = RoboflowConfig.API_KEY,
            confidence = RoboflowConfig.MIN_CONFIDENCE,
            imageBody = requestBody
        )

        Log.d(TAG, "Roboflow response: $response")

        return parseResponse(response)
    }

    fun close() {
        // Tidak perlu close interpreter karena ini remote API.
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            85,
            outputStream
        )

        val bytes = outputStream.toByteArray()

        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP
        )
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val ratio = width.toFloat() / height.toFloat()

        val newWidth: Int
        val newHeight: Int

        if (width >= height) {
            newWidth = maxSize
            newHeight = (maxSize / ratio).toInt().coerceAtLeast(1)
        } else {
            newHeight = maxSize
            newWidth = (maxSize * ratio).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(
            bitmap,
            newWidth,
            newHeight,
            true
        )
    }

    private fun parseResponse(json: JsonObject): List<DetectionResult> {
        /*
         * Roboflow classification response umumnya punya:
         * top: "class_name"
         * confidence: 0.x
         * predictions: [...]
         *
         * Object detection response umumnya:
         * predictions: [
         *   { x, y, width, height, confidence, class }
         * ]
         *
         * Parser ini dibuat fleksibel agar bisa membaca classification
         * maupun detection response.
         */

        if (json.has("predictions")) {
            val predictionsElement = json.get("predictions")

            if (predictionsElement.isJsonArray) {
                val array = predictionsElement.asJsonArray

                if (isObjectDetectionArray(array)) {
                    return parseObjectDetection(json, array)
                }

                return parseClassificationArray(array)
            }

            if (predictionsElement.isJsonObject) {
                return parseClassificationObject(predictionsElement.asJsonObject)
            }
        }

        if (json.has("top") && json.has("confidence")) {
            val label = json.get("top").asString
            val confidence = json.get("confidence").asFloat

            return listOf(
                makeClassificationResult(
                    label = applyHealthyGuard(label, confidence),
                    originalLabel = label,
                    confidence = confidence,
                    allProbabilities = emptyList()
                )
            )
        }

        return listOf(
            DetectionResult(
                boundingBox = RectF(0f, 0f, 1f, 1f),
                label = "tidak_yakin",
                confidence = 0f,
                classIndex = -1,
                description = DiseaseInfo.description("tidak_yakin"),
                allProbabilities = emptyList(),
                hasChiliObject = true
            )
        )
    }

    private fun isObjectDetectionArray(array: JsonArray): Boolean {
        if (array.size() == 0) return false

        val first = array[0]

        if (!first.isJsonObject) return false

        val obj = first.asJsonObject

        return obj.has("x") &&
                obj.has("y") &&
                obj.has("width") &&
                obj.has("height") &&
                obj.has("class")
    }

    private fun parseObjectDetection(
        root: JsonObject,
        array: JsonArray
    ): List<DetectionResult> {
        val imageWidth = root.getAsJsonObject("image")?.get("width")?.asFloat ?: 1f
        val imageHeight = root.getAsJsonObject("image")?.get("height")?.asFloat ?: 1f

        val results = mutableListOf<DetectionResult>()

        for (item in array) {
            if (!item.isJsonObject) continue

            val obj = item.asJsonObject

            val label = obj.get("class")?.asString ?: "Unknown"
            val confidence = obj.get("confidence")?.asFloat ?: 0f

            val x = obj.get("x")?.asFloat ?: 0f
            val y = obj.get("y")?.asFloat ?: 0f
            val width = obj.get("width")?.asFloat ?: imageWidth
            val height = obj.get("height")?.asFloat ?: imageHeight

            val left = ((x - width / 2f) / imageWidth).coerceIn(0f, 1f)
            val top = ((y - height / 2f) / imageHeight).coerceIn(0f, 1f)
            val right = ((x + width / 2f) / imageWidth).coerceIn(0f, 1f)
            val bottom = ((y + height / 2f) / imageHeight).coerceIn(0f, 1f)

            val finalLabel = applyHealthyGuard(label, confidence)

            results += DetectionResult(
                boundingBox = RectF(left, top, right, bottom),
                label = finalLabel,
                confidence = confidence,
                classIndex = -1,
                description = DiseaseInfo.description(finalLabel),
                allProbabilities = listOf(
                    ClassProbability(
                        label = label,
                        confidence = confidence
                    )
                ),
                hasChiliObject = true
            )
        }

        return results.sortedByDescending { it.confidence }
    }

    private fun parseClassificationArray(array: JsonArray): List<DetectionResult> {
        val probabilities = mutableListOf<ClassProbability>()

        for (item in array) {
            if (!item.isJsonObject) continue

            val obj = item.asJsonObject

            val label = when {
                obj.has("class") -> obj.get("class").asString
                obj.has("label") -> obj.get("label").asString
                else -> "Unknown"
            }

            val confidence = when {
                obj.has("confidence") -> obj.get("confidence").asFloat
                obj.has("score") -> obj.get("score").asFloat
                else -> 0f
            }

            probabilities += ClassProbability(
                label = label,
                confidence = confidence
            )
        }

        val sorted = probabilities.sortedByDescending { it.confidence }

        val top = sorted.firstOrNull()
            ?: return emptyList()

        val finalLabel = applyHealthyGuard(top.label, top.confidence)

        return listOf(
            makeClassificationResult(
                label = finalLabel,
                originalLabel = top.label,
                confidence = top.confidence,
                allProbabilities = sorted
            )
        )
    }

    private fun parseClassificationObject(obj: JsonObject): List<DetectionResult> {
        val probabilities = mutableListOf<ClassProbability>()

        for ((label, value) in obj.entrySet()) {
            if (!value.isJsonObject) continue

            val predObj = value.asJsonObject

            val confidence = when {
                predObj.has("confidence") -> predObj.get("confidence").asFloat
                predObj.has("score") -> predObj.get("score").asFloat
                else -> 0f
            }

            probabilities += ClassProbability(
                label = label,
                confidence = confidence
            )
        }

        val sorted = probabilities.sortedByDescending { it.confidence }

        val top = sorted.firstOrNull()
            ?: return emptyList()

        val finalLabel = applyHealthyGuard(top.label, top.confidence)

        return listOf(
            makeClassificationResult(
                label = finalLabel,
                originalLabel = top.label,
                confidence = top.confidence,
                allProbabilities = sorted
            )
        )
    }

    private fun makeClassificationResult(
        label: String,
        originalLabel: String,
        confidence: Float,
        allProbabilities: List<ClassProbability>
    ): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(0f, 0f, 1f, 1f),
            label = label,
            confidence = confidence,
            classIndex = -1,
            description = DiseaseInfo.description(label),
            allProbabilities = if (allProbabilities.isNotEmpty()) {
                allProbabilities
            } else {
                listOf(
                    ClassProbability(
                        label = originalLabel,
                        confidence = confidence
                    )
                )
            },
            hasChiliObject = true
        )
    }

    private fun applyHealthyGuard(label: String, confidence: Float): String {
        val normalized = normalizeLabel(label)

        return if (
            isHealthyLabel(normalized) &&
            confidence < RoboflowConfig.HEALTHY_MIN_CONFIDENCE
        ) {
            "tidak_yakin"
        } else {
            label
        }
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

    interface RoboflowApi {
        @POST("{modelId}/{versionId}")
        suspend fun infer(
            @Path("modelId") modelId: String,
            @Path("versionId") versionId: String,
            @Query("api_key") apiKey: String,
            @Query("confidence") confidence: Float,
            @Query("format") format: String = "json",
            @Body imageBody: RequestBody
        ): JsonObject
    }
}