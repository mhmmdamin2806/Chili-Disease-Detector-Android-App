package com.example.chilidisease.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.example.chilidisease.SettingsActivity.PrefsKey
import com.example.chilidisease.detector.ModelConfig

/**
 * AppPreferences
 *
 * Lapisan akses terpusat untuk SharedPreferences aplikasi.
 * Membaca nilai dari SettingsActivity dan memberikan typed API.
 *
 * Contoh penggunaan di MainActivity/CameraViewModel:
 * ```kotlin
 * val prefs = AppPreferences(context)
 * val config = prefs.buildModelConfig()
 * viewModel.initModel(config)
 * ```
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    // ================================================================
    // MODEL
    // ================================================================

    val modelType: String
        get() = prefs.getString(PrefsKey.MODEL_TYPE, "mobilenetv4")
            ?: "mobilenetv4"
    val confidenceThreshold: Float
        get() = prefs.getInt(PrefsKey.CONFIDENCE_THRESHOLD, 45) / 100f

    val useGpu: Boolean
        get() = prefs.getBoolean(PrefsKey.USE_GPU, false)

    /**
     * Buat ModelConfig berdasarkan pengaturan saat ini.
     */
    fun buildModelConfig(): ModelConfig = when (modelType) {
        "mobilenetv4" -> ModelConfig.MobileNetV4(
            confidenceThreshold = confidenceThreshold
        )
        "teachable" -> ModelConfig.TeachableMachine(
            confidenceThreshold = confidenceThreshold
        )
        "yolov8" -> ModelConfig.YoloV8(
            confidenceThreshold = confidenceThreshold
        )
        "ssd" -> ModelConfig.SsdMobileNet(
            confidenceThreshold = confidenceThreshold
        )
        else -> ModelConfig.MobileNetV4( // default
            confidenceThreshold = confidenceThreshold
        )
    }

    // ================================================================
    // KAMERA
    // ================================================================

    val inferenceIntervalMs: Long
        get() = prefs.getInt(PrefsKey.INFERENCE_INTERVAL, 80).toLong()

    // ================================================================
    // FITUR
    // ================================================================

    val notificationsEnabled: Boolean
        get() = prefs.getBoolean(PrefsKey.ENABLE_NOTIFICATIONS, true)

    val saveHistoryEnabled: Boolean
        get() = prefs.getBoolean(PrefsKey.SAVE_HISTORY, true)

    // ================================================================
    // LISTENER
    // ================================================================

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
