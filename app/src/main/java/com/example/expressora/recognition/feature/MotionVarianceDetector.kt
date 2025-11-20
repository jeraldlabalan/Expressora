package com.example.expressora.recognition.feature

import android.util.Log

/**
 * Detects motion variance in hand landmarks to distinguish between static and dynamic signs.
 * 
 * Static signs (e.g., alphabet letters, numbers) have low variance (hand is relatively still).
 * Dynamic signs (e.g., words, actions) have high variance (hand is moving).
 * 
 * This can be used to route recognition to different classifiers:
 * - Low variance → Static classifier (Alphabet/Numbers)
 * - High variance → Dynamic classifier (Words/Actions)
 */
class MotionVarianceDetector(
    private val bufferSize: Int = 10,  // Number of frames to track
    private val varianceThreshold: Float = 0.01f  // Threshold for static vs dynamic (tunable)
) {
    private val TAG = "MotionVarianceDetector"
    
    // Ring buffer for wrist position history
    private val wristHistoryX = FloatArray(bufferSize)
    private val wristHistoryY = FloatArray(bufferSize)
    private var historyIndex = 0
    private var historySize = 0  // Track how many frames we've collected
    
    /**
     * Add a new wrist position to the history buffer.
     * @param wristX Normalized x coordinate (0-1)
     * @param wristY Normalized y coordinate (0-1)
     */
    fun addWristPosition(wristX: Float, wristY: Float) {
        wristHistoryX[historyIndex] = wristX
        wristHistoryY[historyIndex] = wristY
        historyIndex = (historyIndex + 1) % bufferSize
        if (historySize < bufferSize) historySize++
    }
    
    /**
     * Calculate the variance of wrist position over the frame buffer.
     * @return Variance value (Mean Squared Error of position)
     */
    fun calculateVariance(): Float {
        if (historySize < 2) {
            return 0f  // Not enough data
        }
        
        val meanX = wristHistoryX.slice(0 until historySize).average().toFloat()
        val meanY = wristHistoryY.slice(0 until historySize).average().toFloat()
        
        var sumSq = 0.0f
        for (i in 0 until historySize) {
            val diffX = wristHistoryX[i] - meanX
            val diffY = wristHistoryY[i] - meanY
            sumSq += diffX * diffX + diffY * diffY
        }
        
        return sumSq / historySize
    }
    
    /**
     * Determine if the hand is static (low variance) or dynamic (high variance).
     * @return true if static (low variance), false if dynamic (high variance)
     */
    fun isStatic(): Boolean {
        val variance = calculateVariance()
        val isStatic = variance < varianceThreshold
        Log.v(TAG, "Motion detection: variance=$variance, threshold=$varianceThreshold, isStatic=$isStatic")
        return isStatic
    }
    
    /**
     * Get the current variance value.
     * @return Variance value, or 0f if insufficient data
     */
    fun getVariance(): Float {
        return calculateVariance()
    }
    
    /**
     * Reset the history buffer.
     */
    fun reset() {
        historyIndex = 0
        historySize = 0
        Log.d(TAG, "Motion variance detector reset")
    }
    
    /**
     * Check if enough data has been collected for reliable variance calculation.
     * @return true if buffer has at least 5 frames
     */
    fun hasEnoughData(): Boolean {
        return historySize >= 5
    }
}

