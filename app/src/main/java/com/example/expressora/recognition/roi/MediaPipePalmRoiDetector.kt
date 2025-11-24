package com.example.expressora.recognition.roi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * ROI detector using MediaPipe HandLandmarker for lightweight hand detection.
 * Uses minimal settings to quickly detect hand bounding box for ROI cropping.
 */
class MediaPipePalmRoiDetector(
    context: Context,
    private val paddingMultiplier: Float = 2.0f,
    private val minConfidence: Float = 0.5f,
    private val detectionCadence: Int = 10,
    private val cacheFrames: Int = 8
) : HandRoiDetector {
    
    companion object {
        private const val TAG = "MediaPipePalmRoiDetector"
        private const val MODEL_ASSET_PATH = "recognition/hand_landmarker.task"
    }

    private var landmarker: HandLandmarker? = null
    private var isInitialized = false
    
    // ROI caching
    private var cachedRoi: Rect? = null
    private var cachedFrameCount = 0
    private var lastDetectionFrame = 0
    private var frameCounter = 0

    init {
        try {
            initialize(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe Palm ROI Detector", e)
        }
    }

    private fun initialize(context: Context) {
        try {
            // Build base options - MediaPipe will automatically select appropriate delegate
            // For ROI detection, we want lightweight processing, but explicit CPU setting
            // is not available in this API version. MediaPipe will optimize automatically.
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()
            
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(1) // Only need one hand for ROI
                .setMinHandDetectionConfidence(minConfidence)
                .setMinHandPresenceConfidence(minConfidence)
                .setMinTrackingConfidence(0.3f) // Lower tracking confidence for ROI
                .setRunningMode(RunningMode.IMAGE) // Use IMAGE mode for synchronous detection
                .build()
            
            landmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "MediaPipe Palm ROI Detector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe Palm ROI Detector", e)
            isInitialized = false
        }
    }

    override fun detectRoi(bitmap: Bitmap): Rect? {
        if (!isAvailable()) {
            return null
        }

        frameCounter++
        
        // Check if we should use cached ROI
        if (cachedRoi != null && cachedFrameCount < cacheFrames && 
            (frameCounter - lastDetectionFrame) < detectionCadence) {
            cachedFrameCount++
            return cachedRoi
        }

        // Run detection
        val shouldDetect = (frameCounter - lastDetectionFrame) >= detectionCadence
        if (!shouldDetect && cachedRoi != null) {
            cachedFrameCount++
            return cachedRoi
        }

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker?.detect(mpImage) ?: return null

            if (result.landmarks().isEmpty()) {
                // No hand detected, clear cache
                cachedRoi = null
                cachedFrameCount = 0
                return null
            }

            // Get first hand landmarks
            val landmarks = result.landmarks().first()
            
            // Calculate bounding box from landmarks
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE

            for (landmark in landmarks) {
                val x = landmark.x() * bitmap.width
                val y = landmark.y() * bitmap.height
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }

            // Add padding
            val width = maxX - minX
            val height = maxY - minY
            val paddingX = width * paddingMultiplier / 2f
            val paddingY = height * paddingMultiplier / 2f

            val roi = Rect(
                (minX - paddingX).toInt().coerceAtLeast(0),
                (minY - paddingY).toInt().coerceAtLeast(0),
                (maxX + paddingX).toInt().coerceAtMost(bitmap.width),
                (maxY + paddingY).toInt().coerceAtMost(bitmap.height)
            )

            // Validate ROI
            if (roi.width() < 50 || roi.height() < 50) {
                cachedRoi = null
                cachedFrameCount = 0
                return null
            }

            // Cache the ROI
            cachedRoi = roi
            cachedFrameCount = 0
            lastDetectionFrame = frameCounter

            return roi
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting ROI", e)
            cachedRoi = null
            cachedFrameCount = 0
            return null
        }
    }

    override fun isAvailable(): Boolean {
        return isInitialized && landmarker != null
    }

    override fun close() {
        try {
            landmarker?.close()
            landmarker = null
            isInitialized = false
            cachedRoi = null
            Log.i(TAG, "MediaPipe Palm ROI Detector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ROI detector", e)
        }
    }

    /**
     * Force re-detection on next call (clears cache).
     */
    fun forceRedetection() {
        cachedRoi = null
        cachedFrameCount = 0
        lastDetectionFrame = 0
    }
}

