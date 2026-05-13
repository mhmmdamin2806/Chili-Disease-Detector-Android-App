package com.example.chilidisease

import android.graphics.RectF
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.utils.FpsCounter
import org.junit.Assert.*
import org.junit.Test

class DetectionResultTest {

    // ── DetectionResult ──────────────────────────────────

    @Test fun `confidencePercent formats correctly`() {
        assertEquals("87.3%", makeResult(0.873f, 1).confidencePercent)
    }

    @Test fun `confidencePercent handles boundary values`() {
        assertEquals("0.0%",   makeResult(0.000f, 0).confidencePercent)
        assertEquals("100.0%", makeResult(1.000f, 0).confidencePercent)
    }

    @Test fun `displayColor green for healthy`() =
        assertEquals(0xFF00C853.toInt(), makeResult(classIndex = 0).displayColor)

    @Test fun `displayColor orange for anthracnose`() =
        assertEquals(0xFFFF6D00.toInt(), makeResult(classIndex = 1).displayColor)

    @Test fun `displayColor red for fusarium`() =
        assertEquals(0xFFD50000.toInt(), makeResult(classIndex = 2).displayColor)

    @Test fun `displayColor purple for unknown`() =
        assertEquals(0xFF6200EA.toInt(), makeResult(classIndex = 99).displayColor)

    @Test fun `diseaseDescription not empty for all classes`() {
        (0..2).forEach { i -> assertFalse(makeResult(classIndex = i).diseaseDescription.isEmpty()) }
    }

    @Test fun `bounding box values accessible`() {
        val box = RectF(0.1f, 0.2f, 0.8f, 0.9f)
        val r = DetectionResult(box, "Test", 0.9f, 0)
        assertEquals(0.1f, r.boundingBox.left,   0.001f)
        assertEquals(0.9f, r.boundingBox.bottom, 0.001f)
    }

    @Test fun `max confidence detection selected correctly`() {
        val results = listOf(
            makeResult(0.65f, 1), makeResult(0.92f, 2), makeResult(0.78f, 0)
        )
        val top = results.maxByOrNull { it.confidence }!!
        assertEquals(0.92f, top.confidence, 0.001f)
        assertEquals(2, top.classIndex)
    }

    @Test fun `filter by threshold works`() {
        val results = listOf(makeResult(0.30f, 1), makeResult(0.80f, 2), makeResult(0.44f, 0))
        assertEquals(1, results.filter { it.confidence >= 0.45f }.size)
    }

    @Test fun `empty list returns null on maxByOrNull`() {
        assertNull(emptyList<DetectionResult>().maxByOrNull { it.confidence })
    }

    // ── FpsCounter ───────────────────────────────────────

    @Test fun `fps starts at zero`() = assertEquals(0f, FpsCounter().getFps())

    @Test fun `fps increments after tick`() {
        val c = FpsCounter(); c.tick(); c.tick()
        assertTrue(c.getFps() > 0f)
    }

    @Test fun `fps resets after reset()`() {
        val c = FpsCounter()
        repeat(10) { c.tick() }
        c.reset()
        assertEquals(0f, c.getFps())
    }

    @Test fun `inference time tracked`() {
        val c = FpsCounter(); c.tick(45L)
        assertEquals(45L, c.getLastInferenceMs())
    }

    @Test fun `average inference computed`() {
        val c = FpsCounter(); c.tick(40L); c.tick(60L)
        assertEquals(50L, c.getAverageInferenceMs())
    }

    @Test fun `detail label contains ms when inference tracked`() {
        val c = FpsCounter(); c.tick(55L)
        assertTrue(c.getDetailLabel().contains("ms"))
    }

    @Test fun `getFpsLabel contains FPS`() =
        assertTrue(FpsCounter().getFpsLabel().contains("FPS"))

    // ── Helper ────────────────────────────────────────────

    private fun makeResult(confidence: Float = 0.9f, classIndex: Int = 0) =
        DetectionResult(RectF(0f, 0f, 1f, 1f), "Test", confidence, classIndex)
}
