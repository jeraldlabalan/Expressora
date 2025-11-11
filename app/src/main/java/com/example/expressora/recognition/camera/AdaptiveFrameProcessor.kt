package com.example.expressora.recognition.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import kotlin.math.abs

/**
 * Adaptive frame processor that intelligently skips frames based on:
 * - Motion detection (skip frames when hands are still)
 * - FPS performance (adjust skip rate dynamically)
 * - Confidence levels (process more when confidence is low)
 */
class AdaptiveFrameProcessor {
    companion object {
        private const val TAG = "AdaptiveFrameProcessor"
        private const val FPS_SAMPLE_SIZE = 10
    }
    
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fpsSum = 0f
    private var currentSkipRate = PerformanceConfig.BASE_FRAME_SKIP
    
    // Motion detection
    private var lastBrightness: Float? = null
    private var stillFrameCount = 0
    
    // FPS tracking
    private val fpsHistory = mutableListOf<Float>()
    
    /**
     * Determines if a frame should be processed.
     * @return true if frame should be processed, false to skip
     */
    fun shouldProcessFrame(
        bitmap: Bitmap,
        lastConfidence: Float = 0f
    ): Boolean {
        frameCount++
        
        // Always process first frame
        if (frameCount == 1) {
            updateMotionState(bitmap)
            return true
        }
        
        // Skip based on current skip rate
        if (frameCount % currentSkipRate != 0) {
            return false
        }
        
        // Motion detection: skip if hands are still
        if (PerformanceConfig.ENABLE_MOTION_DETECTION) {
            val motion = detectMotion(bitmap)
            if (!motion) {
                stillFrameCount++
                if (stillFrameCount >= PerformanceConfig.SKIP_FRAMES_WHEN_STILL) {
                    return false
                }
            } else {
                stillFrameCount = 0
            }
        }
        
        // Confidence-based adjustment: process more frames when confidence is low
        if (lastConfidence in 0.1f..0.6f && frameCount % 2 == 0) {
            return true
        }
        
        updateMotionState(bitmap)
        return true
    }
    
    /**
     * Updates FPS metrics and adjusts skip rate dynamically
     */
    fun updateFPS() {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameTime > 0) {
            val delta = now - lastFrameTime
            if (delta > 0) {
                val fps = 1000f / delta
                fpsHistory.add(fps)
                if (fpsHistory.size > FPS_SAMPLE_SIZE) {
                    fpsHistory.removeAt(0)
                }
                
                // Adjust skip rate based on average FPS
                adjustSkipRate()
            }
        }
        lastFrameTime = now
    }
    
    private fun adjustSkipRate() {
        if (!PerformanceConfig.ADAPTIVE_SKIP_ENABLED || fpsHistory.size < FPS_SAMPLE_SIZE) {
            return
        }
        
        val avgFps = fpsHistory.average().toFloat()
        val targetFps = PerformanceConfig.TARGET_FPS.toFloat()
        
        currentSkipRate = when {
            avgFps < targetFps * 0.5f -> {
                // Very low FPS: skip more frames
                (currentSkipRate + 1).coerceIn(
                    PerformanceConfig.MIN_FRAME_SKIP,
                    PerformanceConfig.MAX_FRAME_SKIP
                )
            }
            avgFps < targetFps * 0.8f -> {
                // Low FPS: slightly increase skip rate
                (currentSkipRate + 1).coerceAtMost(PerformanceConfig.MAX_FRAME_SKIP)
            }
            avgFps > targetFps * 1.2f -> {
                // High FPS: can process more frames
                (currentSkipRate - 1).coerceAtLeast(PerformanceConfig.MIN_FRAME_SKIP)
            }
            else -> {
                // FPS is good, maintain current rate
                currentSkipRate
            }
        }
        
        if (PerformanceConfig.VERBOSE_LOGGING && frameCount % 60 == 0) {
            Log.d(TAG, "Adaptive: avgFPS=%.1f, skipRate=%d, target=%d".format(
                avgFps, currentSkipRate, PerformanceConfig.TARGET_FPS
            ))
        }
    }
    
    private fun detectMotion(bitmap: Bitmap): Boolean {
        val currentBrightness = calculateAverageBrightness(bitmap)
        val lastBright = lastBrightness
        
        return if (lastBright != null) {
            val diff = abs(currentBrightness - lastBright)
            diff > PerformanceConfig.MOTION_THRESHOLD
        } else {
            true // Assume motion on first check
        }
    }
    
    private fun updateMotionState(bitmap: Bitmap) {
        lastBrightness = calculateAverageBrightness(bitmap)
    }
    
    /**
     * Calculate average brightness as a proxy for motion detection.
     * More sophisticated motion detection could use optical flow.
     */
    private fun calculateAverageBrightness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val sampleSize = 10 // Sample every 10th pixel for speed
        
        var totalBrightness = 0f
        var pixelCount = 0
        
        for (y in 0 until height step sampleSize) {
            for (x in 0 until width step sampleSize) {
                val pixel = bitmap.getPixel(x, y)
                // Calculate perceived brightness
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalBrightness += (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                pixelCount++
            }
        }
        
        return if (pixelCount > 0) totalBrightness / pixelCount else 0f
    }
    
    /**
     * Get current skip rate for monitoring
     */
    fun getCurrentSkipRate(): Int = currentSkipRate
    
    /**
     * Get average FPS for monitoring
     */
    fun getAverageFPS(): Float = if (fpsHistory.isNotEmpty()) {
        fpsHistory.average().toFloat()
    } else {
        0f
    }
    
    /**
     * Reset processor state
     */
    fun reset() {
        frameCount = 0
        lastFrameTime = 0L
        lastBrightness = null
        stillFrameCount = 0
        fpsHistory.clear()
        currentSkipRate = PerformanceConfig.BASE_FRAME_SKIP
    }
}

