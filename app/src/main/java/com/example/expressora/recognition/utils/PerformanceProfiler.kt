package com.example.expressora.recognition.utils

import android.os.SystemClock
import android.util.Log
import com.example.expressora.recognition.utils.LogUtils
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Performance profiler for measuring frame processing times, inference latency,
 * and UI thread blocking. Provides real-time metrics and statistics.
 */
object PerformanceProfiler {
    private const val TAG = "PerformanceProfiler"
    private const val MAX_SAMPLES = 1000 // Keep last 1000 samples
    
    // Frame processing metrics
    private val frameTimes = ConcurrentLinkedQueue<Long>()
    private val frameTimeSum = AtomicLong(0)
    private val frameCount = AtomicInteger(0)
    private val maxFrameTime = AtomicLong(0)
    private val minFrameTime = AtomicLong(Long.MAX_VALUE)
    
    // Inference metrics
    private val inferenceTimes = ConcurrentLinkedQueue<Long>()
    private val inferenceTimeSum = AtomicLong(0)
    private val inferenceCount = AtomicInteger(0)
    private val maxInferenceTime = AtomicLong(0)
    private val minInferenceTime = AtomicLong(Long.MAX_VALUE)
    
    // UI thread blocking metrics
    private val uiBlockingTimes = ConcurrentLinkedQueue<Long>()
    private val uiBlockingSum = AtomicLong(0)
    private val uiBlockingCount = AtomicInteger(0)
    private val maxUiBlockingTime = AtomicLong(0)
    
    // Feature extraction metrics
    private val featureExtractionTimes = ConcurrentLinkedQueue<Long>()
    private val featureExtractionSum = AtomicLong(0)
    private val featureExtractionCount = AtomicInteger(0)
    
    // Hand detection metrics
    private val handDetectionTimes = ConcurrentLinkedQueue<Long>()
    private val handDetectionSum = AtomicLong(0)
    private val handDetectionCount = AtomicInteger(0)
    
    /**
     * Record frame processing time (from camera frame to result).
     */
    fun recordFrameTime(timeMs: Long) {
        frameTimes.offer(timeMs)
        frameTimeSum.addAndGet(timeMs)
        frameCount.incrementAndGet()
        
        maxFrameTime.updateAndGet { max(it, timeMs) }
        minFrameTime.updateAndGet { min(it, timeMs) }
        
        // Keep only last MAX_SAMPLES
        while (frameTimes.size > MAX_SAMPLES) {
            val removed = frameTimes.poll()
            if (removed != null) {
                frameTimeSum.addAndGet(-removed)
            }
        }
    }
    
    /**
     * Record inference time (TFLite model execution).
     */
    fun recordInferenceTime(timeMs: Long) {
        inferenceTimes.offer(timeMs)
        inferenceTimeSum.addAndGet(timeMs)
        inferenceCount.incrementAndGet()
        
        maxInferenceTime.updateAndGet { max(it, timeMs) }
        minInferenceTime.updateAndGet { min(it, timeMs) }
        
        while (inferenceTimes.size > MAX_SAMPLES) {
            val removed = inferenceTimes.poll()
            if (removed != null) {
                inferenceTimeSum.addAndGet(-removed)
            }
        }
    }
    
    /**
     * Record UI thread blocking time.
     */
    fun recordUiBlockingTime(timeMs: Long) {
        uiBlockingTimes.offer(timeMs)
        uiBlockingSum.addAndGet(timeMs)
        uiBlockingCount.incrementAndGet()
        
        maxUiBlockingTime.updateAndGet { max(it, timeMs) }
        
        while (uiBlockingTimes.size > MAX_SAMPLES) {
            val removed = uiBlockingTimes.poll()
            if (removed != null) {
                uiBlockingSum.addAndGet(-removed)
            }
        }
    }
    
    /**
     * Record feature extraction time.
     */
    fun recordFeatureExtractionTime(timeMs: Long) {
        featureExtractionTimes.offer(timeMs)
        featureExtractionSum.addAndGet(timeMs)
        featureExtractionCount.incrementAndGet()
        
        while (featureExtractionTimes.size > MAX_SAMPLES) {
            val removed = featureExtractionTimes.poll()
            if (removed != null) {
                featureExtractionSum.addAndGet(-removed)
            }
        }
    }
    
    /**
     * Record hand detection time (MediaPipe).
     */
    fun recordHandDetectionTime(timeMs: Long) {
        handDetectionTimes.offer(timeMs)
        handDetectionSum.addAndGet(timeMs)
        handDetectionCount.incrementAndGet()
        
        while (handDetectionTimes.size > MAX_SAMPLES) {
            val removed = handDetectionTimes.poll()
            if (removed != null) {
                handDetectionSum.addAndGet(-removed)
            }
        }
    }
    
    /**
     * Get current FPS based on frame processing times.
     */
    fun getCurrentFPS(): Float {
        val count = frameCount.get()
        if (count == 0) return 0f
        
        val avgFrameTime = frameTimeSum.get().toFloat() / count
        return if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
    }
    
