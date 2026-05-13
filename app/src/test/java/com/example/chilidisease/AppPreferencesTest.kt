package com.example.chilidisease

import com.example.chilidisease.detector.ModelConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test untuk logika ModelConfig (tidak memerlukan Context Android).
 */
class ModelConfigTest {

    @Test fun `TeachableMachine has 224 input size`() {
        val cfg = ModelConfig.TeachableMachine()
        assertEquals(224, cfg.inputWidth)
        assertEquals(224, cfg.inputHeight)
    }

    @Test fun `SsdMobileNet has 320 input size`() {
        val cfg = ModelConfig.SsdMobileNet()
        assertEquals(320, cfg.inputWidth)
        assertEquals(320, cfg.inputHeight)
    }

    @Test fun `YoloV8 has 640 input size`() {
        val cfg = ModelConfig.YoloV8()
        assertEquals(640, cfg.inputWidth)
        assertEquals(640, cfg.inputHeight)
    }

    @Test fun `Custom config stores values correctly`() {
        val cfg = ModelConfig.Custom(
            modelPath  = "my_model.tflite",
            inputWidth = 416, inputHeight = 416,
            confidenceThreshold = 0.6f
        )
        assertEquals("my_model.tflite", cfg.modelPath)
        assertEquals(416, cfg.inputWidth)
        assertEquals(0.6f, cfg.confidenceThreshold, 0.001f)
    }

    @Test fun `default label path is labels_txt`() {
        assertEquals("labels.txt", ModelConfig.SsdMobileNet().labelPath)
        assertEquals("labels.txt", ModelConfig.TeachableMachine().labelPath)
        assertEquals("labels.txt", ModelConfig.YoloV8().labelPath)
    }

    @Test fun `YoloV8 has iouThreshold field`() {
        val cfg = ModelConfig.YoloV8(iouThreshold = 0.5f)
        assertEquals(0.5f, cfg.iouThreshold, 0.001f)
    }

    @Test fun `confidence thresholds within valid range`() {
        listOf(
            ModelConfig.TeachableMachine(),
            ModelConfig.SsdMobileNet(),
            ModelConfig.YoloV8()
        ).forEach { cfg ->
            assertTrue(cfg.confidenceThreshold in 0f..1f)
        }
    }

    @Test fun `sealed class exhaustive when`() {
        val configs: List<ModelConfig> = listOf(
            ModelConfig.TeachableMachine(),
            ModelConfig.SsdMobileNet(),
            ModelConfig.YoloV8(),
            ModelConfig.Custom("a.tflite", inputWidth = 224, inputHeight = 224)
        )
        configs.forEach { cfg ->
            val type = when (cfg) {
                is ModelConfig.TeachableMachine -> "teachable"
                is ModelConfig.SsdMobileNet     -> "ssd"
                is ModelConfig.YoloV8           -> "yolo"
                is ModelConfig.Custom           -> "custom"
            }
            assertFalse(type.isEmpty())
        }
    }
}
