package com.example.expressora.recognition.diagnostics

import android.util.Log

/**
 * Diagnostic utility to analyze low confidence issues in recognition model.
 * 
 * Common causes of low confidence:
 * 1. Feature scaling mismatch - model expects different normalization than what's applied
 * 2. Zero-padding issues - missing hands/face features not handled correctly
 * 3. Logit entropy too low - model outputs are too similar (all classes have similar probability)
 * 4. Model input shape mismatch - wrong sequence length or feature dimensions
 * 5. Feature range issues - features outside expected bounds
 */
object ConfidenceDiagnostics {
    private const val TAG = "ConfidenceDiagnostics"
    
    /**
     * Analyze logits to determine why confidence might be low.
     * 
     * @param logits Raw logits from model
     * @param probabilities Softmax probabilities (optional, will calculate if null)
     * @return Diagnostic report
     */
    fun analyzeLowConfidence(
        logits: FloatArray,
        probabilities: FloatArray? = null
    ): ConfidenceReport {
        val probs = probabilities ?: softmax(logits)
        
        val maxLogit = logits.maxOrNull() ?: 0f
        val minLogit = logits.minOrNull() ?: 0f
        val logitRange = maxLogit - minLogit
        val logitMean = logits.average().toFloat()
        val logitStd = calculateStd(logits, logitMean)
        
        val maxProb = probs.maxOrNull() ?: 0f
        val minProb = probs.minOrNull() ?: 0f
        val probRange = maxProb - minProb
        
        // Calculate entropy (measure of uncertainty)
        val entropy = calculateEntropy(probs)
        val maxEntropy = kotlin.math.ln(logits.size.toDouble()).toFloat() // Max entropy for uniform distribution
        
        // Find top 3 predictions
        val top3 = probs.mapIndexed { index, prob -> index to prob }
            .sortedByDescending { it.second }
            .take(3)
        
        // Check for issues
        val issues = mutableListOf<String>()
        
        // Issue 1: Low logit range (model not confident)
        if (logitRange < 2.0f) {
            issues.add("Low logit range: $logitRange (expected >2.0). Model outputs are too similar.")
        }
        
        // Issue 2: High entropy (uncertain predictions)
        if (entropy > maxEntropy * 0.9f) {
            issues.add("High entropy: $entropy (max=$maxEntropy). Model is very uncertain.")
        }
        
        // Issue 3: Low max probability
        if (maxProb < 0.5f) {
            issues.add("Low max probability: $maxProb (${(maxProb * 100).toInt()}%). Top prediction is weak.")
        }
        
        // Issue 4: Top predictions too close
        if (top3.size >= 2) {
            val diff = top3[0].second - top3[1].second
            if (diff < 0.1f) {
                issues.add("Top predictions too close: ${top3[0].second} vs ${top3[1].second} (diff=$diff). Model can't decide.")
            }
        }
        
        // Issue 5: Logits all near zero
        if (kotlin.math.abs(logitMean) < 0.1f && logitStd < 0.5f) {
            issues.add("Logits too small: mean=$logitMean, std=$logitStd. Model may not be processing input correctly.")
        }
        
        return ConfidenceReport(
            maxLogit = maxLogit,
            minLogit = minLogit,
            logitRange = logitRange,
            logitMean = logitMean,
            logitStd = logitStd,
            maxProbability = maxProb,
            minProbability = minProb,
            probabilityRange = probRange,
            entropy = entropy,
            maxEntropy = maxEntropy,
            top3Predictions = top3.map { it.first to it.second },
            issues = issues
        )
    }
    
