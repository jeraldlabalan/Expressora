package com.example.expressora.recognition.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.utils.LogUtils
import java.io.ByteArrayOutputStream

class CameraBitmapAnalyzer(
    private val onBitmap: (Bitmap) -> Unit,
    private val frameSkip: Int = PerformanceConfig.BASE_FRAME_SKIP
) : ImageAnalysis.Analyzer {
    
    companion object {
        private const val TAG = "CameraBitmapAnalyzer"
        private var loggedOnce = false
    }

    private var yuvBuffer: ByteArray? = null
    private var rgbBuffer: IntArray? = null  // Reusable RGB buffer for direct conversion
    private var frameCounter = 0
    private var adaptiveSkip = frameSkip
    private var framesSinceSkipAdjust = 0
    
    // Bitmap pool for reuse (ARGB_8888 format for direct conversion)
    private val bitmapPool = mutableListOf<Bitmap>()
    private val maxPoolSize = PerformanceConfig.BITMAP_POOL_SIZE

    override fun analyze(image: ImageProxy) {
        try {
            // Adaptive frame skipping based on measured FPS
            frameCounter++
            
            if (PerformanceConfig.ADAPTIVE_SKIP_ENABLED) {
                framesSinceSkipAdjust++
                if (framesSinceSkipAdjust >= PerformanceConfig.ADAPTIVE_SKIP_UPDATE_INTERVAL) {
                    adjustAdaptiveSkip()
                    framesSinceSkipAdjust = 0
                }
                
                if (frameCounter % adaptiveSkip != 0) {
                    return
                }
            } else {
                if (frameCounter % frameSkip != 0) {
                    return
                }
            }
            
            if (!loggedOnce) {
                Log.i(TAG, "📷 Camera analyzer active: " +
                        "resolution=${image.width}x${image.height}, " +
                        "adaptiveSkip=${PerformanceConfig.ADAPTIVE_SKIP_ENABLED}, " +
                        "baseSkip=$frameSkip, " +
                        "directYUV=true")
                loggedOnce = true
            }
            
            // Log frame processing for debugging (every 30 frames to avoid spam)
            if (frameCounter % 30 == 0) {
                Log.d(TAG, "📷 Processing frame #$frameCounter @ ${System.currentTimeMillis()}")
            }
            
            val bitmap = image.toBitmapDirect() ?: return
            val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
            
            // NOTE: We do NOT horizontally flip/mirror the image before sending to MediaPipe.
            // The bitmap orientation matches the actual camera view (not mirrored).
            // MediaPipe's handedness labels are reversed relative to the person's actual hands,
            // so we handle the reversal in HandToFeaturesBridge mapping logic instead.
            // This ensures consistent behavior for both front and rear cameras.
            
            // Optional downscaling (usually disabled with 480x360 input)
            val finalBitmap = if (PerformanceConfig.ENABLE_DOWNSCALING && 
                (rotated.width > PerformanceConfig.DOWNSCALE_WIDTH || 
                 rotated.height > PerformanceConfig.DOWNSCALE_HEIGHT)) {
                downscaleBitmap(rotated)
            } else {
                rotated
            }
            
            // Pass bitmap to callback for processing
            LogUtils.debugIfVerbose(TAG) { "📷 Frame converted to bitmap: ${finalBitmap.width}x${finalBitmap.height}, calling onBitmap callback" }
            onBitmap(finalBitmap)
        } catch (error: Throwable) {
            Log.e(TAG, "❌ Frame analysis error: ${error.message}", error)
        } finally {
            image.close()
        }
    }
    
    /**
     * Adjust adaptive skip based on current FPS.
     * Increases skip when FPS is below target, decreases when above.
     */
    private fun adjustAdaptiveSkip() {
        val currentFps = com.example.expressora.recognition.diagnostics.RecognitionDiagnostics.getCurrentFPS()
        if (currentFps <= 0f) return
        
        val targetFps = PerformanceConfig.TARGET_FPS
        
        when {
            currentFps < targetFps * 0.8f -> {
                // FPS too low, increase skip
                adaptiveSkip = (adaptiveSkip + 1).coerceAtMost(PerformanceConfig.MAX_FRAME_SKIP)
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    Log.d(TAG, "FPS low ($currentFps), increased skip to $adaptiveSkip")
                }
            }
            currentFps > targetFps * 1.2f && adaptiveSkip > PerformanceConfig.MIN_FRAME_SKIP -> {
                // FPS high, can decrease skip for better responsiveness
                adaptiveSkip = (adaptiveSkip - 1).coerceAtLeast(PerformanceConfig.MIN_FRAME_SKIP)
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    Log.d(TAG, "FPS high ($currentFps), decreased skip to $adaptiveSkip")
                }
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }
        
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        
        // Create new bitmap (fast version without filtering)
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        
        // Return original to pool
        returnBitmapToPool(bitmap)
        
        return result
    }
    
    private fun downscaleBitmap(bitmap: Bitmap): Bitmap {
        val targetWidth = PerformanceConfig.DOWNSCALE_WIDTH
        val targetHeight = PerformanceConfig.DOWNSCALE_HEIGHT
        
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        returnBitmapToPool(bitmap)
        return scaled
    }

    /**
     * Direct YUV_420_888 to ARGB_8888 bitmap conversion.
     * Bypasses JPEG compression/decompression for 30-50% performance improvement.
     */
    private fun ImageProxy.toBitmapDirect(): Bitmap? {
        if (format != ImageFormat.YUV_420_888 || planes.isEmpty()) return null

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        // Reuse RGB buffer
        val pixelCount = width * height
        val rgb = if (rgbBuffer?.size == pixelCount) {
            rgbBuffer!!
        } else {
            IntArray(pixelCount).also { rgbBuffer = it }
        }
        
        // Get or create pooled bitmap (ARGB_8888 for full color + alpha)
        val bitmap = getBitmapFromPool(width, height) ?: Bitmap.createBitmap(
            width, height, Bitmap.Config.ARGB_8888
        )
        
        // Convert YUV to RGB directly
        yuv420ToRgb(
            yBuffer, uBuffer, vBuffer,
            rgb, width, height,
            yRowStride, uvRowStride, uvPixelStride
        )
        
        // Set pixels into bitmap
        bitmap.setPixels(rgb, 0, width, 0, 0, width, height)
        
        // Rewind buffers for next use
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()
        
        return bitmap
    }
    
    /**
     * Fast YUV420 to RGB conversion using standard ITU-R BT.601 coefficients.
     * This implementation prioritizes speed over perfect color accuracy.
     */
    private fun yuv420ToRgb(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        rgb: IntArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ) {
        var rgbIndex = 0
        
        for (y in 0 until height) {
            val yRowOffset = y * yRowStride
            val uvRowOffset = (y / 2) * uvRowStride
            
            for (x in 0 until width) {
                val yValue = (yBuffer.get(yRowOffset + x).toInt() and 0xFF)
                val uvIndex = uvRowOffset + (x / 2) * uvPixelStride
                val uValue = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                
                // ITU-R BT.601 conversion (optimized with bit shifts)
                var r = yValue + ((1436 * vValue) shr 10)
                var g = yValue - ((354 * uValue + 732 * vValue) shr 10)
                var b = yValue + ((1814 * uValue) shr 10)
                
                // Clamp to [0, 255]
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                
                // Pack into ARGB format
                rgb[rgbIndex++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
    }
    
    /**
     * Get a bitmap from the pool if available and matching dimensions.
     */
    private fun getBitmapFromPool(width: Int, height: Int): Bitmap? {
        synchronized(bitmapPool) {
            val bitmap = bitmapPool.firstOrNull { 
                !it.isRecycled && it.width == width && it.height == height &&
                it.config == Bitmap.Config.ARGB_8888
            }
            if (bitmap != null) {
                bitmapPool.remove(bitmap)
            }
            return bitmap
        }
    }
    
    /**
     * Return a bitmap to the pool for reuse.
     */
    private fun returnBitmapToPool(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(bitmapPool) {
            if (bitmapPool.size < maxPoolSize) {
                bitmapPool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }
}

