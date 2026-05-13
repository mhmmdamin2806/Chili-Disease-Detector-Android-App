package com.example.chilidisease.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.chilidisease.detector.DetectionResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DetectionHistoryManager
 *
 * Menyimpan dan mengambil riwayat deteksi penyakit menggunakan SharedPreferences + JSON.
 * Riwayat disimpan secara lokal tanpa memerlukan database eksternal.
 *
 * Batas maksimum: 100 entri (otomatis hapus entri paling lama)
 */
class DetectionHistoryManager(private val context: Context) {

    companion object {
        private const val TAG = "DetectionHistoryManager"
        private const val PREF_NAME = "chili_detection_history"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_ENTRIES = 100
        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale("id", "ID"))
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ================================================================
    // DATA CLASS
    // ================================================================

    data class HistoryEntry(
        val id: Long,           // Timestamp (ms) sebagai ID unik
        val timestamp: Long,    // Waktu deteksi
        val label: String,      // Nama penyakit
        val confidence: Float,  // Akurasi (0.0 - 1.0)
        val classIndex: Int     // Index kelas
    ) {
        val dateString: String
            get() = DATE_FORMAT.format(Date(timestamp))

        val confidencePercent: String
            get() = "%.1f%%".format(confidence * 100)

        val isHealthy: Boolean
            get() = classIndex == 0
    }

    // ================================================================
    // WRITE
    // ================================================================

    /**
     * Catat satu hasil deteksi ke riwayat.
     * Duplikat dalam 5 detik terakhir akan diabaikan.
     *
     * @param result Hasil deteksi dari TFLite
     */
    fun record(result: DetectionResult) {
        try {
            val entries = loadAll().toMutableList()
            val now = System.currentTimeMillis()

            // Hindari duplikat (kelas sama dalam 5 detik terakhir)
            val recentSameClass = entries.any { entry ->
                entry.classIndex == result.classIndex && now - entry.timestamp < 5_000L
            }
            if (recentSameClass) return

            // Tambah entri baru
            val newEntry = HistoryEntry(
                id = now,
                timestamp = now,
                label = result.label,
                confidence = result.confidence,
                classIndex = result.classIndex
            )
            entries.add(0, newEntry) // Tambah di awal (terbaru di atas)

            // Batasi jumlah entri
            val trimmed = if (entries.size > MAX_ENTRIES) entries.take(MAX_ENTRIES) else entries

            saveAll(trimmed)
            Log.d(TAG, "Riwayat dicatat: ${result.label} (${result.confidencePercent})")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyimpan riwayat: ${e.message}")
        }
    }

    /**
     * Hapus satu entri berdasarkan ID (timestamp)
     */
    fun delete(entryId: Long) {
        val updated = loadAll().filterNot { it.id == entryId }
        saveAll(updated)
    }

    /**
     * Hapus semua riwayat
     */
    fun clearAll() {
        prefs.edit().remove(KEY_HISTORY).apply()
        Log.d(TAG, "Semua riwayat dihapus")
    }

    // ================================================================
    // READ
    // ================================================================

    /**
     * Ambil semua riwayat, terbaru di atas
     */
    fun loadAll(): List<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            parseJsonToEntries(JSONArray(json))
        } catch (e: Exception) {
            Log.e(TAG, "Gagal parse riwayat: ${e.message}")
            emptyList()
        }
    }

    /**
     * Ambil statistik ringkasan
     */
    fun getStats(): HistoryStats {
        val entries = loadAll()
        if (entries.isEmpty()) return HistoryStats()

        val total = entries.size
        val byClass = entries.groupBy { it.classIndex }
        val healthyCount = byClass[0]?.size ?: 0
        val anthracnoseCount = byClass[1]?.size ?: 0
        val fusariumCount = byClass[2]?.size ?: 0
        val avgConfidence = entries.map { it.confidence }.average().toFloat()
        val mostRecent = entries.firstOrNull()

        return HistoryStats(
            totalDetections = total,
            healthyCount = healthyCount,
            anthracnoseCount = anthracnoseCount,
            fusariumCount = fusariumCount,
            averageConfidence = avgConfidence,
            mostRecentLabel = mostRecent?.label,
            mostRecentTime = mostRecent?.dateString
        )
    }

    /**
     * Ambil riwayat difilter per kelas
     */
    fun loadByClass(classIndex: Int): List<HistoryEntry> =
        loadAll().filter { it.classIndex == classIndex }

    // ================================================================
    // SERIALIZATION
    // ================================================================

    private fun saveAll(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("timestamp", entry.timestamp)
                put("label", entry.label)
                put("confidence", entry.confidence.toDouble())
                put("classIndex", entry.classIndex)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun parseJsonToEntries(array: JSONArray): List<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                HistoryEntry(
                    id = obj.getLong("id"),
                    timestamp = obj.getLong("timestamp"),
                    label = obj.getString("label"),
                    confidence = obj.getDouble("confidence").toFloat(),
                    classIndex = obj.getInt("classIndex")
                )
            )
        }
        return list
    }

    // ================================================================
    // STATS DATA CLASS
    // ================================================================

    data class HistoryStats(
        val totalDetections: Int = 0,
        val healthyCount: Int = 0,
        val anthracnoseCount: Int = 0,
        val fusariumCount: Int = 0,
        val averageConfidence: Float = 0f,
        val mostRecentLabel: String? = null,
        val mostRecentTime: String? = null
    ) {
        val diseaseCount: Int get() = anthracnoseCount + fusariumCount
        val healthPercentage: Float
            get() = if (totalDetections == 0) 0f else healthyCount * 100f / totalDetections
    }
}
