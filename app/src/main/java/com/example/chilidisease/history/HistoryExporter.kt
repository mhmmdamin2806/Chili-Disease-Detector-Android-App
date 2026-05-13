package com.example.chilidisease.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HistoryExporter
 * Format CSV:
 * ID,Tanggal,Waktu,Nama Penyakit,Akurasi (%),Kelas Index
 * 1700000001234,14 Apr 2026,09:30:12,Antraknosa (Patek),87.3,1
 */
class HistoryExporter(private val context: Context) {

    companion object {
        private const val TAG = "ChiliDiseaseDetector"

        const val MODEL_INPUT_WIDTH  = 224
        const val MODEL_INPUT_HEIGHT = 224

        const val CONFIDENCE_THRESHOLD = 0.70f
        const val MAX_DETECTIONS = 10

        private const val BYTES_PER_CHANNEL = 1
        private const val NUM_CHANNELS = 3

        // ── Format tanggal & waktu ──────────────────────────────────────────
        /** Dipakai untuk nama file CSV */
        private val FILE_DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

        /** Dipakai untuk kolom Tanggal di CSV */
        private val DATE_FMT = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))

        /** Dipakai untuk kolom Waktu di CSV */
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        // ── FileProvider authority — harus sama dengan AndroidManifest.xml ─
        // Contoh di Manifest:
        //   android:authorities="${applicationId}.fileprovider"
        private const val FILE_PROVIDER_AUTHORITY = "com.example.chilidisease.fileprovider"
    }

    /**
     * Ekspor riwayat ke CSV dan buka Share Sheet.
     *
     * @param entries Daftar riwayat yang akan diekspor
     * @return true jika berhasil membuat file dan membuka share sheet
     */
    fun exportAndShare(entries: List<DetectionHistoryManager.HistoryEntry>): Boolean {
        if (entries.isEmpty()) return false

        return try {
            val csvFile = writeCsv(entries)
            val uri = getFileUri(csvFile)
            openShareSheet(uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal ekspor: ${e.message}")
            false
        }
    }

    /**
     * Tulis data ke file CSV di cache directory
     */
    private fun writeCsv(entries: List<DetectionHistoryManager.HistoryEntry>): File {
        val timestamp = FILE_DATE_FMT.format(Date())
        val fileName = "riwayat_deteksi_cabai_$timestamp.csv"
        val file = File(context.cacheDir, fileName)

        FileWriter(file).use { writer ->
            // Header
            writer.write("No,Tanggal,Waktu,Nama Penyakit,Akurasi (%),Kelas\n")

            // Data rows
            entries.forEachIndexed { idx, entry ->
                val date = DATE_FMT.format(Date(entry.timestamp))
                val time = TIME_FMT.format(Date(entry.timestamp))
                val confidence = "%.1f".format(entry.confidence * 100)
                val className = when (entry.classIndex) {
                    0 -> "Sehat"
                    1 -> "Antraknosa"
                    2 -> "Layu Fusarium"
                    else -> "Tidak Diketahui"
                }
                // Escape koma dalam nama dengan kutip
                val safeName = if (entry.label.contains(","))
                    "\"${entry.label}\"" else entry.label

                writer.write("${idx + 1},$date,$time,$safeName,$confidence,$className\n")
            }

            // Footer statistik
            val stats = summarize(entries)
            writer.write("\n")
            writer.write("--- RINGKASAN ---\n")
            writer.write("Total Deteksi,${entries.size}\n")
            writer.write("Sehat,${stats.healthyCount}\n")
            writer.write("Antraknosa (Patek),${stats.anthracnoseCount}\n")
            writer.write("Layu Fusarium,${stats.fusariumCount}\n")
            writer.write("Rata-rata Akurasi,%.1f%%\n".format(stats.avgConfidence * 100))
            writer.write("Diekspor pada,${SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id","ID")).format(Date())}\n")
        }

        Log.d(TAG, "CSV dibuat: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    /**
     * Dapatkan URI yang bisa dibagikan via FileProvider
     */
    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }

    /**
     * Buka Android Share Sheet
     */
    private fun openShareSheet(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Riwayat Deteksi Penyakit Cabai")
            putExtra(
                Intent.EXTRA_TEXT,
                "Data riwayat deteksi penyakit cabai dari aplikasi Deteksi Penyakit Cabai."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Bagikan Riwayat Deteksi")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // ================================================================
    // STATS HELPER
    // ================================================================

    private data class ExportStats(
        val healthyCount: Int,
        val anthracnoseCount: Int,
        val fusariumCount: Int,
        val avgConfidence: Float
    )

    private fun summarize(entries: List<DetectionHistoryManager.HistoryEntry>): ExportStats {
        val byClass = entries.groupBy { it.classIndex }
        return ExportStats(
            healthyCount     = byClass[0]?.size ?: 0,
            anthracnoseCount = byClass[1]?.size ?: 0,
            fusariumCount    = byClass[2]?.size ?: 0,
            avgConfidence    = if (entries.isEmpty()) 0f
                              else entries.map { it.confidence }.average().toFloat()
        )
    }
}
