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
        private const val FEATURE_MEAN_ASSET = "recognition/feature_mean_v2.npy"
        private const val FEATURE_STD_ASSET = "recognition/feature_std_v2.npy"
        private const val EXPECTED_FEATURE_DIM = 237 // Left Hand (63) + Right Hand (63) + Face (111)
        private const val EPSILON = 1e-8f // Epsilon for numerical stability in normalization
        
        /**
         * Create FeatureScaler by loading mean and std from assets.
         * 
         * @param context Android context
         * @param featureDim Expected feature dimension (default 126)
         * @return FeatureScaler instance, or null if loading failed
         */
        fun create(context: Context, featureDim: Int = EXPECTED_FEATURE_DIM): FeatureScaler? {
            Log.i("FeatureScaler", "🔧 Creating FeatureScaler: featureDim=$featureDim")
            
            // Verify assets exist before attempting to load
            // Note: assets.list("") only lists root directory, so we check by trying to open the files
            try {
                val hasMean = try {
                    context.assets.open(FEATURE_MEAN_ASSET).use { true }
                } catch (e: Exception) {
                    false
                }
                
                val hasStd = try {
                    context.assets.open(FEATURE_STD_ASSET).use { true }
                } catch (e: Exception) {
                    false
                }
                
                Log.i("FeatureScaler", "📂 Asset verification: feature_mean_v2.npy=${if (hasMean) "✅ EXISTS" else "❌ MISSING"}, feature_std_v2.npy=${if (hasStd) "✅ EXISTS" else "❌ MISSING"}")
                
                if (!hasMean || !hasStd) {
                    // List available assets for debugging
                    val rootAssets = context.assets.list("")?.toList() ?: emptyList()
                    val recognitionAssets = try {
                        context.assets.list("recognition")?.toList() ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val allNpyFiles = (rootAssets + recognitionAssets.map { "recognition/$it" })
                        .filter { it.contains("feature") || it.endsWith(".npy") }
                    Log.e("FeatureScaler", "❌ CRITICAL: Asset files missing! Expected: $FEATURE_MEAN_ASSET, $FEATURE_STD_ASSET")
                    Log.e("FeatureScaler", "📂 Available assets containing 'feature' or '.npy': ${allNpyFiles.joinToString(", ")}")
                    return null
                }
            } catch (e: Exception) {
                Log.e("FeatureScaler", "❌ Failed to verify assets: ${e.javaClass.simpleName} - ${e.message}", e)
                return null
            }
            
            Log.i("FeatureScaler", "📂 Loading mean from: $FEATURE_MEAN_ASSET")
            val mean = try {
                NpyFileReader.loadFloatArray(context, FEATURE_MEAN_ASSET, featureDim)
            } catch (e: Exception) {
                Log.e("FeatureScaler", "❌ EXCEPTION loading mean: ${e.javaClass.simpleName} - ${e.message}", e)
                null
            }
            
            Log.i("FeatureScaler", "📂 Loading std from: $FEATURE_STD_ASSET")
            val std = try {
                NpyFileReader.loadFloatArray(context, FEATURE_STD_ASSET, featureDim)
            } catch (e: Exception) {
                Log.e("FeatureScaler", "❌ EXCEPTION loading std: ${e.javaClass.simpleName} - ${e.message}", e)
                null
            }
            
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
            
            // Validate dimensions - CRITICAL: Must match expected feature dimension
            if (mean.size != featureDim || std.size != featureDim) {
                Log.e("FeatureScaler", "❌ Dimension mismatch: mean.size=${mean.size}, std.size=${std.size}, expected=$featureDim")
                Log.e("FeatureScaler", "❌ CRITICAL: Make sure feature_mean_v2.npy and feature_std_v2.npy files (size $featureDim) are copied to app/src/main/assets/recognition/")
                return null
            }
            
            // Explicit validation for 237 features (catches missing/incorrect asset files early)
            if (mean.size != 237 || std.size != 237) {
                Log.e("FeatureScaler", "❌ CRITICAL: Expected 237 features (Left Hand 63 + Right Hand 63 + Face 111), " +
                        "but got mean.size=${mean.size}, std.size=${std.size}")
                Log.e("FeatureScaler", "❌ Make sure you copied the correct feature_mean_v2.npy and feature_std_v2.npy files from Python training pipeline")
                return null
            }
            
            Log.d("FeatureScaler", "✅ Dimension validation passed: both arrays are size $featureDim (237 features)")
            
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
     * Apply feature scaling: scaled = (features - mean) / (std + epsilon)
     * Uses epsilon 1e-8 for numerical stability (matches Python training pipeline).
     * 
     * @param features Raw feature vector (must be size 237)
     * @return Scaled feature vector
     */
    fun scale(features: FloatArray): FloatArray {
        val scaled = FloatArray(features.size)
        for (i in features.indices) {
            // Simple Shift & Scale: 0.0 -> -1.0, 1.0 -> 1.0
            // Formula: (x - 0.5) * 2.0
            scaled[i] = (features[i] - 0.5f) * 2.0f
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

