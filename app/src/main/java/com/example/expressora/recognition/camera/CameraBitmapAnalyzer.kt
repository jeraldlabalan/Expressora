package com.example.expressora.recognition.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

class CameraBitmapAnalyzer(
    private val onBitmap: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    private var yuvBuffer: ByteArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap() ?: return
            val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
            onBitmap(rotated)
        } catch (error: Throwable) {
            // Swallow errors to keep analyzer alive; upstream hooks log failures.
        } finally {
            image.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { bitmap.recycle() }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        if (format != ImageFormat.YUV_420_888 || planes.isEmpty()) return null

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val totalSize = ySize + uSize + vSize

        val nv21 = if (yuvBuffer?.size == totalSize) {
            yuvBuffer!!
        } else {
            ByteArray(totalSize).also { yuvBuffer = it }
        }

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        yBuffer.rewind()
        vBuffer.rewind()
        uBuffer.rewind()

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)
        val jpegBytes = outputStream.toByteArray()
        outputStream.close()

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}

