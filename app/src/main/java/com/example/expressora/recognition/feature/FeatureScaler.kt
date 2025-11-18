package com.example.expressora.recognition.feature

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.utils.NpyFileReader
import com.example.expressora.recognition.utils.LogUtils

/**
 * Utility to load and apply feature scaling (mean/std normalization).
 * 
 * The retrained model requires features to be scaled using:
 *   scaled = (features - mean) / std
 * 
 * This matches the preprocessing used during model training.
 */
class FeatureScaler private constructor(
    private val mean: FloatArray,
    private val std: FloatArray
) {
    private val TAG = "FeatureScaler"
    
    companion object {
        private const val FEATURE_MEAN_ASSET = "feature_mean.npy"
        private const val FEATURE_STD_ASSET = "feature_std.npy"
        private const val EXPECTED_FEATURE_DIM = 126
        
        /**
         * Create FeatureScaler by loading mean and std from assets.
         * 
         * @param context Android context
         * @param featureDim Expected feature dimension (default 126)
         * @return FeatureScaler instance, or null if loading failed
         */
        fun create(context: Context, featureDim: Int = EXPECTED_FEATURE_DIM): FeatureScaler? {
            Log.d("FeatureScaler", "🔧 Creating FeatureScaler: featureDim=$featureDim")
            Log.d("FeatureScaler", "📂 Loading mean from: $FEATURE_MEAN_ASSET")
            val mean = NpyFileReader.loadFloatArray(context, FEATURE_MEAN_ASSET, featureDim)
            Log.d("FeatureScaler", "📂 Loading std from: $FEATURE_STD_ASSET")
            val std = NpyFileReader.loadFloatArray(context, FEATURE_STD_ASSET, featureDim)
            
            if (mean == null) {
                Log.e("FeatureScaler", "❌ Failed to load mean from $FEATURE_MEAN_ASSET")
            } else {
                Log.d("FeatureScaler", "✅ Loaded mean: size=${mean.size}, range=[${mean.minOrNull()}, ${mean.maxOrNull()}], " +
                        "mean=${mean.average()}, sample[0-4]=[${mean.slice(0 until minOf(5, mean.size)).joinToString()}]")
            }
            
            if (std == null) {
                Log.e("FeatureScaler", "❌ Failed to load std from $FEATURE_STD_ASSET")
            } else {
                Log.d("FeatureScaler", "✅ Loaded std: size=${std.size}, range=[${std.minOrNull()}, ${std.maxOrNull()}], " +
                        "mean=${std.average()}, sample[0-4]=[${std.slice(0 until minOf(5, std.size)).joinToString()}]")
            }
            
            if (mean == null || std == null) {
                Log.e("FeatureScaler", "❌ Failed to load scaling parameters: mean=${mean != null}, std=${std != null}")
                return null
            }
            
            // Validate dimensions
            if (mean.size != featureDim || std.size != featureDim) {
                Log.e("FeatureScaler", "❌ Dimension mismatch: mean.size=${mean.size}, std.size=${std.size}, expected=$featureDim")
                return null
            }
            Log.d("FeatureScaler", "✅ Dimension validation passed: both arrays are size $featureDim")
            
            // Validate std values (should not be zero)
            val zeroStdCount = std.count { it == 0f || it.isNaN() }
            val zeroStdIndices = std.mapIndexed { index, value -> 
                if (value == 0f || value.isNaN()) index else null 
            }.filterNotNull()
            if (zeroStdCount > 0) {
                Log.w("FeatureScaler", "⚠️ Warning: $zeroStdCount std values are zero or NaN at indices: " +
                        "${zeroStdIndices.take(10).joinToString()}${if (zeroStdIndices.size > 10) "..." else ""}, " +
                        "will use 1.0 for those features")
            } else {
                Log.d("FeatureScaler", "✅ All std values are non-zero and valid")
            }
            
            // Log statistics
            val meanMin = mean.minOrNull() ?: 0f
            val meanMax = mean.maxOrNull() ?: 0f
            val meanMean = mean.average()
            val stdMin = std.minOrNull() ?: 0f
            val stdMax = std.maxOrNull() ?: 0f
            val stdMean = std.average()
            
            Log.i("FeatureScaler", "✅ FeatureScaler initialized successfully: featureDim=$featureDim")
            Log.d("FeatureScaler", "📊 Mean stats: range=[$meanMin, $meanMax], mean=$meanMean")
            Log.d("FeatureScaler", "📊 Std stats: range=[$stdMin, $stdMax], mean=$stdMean, " +
                    "zeroCount=$zeroStdCount, validCount=${std.size - zeroStdCount}")
            
            return FeatureScaler(mean, std)
        }
    }
    
    /**
     * Apply feature scaling: scaled = (features - mean) / std
     * 
     * @param features Raw feature vector (must be size 126)
     * @return Scaled feature vector
     */
    fun scale(features: FloatArray): FloatArray {
        if (features.size != mean.size) {
            Log.e(TAG, "❌ Feature size mismatch: got ${features.size}, expected ${mean.size}")
            throw IllegalArgumentException("Feature size mismatch: got ${features.size}, expected ${mean.size}")
        }
        
        Log.d(TAG, "🔄 Scaling feature vector: size=${features.size}")
        
        // Calculate raw feature statistics before scaling
        val rawMin = features.minOrNull() ?: 0f
        val rawMax = features.maxOrNull() ?: 0f
        val rawMean = features.average().toFloat()
        val rawNonZero = features.count { it != 0f }
        Log.d(TAG, "📊 Raw features: min=$rawMin, max=$rawMax, mean=$rawMean, nonZero=$rawNonZero/${features.size}")
        
        val scaled = FloatArray(features.size)
        var zeroStdUsed = 0
        var scalingErrors = 0
        
        for (i in features.indices) {
            val stdValue = if (std[i] == 0f || std[i].isNaN()) {
                zeroStdUsed++
                1.0f // Avoid division by zero
            } else {
                std[i]
            }
            
            val scaledValue = try {
                (features[i] - mean[i]) / stdValue
            } catch (e: Exception) {
                scalingErrors++
                Log.w(TAG, "⚠️ Scaling error at index $i: feature=${features[i]}, mean=${mean[i]}, std=$stdValue", e)
                0f // Fallback to 0
            }
            
            scaled[i] = scaledValue
        }
        
        if (zeroStdUsed > 0) {
            Log.d(TAG, "⚠️ Used fallback std=1.0 for $zeroStdUsed features (had zero/NaN std)")
        }
        if (scalingErrors > 0) {
            Log.w(TAG, "⚠️ Encountered $scalingErrors scaling errors")
        }
        
        // Calculate scaled feature statistics
        val scaledMin = scaled.minOrNull() ?: 0f
        val scaledMax = scaled.maxOrNull() ?: 0f
        val scaledMean = scaled.average().toFloat()
        val scaledStd = kotlin.math.sqrt(
            scaled.map { (it - scaledMean) * (it - scaledMean) }.average()
        ).toFloat()
        val scaledNonZero = scaled.count { it != 0f }
        
        // Log scaling statistics
        Log.d(TAG, "📊 Scaled features: min=$scaledMin, max=$scaledMax, mean=$scaledMean, std=$scaledStd, nonZero=$scaledNonZero/${scaled.size}")
        Log.d(TAG, "📊 Scaling transformation: raw[mean=$rawMean, range=${rawMax - rawMin}] -> " +
                "scaled[mean=$scaledMean, range=${scaledMax - scaledMin}]")
        
        // Log sample values for first few features
        val sampleSize = minOf(5, features.size)
        LogUtils.debugIfVerbose(TAG) {
            val samplePairs = (0 until sampleSize).map { i ->
                "f[$i]: ${features[i]} -> (${features[i]} - ${mean[i]}) / ${std[i]} = ${scaled[i]}"
            }
            "🔍 Sample scaling (first $sampleSize):\n  ${samplePairs.joinToString("\n  ")}"
        }
        
        // Validate scaled features (should have mean near 0 and std near 1 if scaling worked correctly)
        val meanDeviation = kotlin.math.abs(scaledMean)
        val stdDeviation = kotlin.math.abs(scaledStd - 1.0f)
        if (meanDeviation > 0.5f || stdDeviation > 0.5f) {
            Log.w(TAG, "⚠️ Scaled features may not be properly normalized: mean=$scaledMean (expected ~0), " +
                    "std=$scaledStd (expected ~1.0). This may indicate scaling parameter mismatch.")
        } else {
            LogUtils.debugIfVerbose(TAG) { "✅ Scaled features appear properly normalized: mean≈0, std≈1" }
        }
        
        return scaled
    }
    
    /**
     * Get the feature dimension this scaler expects.
     */
    fun getFeatureDim(): Int = mean.size
    
    /**
     * Check if scaler is properly initialized.
     */
    fun isValid(): Boolean = mean.isNotEmpty() && std.isNotEmpty() && mean.size == std.size
}

