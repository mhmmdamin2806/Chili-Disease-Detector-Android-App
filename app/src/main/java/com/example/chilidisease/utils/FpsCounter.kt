package com.example.chilidisease.utils

/**
 * FpsCounter
 *
 * Menghitung FPS (frame per detik) secara akurat menggunakan
 * sliding window dari timestamp frame terakhir.
 *
 * Penggunaan:
 * ```kotlin
 * val fpsCounter = FpsCounter()
 *
 * // Panggil setiap frame selesai diproses
 * fpsCounter.tick()
 *
 * // Ambil FPS saat ini
 * val fps = fpsCounter.getFps()
 * val label = fpsCounter.getFpsLabel() // "28.5 FPS"
 * ```
 */
class FpsCounter(
    private val windowSizeMs: Long = 1000L,  // Jendela waktu 1 detik
    private val maxSamples: Int = 60          // Maksimum sampel
) {
    private val timestamps = ArrayDeque<Long>(maxSamples)
    private var lastFps = 0f

    // Statistik inferensi (milidetik)
    private var lastInferenceMs = 0L
    private var totalInferenceMs = 0L
    private var frameCount = 0L

    /**
     * Catat satu frame selesai diproses.
     * Panggil setiap kali inferensi berhasil.
     *
     * @param inferenceMs Durasi inferensi dalam milidetik (opsional)
     */
    fun tick(inferenceMs: Long = 0) {
        val now = System.currentTimeMillis()

        // Hapus timestamp yang sudah lebih dari windowSize
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowSizeMs) {
            timestamps.removeFirst()
        }

        timestamps.addLast(now)
        frameCount++

        if (inferenceMs > 0) {
            lastInferenceMs = inferenceMs
            totalInferenceMs += inferenceMs
        }

        // Hitung FPS dari jumlah timestamp dalam jendela waktu
        lastFps = timestamps.size.toFloat()
    }

    /**
     * Ambil nilai FPS saat ini
     */
    fun getFps(): Float = lastFps

    /**
     * Ambil label FPS siap tampil
     */
    fun getFpsLabel(): String = "%.1f FPS".format(lastFps)

    /**
     * Ambil durasi inferensi terakhir dalam milidetik
     */
    fun getLastInferenceMs(): Long = lastInferenceMs

    /**
     * Ambil rata-rata durasi inferensi
     */
    fun getAverageInferenceMs(): Long {
        return if (frameCount == 0L) 0L else totalInferenceMs / frameCount
    }

    /**
     * Ambil label info lengkap (FPS + waktu inferensi)
     */
    fun getDetailLabel(): String {
        return if (lastInferenceMs > 0) {
            "${getFpsLabel()} | ${lastInferenceMs}ms"
        } else {
            getFpsLabel()
        }
    }

    /**
     * Reset semua counter
     */
    fun reset() {
        timestamps.clear()
        lastFps = 0f
        lastInferenceMs = 0
        totalInferenceMs = 0
        frameCount = 0
    }
}
