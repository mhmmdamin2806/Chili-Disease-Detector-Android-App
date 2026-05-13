package com.example.chilidisease

import android.graphics.RectF
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.history.DetectionHistoryManager
import org.junit.Assert.*
import org.junit.Test

class HistoryEntryTest {

    // ── HistoryEntry ──────────────────────────────────────

    @Test fun `confidencePercent one decimal place`() =
        assertEquals("87.6%", makeEntry(0.876f).confidencePercent)

    @Test fun `isHealthy true only for class 0`() {
        assertTrue(makeEntry(classIndex = 0).isHealthy)
        assertFalse(makeEntry(classIndex = 1).isHealthy)
        assertFalse(makeEntry(classIndex = 2).isHealthy)
    }

    @Test fun `dateString non-empty`() =
        assertTrue(makeEntry().dateString.isNotEmpty())

    @Test fun `id equals timestamp`() {
        val ts = System.currentTimeMillis()
        val e  = makeEntry(timestamp = ts)
        assertEquals(ts, e.id)
    }

    // ── HistoryStats ──────────────────────────────────────

    @Test fun `diseaseCount sums anthracnose and fusarium`() {
        val stats = DetectionHistoryManager.HistoryStats(
            totalDetections  = 10,
            healthyCount     = 5,
            anthracnoseCount = 3,
            fusariumCount    = 2
        )
        assertEquals(5, stats.diseaseCount)
    }

    @Test fun `healthPercentage correct`() {
        val stats = DetectionHistoryManager.HistoryStats(
            totalDetections = 10, healthyCount = 7,
            anthracnoseCount = 2, fusariumCount = 1
        )
        assertEquals(70f, stats.healthPercentage, 0.1f)
    }

    @Test fun `healthPercentage zero when no detections`() =
        assertEquals(0f, DetectionHistoryManager.HistoryStats().healthPercentage, 0.001f)

    @Test fun `empty stats default values`() {
        val s = DetectionHistoryManager.HistoryStats()
        assertEquals(0,  s.totalDetections)
        assertEquals(0,  s.diseaseCount)
        assertEquals(0f, s.averageConfidence)
        assertNull(s.mostRecentLabel)
    }

    // ── DetectionResult collection helpers ───────────────

    @Test fun `sort by confidence descending`() {
        val list = listOf(det(0.5f), det(0.9f), det(0.7f))
        val sorted = list.sortedByDescending { it.confidence }
        assertEquals(0.9f, sorted.first().confidence, 0.001f)
        assertEquals(0.5f, sorted.last().confidence,  0.001f)
    }

    @Test fun `groupBy classIndex works`() {
        val list = listOf(det(0.8f, 0), det(0.7f, 1), det(0.9f, 1), det(0.6f, 2))
        val grouped = list.groupBy { it.classIndex }
        assertEquals(2, grouped[1]?.size)
        assertEquals(1, grouped[0]?.size)
        assertEquals(1, grouped[2]?.size)
    }

    @Test fun `filter above threshold`() {
        val list = listOf(det(0.30f), det(0.80f), det(0.44f), det(0.92f))
        assertEquals(2, list.filter { it.confidence >= 0.75f }.size)
    }

    // ── Helpers ───────────────────────────────────────────

    private fun makeEntry(
        confidence: Float = 0.85f,
        classIndex: Int   = 1,
        label: String     = "Antraknosa (Patek)",
        timestamp: Long   = System.currentTimeMillis()
    ) = DetectionHistoryManager.HistoryEntry(
        id         = timestamp,
        timestamp  = timestamp,
        label      = label,
        confidence = confidence,
        classIndex = classIndex
    )

    private fun det(confidence: Float, classIndex: Int = 1) =
        DetectionResult(RectF(0.1f, 0.1f, 0.9f, 0.9f), "T", confidence, classIndex)
}
