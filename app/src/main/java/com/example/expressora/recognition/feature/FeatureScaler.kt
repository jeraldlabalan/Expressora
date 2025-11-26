package com.example.expressora.recognition.feature

import android.content.Context
import android.util.Log

/**
 * Robust Feature Scaler.
 * Eliminates dependency on .npy files.
 * Maps Valid Data [0.0, 1.0] -> [-1.0, 1.0].
 * Maps Missing Data (entire sections) -> [-10.0] (Sentinel).
 * 
 * CRITICAL: Only marks sections as missing if the ENTIRE section is zeros.
 * Valid coordinates that happen to be 0.0 are scaled normally.
 * This matches the training pipeline fix where 0.0 can be a valid coordinate.
 * 
 * IMPORTANT: Sentinel values (-10.0) are used for buffer quality checks and diagnostics,
 * but they are REPLACED with 0.0 before model inference to match training data format.
 * Training data used fillna(0) for missing values, so the model expects 0.0, not -10.0.
 * The replacement happens in TfLiteInterpreter.runMultiOutputSequence() before inference.
 */
class FeatureScaler private constructor() {

    companion object {
        private const val TAG = "FeatureScaler"
        
        // Section boundaries (matches training pipeline)
        private const val LEFT_HAND_START = 0
        private const val LEFT_HAND_END = 63  // 21 landmarks × 3 coords
        private const val RIGHT_HAND_START = 63
        private const val RIGHT_HAND_END = 126  // 21 landmarks × 3 coords
        private const val FACE_START = 126
        private const val FACE_END = 237  // 37 landmarks × 3 coords

        fun create(context: Context): FeatureScaler {
            Log.i(TAG, "✅ FeatureScaler initialized (Section-Based Sentinel Mode)")
            return FeatureScaler()
        }
    }

