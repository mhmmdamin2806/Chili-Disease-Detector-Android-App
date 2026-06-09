package com.example.chilidisease.detector

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

object ChiliPresenceGate {

    private const val TARGET_SIZE = 160

    /*
     * Dibuat ketat untuk realtime camera.
     * Tujuannya menolak background emulator, lantai, tembok, meja, kursi, dan objek coklat/oranye.
     */
    private const val MIN_AREA_RATIO = 0.0035f
    private const val MAX_AREA_RATIO = 0.10f

    private const val MIN_ASPECT_RATIO = 1.35f
    private const val MAX_ASPECT_RATIO = 9.0f

    private const val MIN_FILL_RATIO = 0.08f
    private const val MAX_FILL_RATIO = 0.82f

    private const val MAX_WIDTH_RATIO = 0.45f
    private const val MAX_HEIGHT_RATIO = 0.45f

    fun hasChiliObject(bitmap: Bitmap): Boolean {
        val scaled = scaleBitmap(bitmap)

        val width = scaled.width
        val height = scaled.height
        val totalPixels = width * height

        val mask = BooleanArray(totalPixels)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = scaled.getPixel(x, y)
                mask[y * width + x] = isChiliLikeColor(pixel)
            }
        }

        val visited = BooleanArray(totalPixels)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x

                if (!mask[index] || visited[index]) continue

                val component = floodFill(
                    startX = x,
                    startY = y,
                    width = width,
                    height = height,
                    mask = mask,
                    visited = visited
                )

                if (isValidComponent(component, totalPixels, width, height)) {
                    return true
                }
            }
        }

        return false
    }

    private fun isChiliLikeColor(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        /*
         * Reject background pucat, putih, abu, coklat muda, dan area gelap.
         */
        if (value < 0.16f) return false
        if (saturation < 0.52f) return false

        /*
         * Untuk realtime, warna orange/coklat sengaja tidak dimasukkan.
         * Karena background emulator/lantai/meja sering orange-coklat.
         */
        val isRedChili =
            (hue <= 14f || hue >= 346f) &&
                    saturation >= 0.58f &&
                    value in 0.18f..0.95f

        val isGreenChili =
            hue in 74f..145f &&
                    saturation >= 0.50f &&
                    value in 0.18f..0.92f

        return isRedChili || isGreenChili
    }

    private fun floodFill(
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        mask: BooleanArray,
        visited: BooleanArray
    ): Component {
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(startX to startY)

        visited[startY * width + startX] = true

        var area = 0
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val x = current.first
            val y = current.second

            area++

            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)

            val neighbors = arrayOf(
                x + 1 to y,
                x - 1 to y,
                x to y + 1,
                x to y - 1
            )

            for ((nx, ny) in neighbors) {
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue

                val idx = ny * width + nx

                if (!visited[idx] && mask[idx]) {
                    visited[idx] = true
                    queue.add(nx to ny)
                }
            }
        }

        return Component(
            area = area,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY
        )
    }

    private fun isValidComponent(
        component: Component,
        totalPixels: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {
        val areaRatio = component.area.toFloat() / totalPixels.toFloat()

        if (areaRatio < MIN_AREA_RATIO) return false
        if (areaRatio > MAX_AREA_RATIO) return false

        val boxWidth = component.maxX - component.minX + 1
        val boxHeight = component.maxY - component.minY + 1

        val widthRatio = boxWidth.toFloat() / imageWidth.toFloat()
        val heightRatio = boxHeight.toFloat() / imageHeight.toFloat()

        if (widthRatio > MAX_WIDTH_RATIO) return false
        if (heightRatio > MAX_HEIGHT_RATIO) return false

        val longSide = max(boxWidth, boxHeight).toFloat()
        val shortSide = min(boxWidth, boxHeight).toFloat().coerceAtLeast(1f)

        val aspectRatio = longSide / shortSide

        if (aspectRatio < MIN_ASPECT_RATIO) return false
        if (aspectRatio > MAX_ASPECT_RATIO) return false

        val boundingArea = boxWidth * boxHeight
        val fillRatio = component.area.toFloat() / boundingArea.toFloat()

        if (fillRatio < MIN_FILL_RATIO) return false
        if (fillRatio > MAX_FILL_RATIO) return false

        val touchesHorizontalEdges =
            component.minX <= 1 && component.maxX >= imageWidth - 2

        val touchesVerticalEdges =
            component.minY <= 1 && component.maxY >= imageHeight - 2

        if (touchesHorizontalEdges || touchesVerticalEdges) return false

        return true
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()

        val targetWidth: Int
        val targetHeight: Int

        if (bitmap.width >= bitmap.height) {
            targetWidth = TARGET_SIZE
            targetHeight = (TARGET_SIZE / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = TARGET_SIZE
            targetWidth = (TARGET_SIZE * ratio).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private data class Component(
        val area: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )
}