package com.example.chilidisease.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryExporter(private val context: Context) {

    companion object {
        private const val TAG = "HistoryExporter"
        private val FILE_DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        private val DATE_FMT      = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))
        private val TIME_FMT      = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private const val FILE_PROVIDER_AUTHORITY = "com.example.chilidisease.fileprovider"
    }

    fun exportAndShare(entries: List<DetectionHistoryManager.HistoryEntry>): Boolean {
        if (entries.isEmpty()) return false
        return try {
            val csvFile = writeCsv(entries)
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, csvFile)
            openShareSheet(uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal ekspor: ${e.message}")
            false
        }
    }

    private fun writeCsv(entries: List<DetectionHistoryManager.HistoryEntry>): File {
        val timestamp = FILE_DATE_FMT.format(Date())
        val file = File(context.cacheDir, "riwayat_deteksi_cabai_$timestamp.csv")

        FileWriter(file).use { w ->
            w.write("No,Tanggal,Waktu,Nama Penyakit,Akurasi (%),Kelas\n")
            entries.forEachIndexed { idx, e ->
                val date       = DATE_FMT.format(Date(e.timestamp))
                val time       = TIME_FMT.format(Date(e.timestamp))
                val confidence = "%.1f".format(e.confidence * 100)
                // Nama ramah pengguna untuk 5 kelas
                val className = when (e.classIndex) {
                    0 -> "Antraknosa"
                    1 -> "Busuk Buah"
                    2 -> "Lalat Buah"
                    3 -> "Cercospora"
                    4 -> "Sehat"
                    else -> "Tidak Diketahui"
                }
                val safeName = if (e.label.contains(",")) "\"${e.label}\"" else e.label
                w.write("${idx+1},$date,$time,$safeName,$confidence,$className\n")
            }

            val byClass = entries.groupBy { it.classIndex }
            w.write("\n--- RINGKASAN ---\n")
            w.write("Total Deteksi,${entries.size}\n")
            w.write("Antraknosa,${byClass[0]?.size ?: 0}\n")
            w.write("Busuk Buah,${byClass[1]?.size ?: 0}\n")
            w.write("Lalat Buah,${byClass[2]?.size ?: 0}\n")
            w.write("Cercospora,${byClass[3]?.size ?: 0}\n")
            w.write("Sehat,${byClass[4]?.size ?: 0}\n")
            val avg = if (entries.isEmpty()) 0f else entries.map { it.confidence }.average().toFloat()
            w.write("Rata-rata Akurasi,%.1f%%\n".format(avg * 100))
            w.write("Diekspor pada,${SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id","ID")).format(Date())}\n")
        }
        return file
    }

    private fun openShareSheet(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Riwayat Deteksi Penyakit Cabai")
            putExtra(Intent.EXTRA_TEXT, "Data riwayat deteksi penyakit cabai.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Bagikan Riwayat")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}