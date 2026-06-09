package com.example.chilidisease.detector

import android.graphics.RectF

data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float,
    val classIndex: Int,
    val description: String = DiseaseInfo.description(label),
    val allProbabilities: List<ClassProbability> = emptyList(),
    val hasChiliObject: Boolean = true
) {
    val confidencePercent: String
        get() = "${String.format("%.1f", confidence * 100f)}%"

    val diseaseDescription: String
        get() = description
}