    /**
     * Get average inference latency in milliseconds.
     */
    fun getAverageInferenceTime(): Float {
        val count = inferenceCount.get()
        return if (count > 0) inferenceTimeSum.get().toFloat() / count else 0f
    }
    
    /**
     * Get average frame processing time in milliseconds.
     */
    fun getAverageFrameTime(): Float {
        val count = frameCount.get()
        return if (count > 0) frameTimeSum.get().toFloat() / count else 0f
    }
    
    /**
     * Get average UI blocking time in milliseconds.
     */
    fun getAverageUiBlockingTime(): Float {
        val count = uiBlockingCount.get()
        return if (count > 0) uiBlockingSum.get().toFloat() / count else 0f
    }
    
    /**
     * Get performance statistics summary.
     */
    data class PerformanceStats(
        val fps: Float,
        val avgFrameTime: Float,
        val maxFrameTime: Long,
        val minFrameTime: Long,
        val avgInferenceTime: Float,
        val maxInferenceTime: Long,
        val minInferenceTime: Long,
        val avgUiBlockingTime: Float,
        val maxUiBlockingTime: Long,
        val avgFeatureExtractionTime: Float,
        val avgHandDetectionTime: Float,
        val frameCount: Int,
        val inferenceCount: Int
    )
    
    fun getStats(): PerformanceStats {
        return PerformanceStats(
            fps = getCurrentFPS(),
            avgFrameTime = getAverageFrameTime(),
            maxFrameTime = maxFrameTime.get(),
            minFrameTime = if (minFrameTime.get() == Long.MAX_VALUE) 0 else minFrameTime.get(),
            avgInferenceTime = getAverageInferenceTime(),
            maxInferenceTime = maxInferenceTime.get(),
            minInferenceTime = if (minInferenceTime.get() == Long.MAX_VALUE) 0 else minInferenceTime.get(),
            avgUiBlockingTime = getAverageUiBlockingTime(),
            maxUiBlockingTime = maxUiBlockingTime.get(),
            avgFeatureExtractionTime = if (featureExtractionCount.get() > 0) 
                featureExtractionSum.get().toFloat() / featureExtractionCount.get() else 0f,
            avgHandDetectionTime = if (handDetectionCount.get() > 0)
                handDetectionSum.get().toFloat() / handDetectionCount.get() else 0f,
            frameCount = frameCount.get(),
            inferenceCount = inferenceCount.get()
        )
    }
    
    /**
     * Log performance statistics.
     */
    fun logStats() {
        val stats = getStats()
        LogUtils.i(TAG) {
            """
            Performance Stats:
            FPS: ${String.format("%.2f", stats.fps)}
            Frame Time: avg=${String.format("%.2f", stats.avgFrameTime)}ms, max=${stats.maxFrameTime}ms, min=${stats.minFrameTime}ms
            Inference: avg=${String.format("%.2f", stats.avgInferenceTime)}ms, max=${stats.maxInferenceTime}ms, min=${stats.minInferenceTime}ms
            UI Blocking: avg=${String.format("%.2f", stats.avgUiBlockingTime)}ms, max=${stats.maxUiBlockingTime}ms
            Feature Extraction: avg=${String.format("%.2f", stats.avgFeatureExtractionTime)}ms
            Hand Detection: avg=${String.format("%.2f", stats.avgHandDetectionTime)}ms
            Frames: ${stats.frameCount}, Inferences: ${stats.inferenceCount}
            """.trimIndent()
        }
    }
    
    /**
     * Reset all metrics.
     */
    fun reset() {
        frameTimes.clear()
        frameTimeSum.set(0)
        frameCount.set(0)
        maxFrameTime.set(0)
        minFrameTime.set(Long.MAX_VALUE)
        
        inferenceTimes.clear()
        inferenceTimeSum.set(0)
        inferenceCount.set(0)
        maxInferenceTime.set(0)
        minInferenceTime.set(Long.MAX_VALUE)
        
        uiBlockingTimes.clear()
        uiBlockingSum.set(0)
        uiBlockingCount.set(0)
        maxUiBlockingTime.set(0)
        
        featureExtractionTimes.clear()
        featureExtractionSum.set(0)
        featureExtractionCount.set(0)
        
        handDetectionTimes.clear()
        handDetectionSum.set(0)
        handDetectionCount.set(0)
    }
    
    /**
     * Measure execution time of a block and record it.
     */
    inline fun <T> measureFrameTime(block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            val time = SystemClock.elapsedRealtime() - start
            recordFrameTime(time)
        }
    }
    
    /**
     * Measure inference time of a block and record it.
     */
    inline fun <T> measureInferenceTime(block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            val time = SystemClock.elapsedRealtime() - start
            recordInferenceTime(time)
        }
    }
    
    /**
     * Measure UI blocking time of a block and record it.
     */
    inline fun <T> measureUiBlockingTime(block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            val time = SystemClock.elapsedRealtime() - start
            recordUiBlockingTime(time)
        }
    }
}

