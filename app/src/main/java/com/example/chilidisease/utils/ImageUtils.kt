package com.example.chilidisease.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ImageUtils
 *
 * Utilitas konversi gambar dari format YUV_420_888 (output CameraX)
 * ke Bitmap (format yang dibutuhkan model TFLite).
 *
 * Format YUV_420_888:
 * - Y plane: luminance (kecerahan), ukuran = width × height
 * - U plane: Cb chrominance (biru-perbedaan), ukuran = (width/2) × (height/2)
 * - V plane: Cr chrominance (merah-perbedaan), ukuran = (width/2) × (height/2)
 */
object ImageUtils {

    /**
     * Konversi ImageProxy (YUV_420_888) dari CameraX ke Bitmap RGB
     *
     * Ini adalah metode utama yang dipanggil dari ImageAnalysis.Analyzer.
     * Otomatis menangani rotasi gambar sesuai orientasi kamera.
     *
     * @param imageProxy ImageProxy dari CameraX
     * @return Bitmap dalam format ARGB_8888 yang sudah dirotasi dengan benar
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = yuvImageProxyToNv21Bitmap(imageProxy)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        return rotateBitmap(bitmap, rotationDegrees.toFloat())
    }

    /**
     * Konversi YUV ImageProxy → NV21 ByteArray → Bitmap
     *
     * Menggunakan YuvImage Android standard untuk konversi yang efisien.
     * Format NV21: Y plane diikuti V dan U interleaved.
     */
    private fun yuvImageProxyToNv21Bitmap(imageProxy: ImageProxy): Bitmap {
        val yuvBytes = yuv420ToNv21(imageProxy)
        val yuvImage = YuvImage(
            yuvBytes,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        return nv21ToBitmap(yuvImage, imageProxy.width, imageProxy.height)
    }

    /**
     * Konversi format YUV_420_888 ke NV21 (byte array)
     *
     * Format YUV_420_888 memiliki 3 plane terpisah yang mungkin
     * tidak bersebelahan di memori (non-contiguous). Fungsi ini
     * menggabungkannya ke format NV21 yang bersebelahan.
     */
    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Buffer NV21: Y plane + V/U interleaved
        val nv21 = ByteArray(ySize + uSize + vSize)

        // Salin Y plane langsung
        yBuffer.get(nv21, 0, ySize)

        // Cek apakah U dan V planes sudah dalam format NV21
        // (bersebelahan dengan pixel stride = 2)
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        if (vPixelStride == 2 && uPixelStride == 2) {
            // Format sudah NV21 atau bisa dioptimasi
            vBuffer.get(nv21, ySize, vSize)
        } else {
            // Format berbeda, perlu interleave manual
            interleaveUVPlanes(nv21, ySize, uBuffer, vBuffer, uPlane.pixelStride, width, height)
        }

        return nv21
    }

    /**
     * Interleave U dan V planes secara manual untuk format non-standard
     *
     * Format NV21 memerlukan data V dan U bergantian: VUVUVU...
     */
    private fun interleaveUVPlanes(
        output: ByteArray,
        offset: Int,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        pixelStride: Int,
        width: Int,
        height: Int
    ) {
        val uvWidth = width / 2
        val uvHeight = height / 2

        var outputIdx = offset
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val bufferIdx = row * (uvWidth * pixelStride) + col * pixelStride
                // NV21: V dulu, baru U
                output[outputIdx++] = vBuffer[bufferIdx]
                output[outputIdx++] = uBuffer[bufferIdx]
            }
        }
    }

    /**
     * Konversi NV21 YuvImage ke Bitmap menggunakan JPEG compression
     *
     * Ini adalah cara Android standar mengkonversi YUV ke Bitmap.
     * Kualitas JPEG 90 memberikan keseimbangan antara kecepatan dan kualitas.
     */
    private fun nv21ToBitmap(yuvImage: YuvImage, width: Int, height: Int): Bitmap {
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            90, // Kualitas JPEG (90 = bagus, cepat)
            outputStream
        )
        val jpegBytes = outputStream.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * Alternatif: Konversi langsung YUV_420_888 ke Bitmap tanpa JPEG
     *
     * Metode ini lebih cepat tapi membutuhkan rendering manual pixel per pixel.
     * Digunakan sebagai fallback jika metode NV21 gagal.
     */
    fun yuv420ToBitmapDirect(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val yRowStride = imageProxy.planes[0].rowStride
        val uvRowStride = imageProxy.planes[1].rowStride
        val uvPixelStride = imageProxy.planes[1].pixelStride

        val argbArray = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Ambil nilai Y (luminance)
                val yValue = (yBuffer[y * yRowStride + x].toInt() and 0xFF)

                // Koordinat UV (subsampled 2x)
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvRowStride + uvX * uvPixelStride

                // Ambil nilai U dan V (chrominance)
                val uValue = (uBuffer[uvIndex].toInt() and 0xFF) - 128
                val vValue = (vBuffer[uvIndex].toInt() and 0xFF) - 128

                // Konversi YUV ke RGB menggunakan formula ITU-R BT.601
                val r = (yValue + 1.402f * vValue).toInt().coerceIn(0, 255)
                val g = (yValue - 0.344136f * uValue - 0.714136f * vValue).toInt().coerceIn(0, 255)
                val b = (yValue + 1.772f * uValue).toInt().coerceIn(0, 255)

                argbArray[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(argbArray, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Rotasi bitmap sesuai derajat rotasi kamera
     *
     * CameraX sering memberikan gambar yang perlu dirotasi
     * tergantung orientasi perangkat dan kamera (depan/belakang).
     *
     * @param bitmap Bitmap sumber
     * @param degrees Derajat rotasi (0, 90, 180, atau 270)
     * @return Bitmap yang sudah dirotasi
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Flip bitmap secara horizontal (untuk kamera depan/selfie)
     *
     * @param bitmap Bitmap sumber
     * @return Bitmap yang sudah di-flip horizontal
     */
    fun flipBitmapHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Crop dan resize bitmap ke ukuran yang dibutuhkan model
     *
     * @param bitmap Bitmap sumber
     * @param targetWidth Lebar target
     * @param targetHeight Tinggi target
     * @param maintainAspectRatio Pertahankan rasio aspek dengan center crop
     * @return Bitmap yang sudah di-crop dan di-resize
     */
    fun cropAndResizeBitmap(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        maintainAspectRatio: Boolean = true
    ): Bitmap {
        return if (maintainAspectRatio) {
            centerCropBitmap(bitmap, targetWidth, targetHeight)
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }

    /**
     * Center crop bitmap untuk mempertahankan rasio aspek
     */
    private fun centerCropBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcWidth = bitmap.width.toFloat()
        val srcHeight = bitmap.height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val srcRatio = srcWidth / srcHeight

        val cropWidth: Int
        val cropHeight: Int

        if (srcRatio > targetRatio) {
            cropHeight = bitmap.height
            cropWidth = (bitmap.height * targetRatio).toInt()
        } else {
            cropWidth = bitmap.width
            cropHeight = (bitmap.width / targetRatio).toInt()
        }

        val startX = (bitmap.width - cropWidth) / 2
        val startY = (bitmap.height - cropHeight) / 2

        val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropWidth, cropHeight)
        return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
    }

    /**
     * Konversi android.media.Image (dari Camera2 API) ke Bitmap
     * Berguna untuk kompatibilitas dengan Camera2 API
     */
    fun mediaImageToBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, outputStream)
        val jpegBytes = outputStream.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}
