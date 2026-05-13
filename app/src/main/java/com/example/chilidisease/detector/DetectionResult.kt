package com.example.chilidisease.detector

import android.graphics.RectF

/**
 * Data class untuk menyimpan hasil deteksi penyakit cabai.
 *
 * @param boundingBox Koordinat bounding box dalam format RectF (left, top, right, bottom)
 *                    dengan nilai dinormalisasi antara 0.0 - 1.0
 * @param label Nama penyakit yang terdeteksi
 * @param confidence Tingkat kepercayaan/akurasi deteksi (0.0 - 1.0)
 * @param classIndex Index kelas dari label
 */
data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float,
    val classIndex: Int
) {
    /**
     * Mengembalikan persentase akurasi dalam format string
     */
    val confidencePercent: String
        get() = "%.1f%%".format(confidence * 100)

    /**
     * Menentukan warna berdasarkan jenis penyakit
     */
    val displayColor: Int
        get() = when (classIndex) {
            0 -> 0xFF00C853.toInt()    // Hijau - Sehat
            1 -> 0xFFFF6D00.toInt()    // Oranye - Antraknosa (Patek)
            2 -> 0xFFD50000.toInt()    // Merah - Layu Fusarium
            else -> 0xFF6200EA.toInt() // Ungu - Tidak diketahui
        }

    /**
     * Menentukan deskripsi singkat penyakit
     */
    val diseaseDescription: String
        get() = when (classIndex) {
            0 -> "Tanaman sehat, tidak ada penyakit terdeteksi"
            1 -> "Antraknosa: Bercak hitam pada buah cabai"
            2 -> "Layu Fusarium: Infeksi jamur pada akar/batang"
            else -> "Penyakit tidak diidentifikasi"
        }
}

/**
 * Konstanta kelas penyakit cabai
 */
object DiseaseClass {
    const val HEALTHY = 0
    const val ANTHRACNOSE = 1     // Antraknosa (Patek)
    const val FUSARIUM_WILT = 2   // Layu Fusarium

    val LABELS = mapOf(
        HEALTHY to "Sehat",
        ANTHRACNOSE to "Antraknosa (Patek)",
        FUSARIUM_WILT to "Layu Fusarium"
    )
}
