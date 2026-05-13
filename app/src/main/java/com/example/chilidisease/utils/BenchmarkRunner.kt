package com.example.chilidisease.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.chilidisease.detector.ChiliDiseaseDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BenchmarkRunner
 *
 * Mengukur performa inferensi TFLite model pada perangkat saat ini.
 * Berguna untuk menentukan interval deteksi yang optimal.
 *
 * Contoh penggunaan di Activity:
 * ```kotlin
 * lifecycleScope.launch {
 *     val result = BenchmarkRunner.run(context, warmupRuns = 5, measureRuns = 20)
 *     Log.d("Bench", result.summary())
 * }
 * ```
 */
object BenchmarkRunner {

    private const val TAG = "BenchmarkRunner"

    // Default input size — sesuaikan jika model Anda bukan 224x224
    private const val DEFAULT_INPUT_WIDTH  = 224
    private const val DEFAULT_INPUT_HEIGHT = 224

    data class BenchmarkResult(
        val warmupRuns: Int,
        val measureRuns: Int,
        val minMs: Long,
        val maxMs: Long,
        val avgMs: Long,
        val p50Ms: Long,
        val p95Ms: Long,
        val inferenceTimesMs: List<Long>
    ) {
        fun summary(): String = buildString {
            appendLine("════ Benchmark TFLite ════")
            appendLine("Runs      : $measureRuns (warmup: $warmupRuns)")
            appendLine("Min       : ${minMs}ms")
            appendLine("Max       : ${maxMs}ms")
            appendLine("Avg       : ${avgMs}ms")
            appendLine("P50       : ${p50Ms}ms")
            appendLine("P95       : ${p95Ms}ms")
            appendLine("Max FPS   : ~${if (avgMs > 0) 1000 / avgMs else 0}")
            appendLine("Rekomendasi interval: ${(avgMs * 1.2).toLong()}ms")
        }

        fun recommendedIntervalMs(): Long = (avgMs * 1.2).toLong().coerceAtLeast(50L)
    }

    /**
     * Jalankan benchmark secara async di Dispatchers.IO.
     *
     * @param context     Android context
     * @param warmupRuns  Jumlah run pemanasan (tidak dihitung)
     * @param measureRuns Jumlah run pengukuran aktual
     * @param inputWidth  Lebar gambar input (default 224, sesuaikan dengan model)
     * @param inputHeight Tinggi gambar input (default 224, sesuaikan dengan model)
     */
    suspend fun run(
        context: Context,
        warmupRuns: Int   = 5,
        measureRuns: Int  = 20,
        inputWidth: Int   = DEFAULT_INPUT_WIDTH,
        inputHeight: Int  = DEFAULT_INPUT_HEIGHT
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Memulai benchmark ($warmupRuns warmup + $measureRuns measure)...")

        val dummyBitmap = createDummyBitmap(inputWidth, inputHeight)
        var detector: ChiliDiseaseDetector? = null

        return@withContext try {
            detector = ChiliDiseaseDetector(
                context      = context,
                inputWidth   = inputWidth,
                inputHeight  = inputHeight
            )

            // ── Warmup (buang cache cold start) ──
            repeat(warmupRuns) { detector.detect(dummyBitmap) }
            Log.d(TAG, "Warmup selesai")

            // ── Pengukuran ──
            val times = mutableListOf<Long>()
            repeat(measureRuns) {
                val t0 = System.currentTimeMillis()
                detector.detect(dummyBitmap)
                val elapsed = System.currentTimeMillis() - t0
                times.add(elapsed)
                Log.v(TAG, "  Run ${it + 1}: ${elapsed}ms")
            }

            val sorted = times.sorted()
            val result = BenchmarkResult(
                warmupRuns       = warmupRuns,
                measureRuns      = measureRuns,
                minMs            = sorted.first(),
                maxMs            = sorted.last(),
                avgMs            = times.average().toLong(),
                p50Ms            = sorted[sorted.size / 2],
                p95Ms            = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)],
                inferenceTimesMs = times
            )

            Log.d(TAG, result.summary())
            result

        } finally {
            detector?.close()
            dummyBitmap.recycle()
        }
    }

    /**
     * Buat bitmap dummy berwarna hijau (representasi tanaman cabai)
     */
    private fun createDummyBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.parseColor("#4CAF50")) // Hijau daun
        return bmp
    }
}