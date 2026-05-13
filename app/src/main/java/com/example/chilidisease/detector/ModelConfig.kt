package com.example.chilidisease.detector

/**
 * ModelConfig — Konfigurasi model TFLite.
 *
 * Pilih sesuai model yang Anda gunakan:
 *
 *   MobileNetV4     → input 224×224, output [1, 3]  (klasifikasi)
 *   TeachableMachine→ input 224×224, output [1, 3]  (klasifikasi)
 *   SsdMobileNet    → input 320×320, output 4 tensor (object detection)
 *   YoloV8          → input 640×640, output [1,7,8400]
 *   Custom          → atur sendiri
 */
sealed class ModelConfig {

    abstract val modelPath: String
    abstract val labelPath: String
    abstract val inputWidth: Int
    abstract val inputHeight: Int
    abstract val confidenceThreshold: Float

    /** MobileNet V4 (224×224) — REKOMENDASI untuk dataset kecil */
    data class MobileNetV4(
        override val modelPath:           String = "chili_disease_model.tflite",
        override val labelPath:           String = "labels.txt",
        override val inputWidth:          Int    = 224,
        override val inputHeight:         Int    = 224,
        override val confidenceThreshold: Float  = 0.50f
    ) : ModelConfig()

    /** Google Teachable Machine (224×224) */
    data class TeachableMachine(
        override val modelPath:           String = "chili_disease_model.tflite",
        override val labelPath:           String = "labels.txt",
        override val inputWidth:          Int    = 224,
        override val inputHeight:         Int    = 224,
        override val confidenceThreshold: Float  = 0.55f
    ) : ModelConfig()

    /** SSD MobileNet V2 / EfficientDet-Lite (320×320, Object Detection) */
    data class SsdMobileNet(
        override val modelPath:           String = "chili_disease_model.tflite",
        override val labelPath:           String = "labels.txt",
        override val inputWidth:          Int    = 320,
        override val inputHeight:         Int    = 320,
        override val confidenceThreshold: Float  = 0.45f
    ) : ModelConfig()

    /** YOLOv8 (640×640) — butuh YoloV8Detector */
    data class YoloV8(
        override val modelPath:           String = "chili_disease_model.tflite",
        override val labelPath:           String = "labels.txt",
        override val inputWidth:          Int    = 640,
        override val inputHeight:         Int    = 640,
        override val confidenceThreshold: Float  = 0.45f,
        val iouThreshold:                 Float  = 0.45f
    ) : ModelConfig()

    /** Konfigurasi kustom */
    data class Custom(
        override val modelPath:           String,
        override val labelPath:           String = "labels.txt",
        override val inputWidth:          Int,
        override val inputHeight:         Int,
        override val confidenceThreshold: Float = 0.45f
    ) : ModelConfig()
}
