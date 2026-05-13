package com.example.chilidisease

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.history.DetectionHistoryManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests untuk DetectionHistoryManager.
 * Dijalankan di emulator/perangkat nyata (memerlukan Context Android).
 *
 * Jalankan: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DetectionHistoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var manager: DetectionHistoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = DetectionHistoryManager(context)
        manager.clearAll()  // Mulai bersih
    }

    @After
    fun tearDown() {
        manager.clearAll()
    }

    // ── WRITE / READ ──

    @Test
    fun initiallyEmpty() {
        val entries = manager.loadAll()
        assertTrue("Seharusnya kosong setelah clearAll", entries.isEmpty())
    }

    @Test
    fun recordAndLoadOneEntry() {
        manager.record(makeDetection(classIndex = 1, confidence = 0.88f))
        val entries = manager.loadAll()
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].classIndex)
        assertEquals("Antraknosa (Patek)", entries[0].label)
    }

    @Test
    fun multipleEntriesLoadedInReverseOrder() {
        manager.record(makeDetection(classIndex = 0, confidence = 0.72f))
        Thread.sleep(10) // Pastikan timestamp berbeda
        manager.record(makeDetection(classIndex = 1, confidence = 0.85f))
        Thread.sleep(10)
        manager.record(makeDetection(classIndex = 2, confidence = 0.91f))

        val entries = manager.loadAll()
        assertEquals(3, entries.size)
        // Terbaru di index 0
        assertEquals(2, entries[0].classIndex)
        assertEquals(0, entries[2].classIndex)
    }

    @Test
    fun deleteRemovesCorrectEntry() {
        manager.record(makeDetection(classIndex = 1, confidence = 0.80f))
        Thread.sleep(10)
        manager.record(makeDetection(classIndex = 2, confidence = 0.91f))

        val entries = manager.loadAll()
        assertEquals(2, entries.size)

        // Hapus entry pertama
        manager.delete(entries[0].id)

        val remaining = manager.loadAll()
        assertEquals(1, remaining.size)
        assertEquals(entries[1].id, remaining[0].id)
    }

    @Test
    fun clearAllRemovesEverything() {
        repeat(5) { i ->
            manager.record(makeDetection(classIndex = i % 3, confidence = 0.75f))
            Thread.sleep(10)
        }
        assertTrue(manager.loadAll().isNotEmpty())
        manager.clearAll()
        assertTrue(manager.loadAll().isEmpty())
    }

    // ── DEDUPLICATION ──

    @Test
    fun duplicateInCooldownWindowIsIgnored() {
        // Dua deteksi kelas sama dalam waktu singkat → hanya satu dicatat
        manager.record(makeDetection(classIndex = 1, confidence = 0.80f))
        manager.record(makeDetection(classIndex = 1, confidence = 0.85f))
        val entries = manager.loadAll()
        assertEquals(1, entries.size)
    }

    @Test
    fun differentClassesAreNotDeduplicated() {
        manager.record(makeDetection(classIndex = 1, confidence = 0.80f))
        manager.record(makeDetection(classIndex = 2, confidence = 0.88f))
        val entries = manager.loadAll()
        assertEquals(2, entries.size)
    }

    // ── STATISTICS ──

    @Test
    fun statsEmptyWhenNoEntries() {
        val stats = manager.getStats()
        assertEquals(0, stats.totalDetections)
        assertEquals(0f, stats.healthPercentage, 0.001f)
    }

    @Test
    fun statsCountsCorrectly() {
        manager.record(makeDetection(classIndex = 0, confidence = 0.72f))
        Thread.sleep(6000) // Keluar dari cooldown window untuk kelas 0
        // Untuk test singkat, kita hanya uji kelas berbeda
        manager.record(makeDetection(classIndex = 1, confidence = 0.80f))
        manager.record(makeDetection(classIndex = 2, confidence = 0.90f))

        val stats = manager.getStats()
        assertTrue(stats.totalDetections >= 2)
        assertTrue(stats.anthracnoseCount >= 1)
        assertTrue(stats.fusariumCount >= 1)
    }

    @Test
    fun averageConfidenceIsCorrect() {
        // Catat entry langsung dengan manipulasi (menggunakan kelas berbeda agar tidak terdeduplikasi)
        manager.record(makeDetection(classIndex = 1, confidence = 0.80f))
        manager.record(makeDetection(classIndex = 2, confidence = 0.90f))

        val stats = manager.getStats()
        // Rata-rata: (0.80 + 0.90) / 2 = 0.85
        assertTrue(stats.averageConfidence in 0.79f..0.91f)
    }

    // ── FILTER BY CLASS ──

    @Test
    fun loadByClassFiltersCorrectly() {
        manager.record(makeDetection(classIndex = 1, confidence = 0.80f))
        manager.record(makeDetection(classIndex = 2, confidence = 0.88f))

        val anthracnoseEntries = manager.loadByClass(1)
        val fusariumEntries    = manager.loadByClass(2)

        assertTrue(anthracnoseEntries.all { it.classIndex == 1 })
        assertTrue(fusariumEntries.all { it.classIndex == 2 })
    }

    // ── HELPER ──

    private fun makeDetection(
        classIndex: Int = 1,
        confidence: Float = 0.85f
    ): DetectionResult {
        val label = when (classIndex) {
            0 -> "Sehat"
            1 -> "Antraknosa (Patek)"
            2 -> "Layu Fusarium"
            else -> "Unknown"
        }
        return DetectionResult(
            boundingBox = RectF(0.1f, 0.1f, 0.9f, 0.9f),
            label = label,
            confidence = confidence,
            classIndex = classIndex
        )
    }
}