    /**
     * Analyze feature vector to check for scaling/normalization issues.
     */
    fun analyzeFeatures(features: FloatArray): FeatureReport {
        val mean = features.average().toFloat()
        val std = calculateStd(features, mean)
        val min = features.minOrNull() ?: 0f
        val max = features.maxOrNull() ?: 0f
        val range = max - min
        val zeroCount = features.count { it == 0f }
        val nonZeroCount = features.size - zeroCount
        
        val issues = mutableListOf<String>()
        
        // Check if features are in expected range for robust Min-Max scaling: [-1, 1]
        if (min < -1.5f || max > 1.5f) {
            issues.add("Features outside expected range [-1, 1]: min=$min, max=$max. Scaling may be incorrect.")
        }
        
        // Check if mean is near 0 (expected for scaled features)
        if (kotlin.math.abs(mean) > 0.2f && nonZeroCount > 0) {
            issues.add("Feature mean not near zero: $mean (expected ~0 for scaled features). Scaling may be incorrect.")
        }
        
        // Check for too many zeros (missing hands/face)
        val zeroRatio = zeroCount.toFloat() / features.size
        if (zeroRatio > 0.3f) {
            issues.add("High zero ratio: ${(zeroRatio * 100).toInt()}% ($zeroCount/${features.size}). Many features are missing (hands/face not detected).")
        }
        
        // Check if features are all zeros
        if (nonZeroCount == 0) {
            issues.add("CRITICAL: All features are zero! No landmarks detected.")
        }
        
        return FeatureReport(
            mean = mean,
            std = std,
            min = min,
            max = max,
            range = range,
            zeroCount = zeroCount,
            nonZeroCount = nonZeroCount,
            zeroRatio = zeroRatio,
            issues = issues
        )
    }
    
    /**
     * Log comprehensive diagnostic report.
     */
    fun logDiagnostics(
        logits: FloatArray,
        probabilities: FloatArray,
        features: FloatArray? = null
    ) {
        val confReport = analyzeLowConfidence(logits, probabilities)
        Log.w(TAG, "=== CONFIDENCE DIAGNOSTICS ===")
        Log.w(TAG, "Logits: range=[${confReport.minLogit}, ${confReport.maxLogit}], " +
                "mean=${confReport.logitMean}, std=${confReport.logitStd}")
        Log.w(TAG, "Probabilities: max=${confReport.maxProbability} (${(confReport.maxProbability * 100).toInt()}%), " +
                "range=${confReport.probabilityRange}")
        Log.w(TAG, "Entropy: ${confReport.entropy}/${confReport.maxEntropy} " +
                "(${(confReport.entropy / confReport.maxEntropy * 100).toInt()}% of max)")
        Log.w(TAG, "Top 3: ${confReport.top3Predictions.take(3).joinToString { 
            "idx=${it.first}=${(it.second * 100).toInt()}%" 
        }}")
        
        if (confReport.issues.isNotEmpty()) {
            Log.e(TAG, "⚠️ ISSUES DETECTED:")
            confReport.issues.forEach { issue ->
                Log.e(TAG, "  - $issue")
            }
        } else {
            Log.i(TAG, "✅ No obvious issues detected")
        }
        
        if (features != null) {
            val featReport = analyzeFeatures(features)
            Log.w(TAG, "=== FEATURE DIAGNOSTICS ===")
            Log.w(TAG, "Features: range=[${featReport.min}, ${featReport.max}], " +
                    "mean=${featReport.mean}, std=${featReport.std}, " +
                    "zeros=${featReport.zeroCount}/${features.size} (${(featReport.zeroRatio * 100).toInt()}%)")
            
            if (featReport.issues.isNotEmpty()) {
                Log.e(TAG, "⚠️ FEATURE ISSUES:")
                featReport.issues.forEach { issue ->
                    Log.e(TAG, "  - $issue")
                }
            }
        }
    }
    
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { kotlin.math.exp((it - maxLogit).toDouble()).toFloat() }
        val sumExps = exps.sum()
        return exps.map { it / sumExps }.toFloatArray()
    }
    
    private fun calculateStd(values: FloatArray, mean: Float): Float {
        if (values.isEmpty()) return 0f
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return kotlin.math.sqrt(variance)
    }
    
    private fun calculateEntropy(probabilities: FloatArray): Float {
        return -probabilities.sumOf { prob ->
            if (prob > 0f) prob * kotlin.math.ln(prob.toDouble()) else 0.0
        }.toFloat()
    }
    
    data class ConfidenceReport(
        val maxLogit: Float,
        val minLogit: Float,
        val logitRange: Float,
        val logitMean: Float,
        val logitStd: Float,
        val maxProbability: Float,
        val minProbability: Float,
        val probabilityRange: Float,
        val entropy: Float,
        val maxEntropy: Float,
        val top3Predictions: List<Pair<Int, Float>>,
        val issues: List<String>
    )
    
    data class FeatureReport(
        val mean: Float,
        val std: Float,
        val min: Float,
        val max: Float,
        val range: Float,
        val zeroCount: Int,
        val nonZeroCount: Int,
        val zeroRatio: Float,
        val issues: List<String>
    )
}

