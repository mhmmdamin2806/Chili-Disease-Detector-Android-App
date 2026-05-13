package com.example.chilidisease.camera

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * CameraController
 *
 * Mengelola interaksi kamera tingkat lanjut:
 *   • Tap-to-Focus  — sentuh layar untuk fokus pada titik tertentu
 *   • Pinch-to-Zoom — cubit/rentang dua jari untuk zoom
 *   • Torch toggle  — nyala/matikan flash LED
 *
 * Cara penggunaan:
 * ```kotlin
 * cameraController = CameraController(
 *     context       = this,
 *     camera        = camera,
 *     previewView   = binding.previewView,
 *     focusRingView = binding.ivFocusRing,
 *     tvZoomLevel   = binding.tvZoomLevel,
 *     scope         = lifecycleScope
 * )
 * cameraController.attachToPreview()
 * ```
 */
class CameraController(
    private val context: Context,
    private var camera: Camera?,
    private val previewView: PreviewView,
    private val focusRingView: ImageView,
    private val tvZoomLevel: TextView,
    private val scope: CoroutineScope
) {
    // ── Zoom state ──
    private var currentZoomRatio = 1f
    private var minZoom = 1f
    private var maxZoom = 8f

    // ── Torch state ──
    private var torchEnabled = false

    // ── Pinch-to-zoom gesture detector ──
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(currentZoomRatio * detector.scaleFactor)
                return true
            }
        }
    )

    // ================================================================
    // SETUP
    // ================================================================

    /**
     * Pasang gesture listener ke PreviewView.
     * Panggil setelah camera berhasil di-bind ke lifecycle.
     */
    fun attachToPreview() {
        // Baca batas zoom dari kamera
        camera?.cameraInfo?.zoomState?.value?.let { state ->
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
            currentZoomRatio = state.zoomRatio
        }

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            // Tap-to-focus saat angkat jari, hanya jika bukan pinch
            if (!scaleDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
            }
            true
        }
    }

    /** Update referensi kamera (setelah rebind) */
    fun updateCamera(newCamera: Camera?) {
        camera = newCamera
        camera?.cameraInfo?.zoomState?.value?.let { s ->
            minZoom = s.minZoomRatio
            maxZoom = s.maxZoomRatio
        }
    }

    /** Lepas listener saat activity destroy */
    fun detach() {
        previewView.setOnTouchListener(null)
        camera = null
    }

    // ================================================================
    // TAP-TO-FOCUS
    // ================================================================

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        showFocusRing(x, y)

        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusRingView.apply {
            translationX = x - width / 2f
            translationY = y - height / 2f
            scaleX = 1.6f
            scaleY = 1.6f
            alpha = 1f
            isVisible = true

            animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(220)
                .withEndAction {
                    scope.launch(Dispatchers.Main) {
                        delay(700)
                        animate().alpha(0f).setDuration(250)
                            .withEndAction { isVisible = false }
                            .start()
                    }
                }
                .start()
        }
    }

    // ================================================================
    // PINCH-TO-ZOOM
    // ================================================================

    private fun setZoom(ratio: Float) {
        currentZoomRatio = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(currentZoomRatio)
        refreshZoomUI()
    }

    fun resetZoom() = setZoom(1f)

    private fun refreshZoomUI() {
        if (currentZoomRatio <= minZoom + 0.05f) {
            tvZoomLevel.isVisible = false
        } else {
            tvZoomLevel.isVisible = true
            tvZoomLevel.text = "%.1fx".format(currentZoomRatio)
        }
    }

    // ================================================================
    // TORCH (FLASH)
    // ================================================================

    /** Toggle torch. Kembalikan status baru (true = menyala). */
    fun toggleTorch(): Boolean {
        val cam = camera ?: return false
        if (!cam.cameraInfo.hasFlashUnit()) return false
        torchEnabled = !torchEnabled
        cam.cameraControl.enableTorch(torchEnabled)
        return torchEnabled
    }

    fun isTorchOn() = torchEnabled

    fun setTorch(on: Boolean) {
        torchEnabled = on
        camera?.cameraControl?.enableTorch(on)
    }
}