    fun scale(features: FloatArray): FloatArray {
        val scaled = FloatArray(features.size)
        
        // Check which sections are missing (entire section is all zeros)
        val leftHandMissing = isSectionAllZeros(features, LEFT_HAND_START, LEFT_HAND_END)
        val rightHandMissing = isSectionAllZeros(features, RIGHT_HAND_START, RIGHT_HAND_END)
        val faceMissing = isSectionAllZeros(features, FACE_START, FACE_END)
        
        // Calculate input statistics
        var inputZeroCount = 0
        var inputNonZeroCount = 0
        var inputMin = Float.MAX_VALUE
        var inputMax = Float.MIN_VALUE
        var inputSum = 0f
        
        // Section-by-section input statistics
        var leftHandInputMin = Float.MAX_VALUE
        var leftHandInputMax = Float.MIN_VALUE
        var leftHandInputSum = 0f
        var leftHandInputCount = 0
        
        var rightHandInputMin = Float.MAX_VALUE
        var rightHandInputMax = Float.MIN_VALUE
        var rightHandInputSum = 0f
        var rightHandInputCount = 0
        
        var faceInputMin = Float.MAX_VALUE
        var faceInputMax = Float.MIN_VALUE
        var faceInputSum = 0f
        var faceInputCount = 0
        
        for (i in features.indices) {
            val value = features[i]
            if (value == 0f) {
                inputZeroCount++
            } else {
                inputNonZeroCount++
            }
            if (value < inputMin) inputMin = value
            if (value > inputMax) inputMax = value
            inputSum += value
            
            // Section-specific statistics
            when {
                i < RIGHT_HAND_START -> {
                    if (value < leftHandInputMin) leftHandInputMin = value
                    if (value > leftHandInputMax) leftHandInputMax = value
                    leftHandInputSum += value
                    leftHandInputCount++
                }
                i < FACE_START -> {
                    if (value < rightHandInputMin) rightHandInputMin = value
                    if (value > rightHandInputMax) rightHandInputMax = value
                    rightHandInputSum += value
                    rightHandInputCount++
                }
                else -> {
                    if (value < faceInputMin) faceInputMin = value
                    if (value > faceInputMax) faceInputMax = value
                    faceInputSum += value
                    faceInputCount++
                }
            }
        }
        val inputMean = inputSum / features.size
        val inputStd = kotlin.math.sqrt(features.map { (it - inputMean) * (it - inputMean) }.average()).toFloat()
        
        // Scale each feature based on section missing status
        var sentinelCount = 0
        var scaledCount = 0
        var validZeroCount = 0  // Valid 0.0 coordinates that are scaled (not sentinel)
        var scaledMin = Float.MAX_VALUE
        var scaledMax = Float.MIN_VALUE
        var scaledSum = 0f
        
        // Section-by-section output statistics
        var leftHandScaledMin = Float.MAX_VALUE
        var leftHandScaledMax = Float.MIN_VALUE
        var leftHandScaledSum = 0f
        var leftHandScaledCount = 0
        
        var rightHandScaledMin = Float.MAX_VALUE
        var rightHandScaledMax = Float.MIN_VALUE
        var rightHandScaledSum = 0f
        var rightHandScaledCount = 0
        
        var faceScaledMin = Float.MAX_VALUE
        var faceScaledMax = Float.MIN_VALUE
        var faceScaledSum = 0f
        var faceScaledCount = 0
        
        // Sample transformations (first 10 values)
        val sampleTransformations = mutableListOf<String>()
        
        for (i in features.indices) {
            val value = features[i]
            val isInMissingSection = when {
                i < RIGHT_HAND_START -> leftHandMissing
                i < FACE_START -> rightHandMissing
                else -> faceMissing
            }
            
            if (isInMissingSection) {
                scaled[i] = -10.0f  // Missing section → sentinel value
                sentinelCount++
            } else {
                // Valid data: Scale to [-1, 1] (even if value is 0.0)
                val scaledValue = (value - 0.5f) * 2.0f
                scaled[i] = scaledValue
                scaledCount++
                if (value == 0f) {
                    validZeroCount++  // This is a valid 0.0 coordinate being scaled
                }
                if (scaledValue < scaledMin) scaledMin = scaledValue
                if (scaledValue > scaledMax) scaledMax = scaledValue
                scaledSum += scaledValue
                
                // Section-specific output statistics
                when {
                    i < RIGHT_HAND_START -> {
                        if (scaledValue < leftHandScaledMin) leftHandScaledMin = scaledValue
                        if (scaledValue > leftHandScaledMax) leftHandScaledMax = scaledValue
                        leftHandScaledSum += scaledValue
                        leftHandScaledCount++
                    }
                    i < FACE_START -> {
                        if (scaledValue < rightHandScaledMin) rightHandScaledMin = scaledValue
                        if (scaledValue > rightHandScaledMax) rightHandScaledMax = scaledValue
                        rightHandScaledSum += scaledValue
                        rightHandScaledCount++
                    }
                    else -> {
                        if (scaledValue < faceScaledMin) faceScaledMin = scaledValue
                        if (scaledValue > faceScaledMax) faceScaledMax = scaledValue
                        faceScaledSum += scaledValue
                        faceScaledCount++
                    }
                }
                
                // Collect sample transformations (first 10)
                if (sampleTransformations.size < 10) {
                    sampleTransformations.add("idx[$i]: %.3f -> %.3f (formula: (%.3f - 0.5) * 2.0)".format(value, scaledValue, value))
                }
            }
        }
        val scaledMean = if (scaledCount > 0) scaledSum / scaledCount else 0f
        val scaledStd = if (scaledCount > 0) {
            kotlin.math.sqrt(scaled.filter { it != -10.0f }.map { (it - scaledMean) * (it - scaledMean) }.average()).toFloat()
        } else 0f
        
        // ALWAYS log diagnostics (using Log.e for visibility)
        val missingSections = mutableListOf<String>()
        if (leftHandMissing) missingSections.add("Left Hand")
        if (rightHandMissing) missingSections.add("Right Hand")
        if (faceMissing) missingSections.add("Face")
        
        Log.e(TAG, "🔍 FEATURE SCALER DIAGNOSTICS:")
        Log.e(TAG, "   📥 INPUT STATISTICS:")
        Log.e(TAG, "      Overall: range=[%.3f, %.3f], mean=%.3f, std=%.3f, zeros=$inputZeroCount, non-zeros=$inputNonZeroCount".format(inputMin, inputMax, inputMean, inputStd))
        if (!leftHandMissing) {
            val leftHandInputMean = if (leftHandInputCount > 0) leftHandInputSum / leftHandInputCount else 0f
            Log.e(TAG, "      Left Hand: range=[%.3f, %.3f], mean=%.3f, count=$leftHandInputCount".format(leftHandInputMin, leftHandInputMax, leftHandInputMean))
        }
        if (!rightHandMissing) {
            val rightHandInputMean = if (rightHandInputCount > 0) rightHandInputSum / rightHandInputCount else 0f
            Log.e(TAG, "      Right Hand: range=[%.3f, %.3f], mean=%.3f, count=$rightHandInputCount".format(rightHandInputMin, rightHandInputMax, rightHandInputMean))
        }
        if (!faceMissing) {
            val faceInputMean = if (faceInputCount > 0) faceInputSum / faceInputCount else 0f
            Log.e(TAG, "      Face: range=[%.3f, %.3f], mean=%.3f, count=$faceInputCount".format(faceInputMin, faceInputMax, faceInputMean))
        }
        if (missingSections.isNotEmpty()) {
            Log.e(TAG, "      ❌ Missing sections: ${missingSections.joinToString(", ")}")
        }
        Log.e(TAG, "   📤 OUTPUT STATISTICS:")
        Log.e(TAG, "      Overall: range=[%.3f, %.3f], mean=%.3f, std=%.3f, sentinels=$sentinelCount, scaled=$scaledCount".format(scaledMin, scaledMax, scaledMean, scaledStd))
        if (!leftHandMissing && leftHandScaledCount > 0) {
            val leftHandScaledMean = leftHandScaledSum / leftHandScaledCount
            Log.e(TAG, "      Left Hand: range=[%.3f, %.3f], mean=%.3f, count=$leftHandScaledCount".format(leftHandScaledMin, leftHandScaledMax, leftHandScaledMean))
        }
        if (!rightHandMissing && rightHandScaledCount > 0) {
            val rightHandScaledMean = rightHandScaledSum / rightHandScaledCount
            Log.e(TAG, "      Right Hand: range=[%.3f, %.3f], mean=%.3f, count=$rightHandScaledCount".format(rightHandScaledMin, rightHandScaledMax, rightHandScaledMean))
        }
        if (!faceMissing && faceScaledCount > 0) {
            val faceScaledMean = faceScaledSum / faceScaledCount
            Log.e(TAG, "      Face: range=[%.3f, %.3f], mean=%.3f, count=$faceScaledCount".format(faceScaledMin, faceScaledMax, faceScaledMean))
        }
        if (validZeroCount > 0) {
            Log.e(TAG, "      ✅ Valid 0.0 coordinates scaled normally: $validZeroCount (not treated as missing)")
        }
        Log.e(TAG, "   🔄 SAMPLE TRANSFORMATIONS (first 10):")
        sampleTransformations.forEach { Log.e(TAG, "      $it") }
        Log.e(TAG, "   📊 First 10 input values: ${features.take(10).joinToString { "%.3f".format(it) }}")
        Log.e(TAG, "   📊 First 10 output values: ${scaled.take(10).joinToString { "%.3f".format(it) }}")
        
        return scaled
    }
    
    /**
     * Check if an entire section (hand or face) is all zeros.
     * This indicates the section is missing (landmarks were null/empty).
     * 
     * @param features The feature array
     * @param start Start index (inclusive)
     * @param end End index (exclusive)
     * @return true if entire section is zeros, false otherwise
     */
    private fun isSectionAllZeros(features: FloatArray, start: Int, end: Int): Boolean {
        if (start < 0 || end > features.size || start >= end) {
            return false
        }
        for (i in start until end) {
            if (features[i] != 0f) {
                return false
            }
        }
        return true
    }
}