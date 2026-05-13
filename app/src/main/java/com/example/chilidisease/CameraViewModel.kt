package com.example.chilidisease

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.chilidisease.detector.ChiliDiseaseDetector
import com.example.chilidisease.detector.DetectionResult
import com.example.chilidisease.detector.ModelConfig
import com.example.chilidisease.detector.YoloV8Detector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraViewModel
 *
 * Memisahkan logika inferensi dari UI (MainActivity).
 * Mendukung semua ModelConfig:
 *   TeachableMachine → ChiliDiseaseDetector (mode klasifikasi)
 *   SsdMobileNet     → ChiliDiseaseDetector (mode object detection)
 *   YoloV8           → YoloV8Detector
 *   Custom           → ChiliDiseaseDetector
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    // ── LiveData ──
    private val _detections   = MutableLiveData<List<DetectionResult>>(emptyList())
    private val _isDetecting  = MutableLiveData(true)
    private val _fps          = MutableLiveData(0f)
    private val _errorMessage = MutableLiveData<String?>(null)
    private val _modelReady   = MutableLiveData(false)
    private val _inferenceMs  = MutableLiveData(0L)

    val detections:   LiveData<List<DetectionResult>> = _detections
    val isDetecting:  LiveData<Boolean>               = _isDetecting
    val fps:          LiveData<Float>                  = _fps
    val errorMessage: LiveData<String?>               = _errorMessage
    val modelReady:   LiveData<Boolean>               = _modelReady
    val inferenceMs:  LiveData<Long>                  = _inferenceMs

    // ── Detector (salah satu dipakai) ──
    private var detector:     ChiliDiseaseDetector? = null
    private var yoloDetector: YoloV8Detector?       = null
    private var isYoloMode = false

    // ── Satu Job aktif sekaligus ──
    private var inferenceJob: Job? = null

    // ── FPS tracking ──
    private val frameTimestamps = ArrayDeque<Long>(60)

    // ================================================================
    // INIT MODEL
    // ================================================================

    fun initModel(config: ModelConfig = ModelConfig.MobileNetV4()) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                closeCurrentDetector()

                when (config) {
                    is ModelConfig.MobileNetV4,
                    is ModelConfig.TeachableMachine,
                    is ModelConfig.SsdMobileNet,
                    is ModelConfig.Custom -> {
                        detector = ChiliDiseaseDetector(
                            context     = ctx,
                            modelPath   = config.modelPath,
                            labelPath   = config.labelPath,
                            useGpu      = false,
                            inputWidth  = config.inputWidth,
                            inputHeight = config.inputHeight,
                            confidenceThreshold = config.confidenceThreshold
                        )
                        isYoloMode = false
                    }
                    is ModelConfig.YoloV8 -> {
                        yoloDetector = YoloV8Detector(
                            context             = ctx,
                            modelPath           = config.modelPath,
                            labelPath           = config.labelPath,
                            inputSize           = config.inputWidth,
                            confidenceThreshold = config.confidenceThreshold,
                            iouThreshold        = (config as ModelConfig.YoloV8).iouThreshold
                        )
                        isYoloMode = true
                    }

                }

                withContext(Dispatchers.Main) {
                    _modelReady.value = true
                    Log.d(TAG, "✅ Model siap: ${config::class.simpleName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Gagal init model: ${e.message}")
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Gagal memuat model: ${e.message}"
                    _modelReady.value = false
                }
            }
        }
    }

    fun initModel(
        modelPath: String = "chili_disease_model.tflite",
        labelPath: String = "labels.txt",
        useGpu: Boolean   = false
    ) = initModel(ModelConfig.MobileNetV4(modelPath = modelPath, labelPath = labelPath))

    // ================================================================
    // FRAME PROCESSING
    // ================================================================

    fun submitFrame(bitmap: Bitmap, imageWidth: Int, imageHeight: Int) {
        if (_isDetecting.value != true) return
        if (inferenceJob?.isActive == true) return   // drop frame

        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()

                val results = if (isYoloMode) {
                    yoloDetector?.detect(bitmap) ?: emptyList()
                } else {
                    detector?.detect(bitmap) ?: emptyList()
                }

                val elapsedMs = System.currentTimeMillis() - t0
                trackFps()

                withContext(Dispatchers.Main) {
                    _detections.value  = results
                    _inferenceMs.value = elapsedMs
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inferensi error: ${e.message}")
            }
        }
    }

    // ================================================================
    // FPS
    // ================================================================

    private fun trackFps() {
        val now = System.currentTimeMillis()
        frameTimestamps.addLast(now)
        while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000L) {
            frameTimestamps.removeFirst()
        }
        val fps = frameTimestamps.size.toFloat()
        viewModelScope.launch(Dispatchers.Main) { _fps.value = fps }
    }

    // ================================================================
    // CONTROLS
    // ================================================================

    fun toggleDetection() {
        val current = _isDetecting.value ?: true
        _isDetecting.value = !current
        if (!current) _detections.value = emptyList()
    }

    fun setDetecting(enabled: Boolean) {
        _isDetecting.value = enabled
        if (!enabled) _detections.value = emptyList()
    }

    fun clearError() { _errorMessage.value = null }

    // ================================================================
    // CLEANUP
    // ================================================================

    private fun closeCurrentDetector() {
        detector?.close()
        yoloDetector?.close()
        detector     = null
        yoloDetector = null
    }

    override fun onCleared() {
        super.onCleared()
        closeCurrentDetector()
        Log.d(TAG, "ViewModel cleared")
    }
}
