package com.example.expressora.recognition.pipeline

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.expressora.recognition.feature.FeatureScaler
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor.Point3
import com.example.expressora.recognition.roi.RoiCoordinateMapper
import com.example.expressora.recognition.utils.LogUtils
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

object HandToFeaturesBridge {
    private const val TAG = "HandToFeaturesBridge"
    
    // Feature scaler for retrained model (lazy initialization)
    @Volatile
    private var featureScaler: FeatureScaler? = null
    
    /**
     * Initialize feature scaler with context.
     * Should be called once during app startup.
     */
    fun initializeScaler(context: Context) {
        Log.d(TAG, "🔧 Initializing FeatureScaler...")
        if (featureScaler == null) {
            synchronized(this) {
                if (featureScaler == null) {
                    Log.d(TAG, "📂 Creating FeatureScaler instance...")
                    featureScaler = FeatureScaler.create(context.applicationContext)
                    if (featureScaler != null) {
                        val featureDim = featureScaler!!.getFeatureDim()
                        val isValid = featureScaler!!.isValid()
                        Log.i(TAG, "✅ FeatureScaler initialized successfully: featureDim=$featureDim, isValid=$isValid")
                    } else {
                        Log.e(TAG, "❌ CRITICAL: Failed to initialize FeatureScaler! Model will not work correctly without feature scaling.")
                        Log.e(TAG, "❌ Make sure feature_mean.npy and feature_std.npy are in app/src/main/assets/")
                    }
                } else {
                    Log.d(TAG, "✅ FeatureScaler already initialized (another thread)")
                }
            }
        } else {
            Log.d(TAG, "✅ FeatureScaler already initialized")
        }
    }
    
    /**
     * Check if feature scaler is initialized.
     */
    fun isScalerInitialized(): Boolean {
        val initialized = featureScaler != null
        LogUtils.debugIfVerbose(TAG) { "FeatureScaler initialized: $initialized" }
        return initialized
    }

    data class Hands(val left: List<Point3>?, val right: List<Point3>?)

    /**
     * Extract hands from MediaPipe result, optionally mapping ROI coordinates to full image.
     * 
     * @param result HandLandmarkerResult from MediaPipe
     * @param roiOffset ROI bounding box if ROI was used, null otherwise
     * @param croppedWidth Width of the cropped bitmap that was processed
     * @param croppedHeight Height of the cropped bitmap that was processed
     * @param fullWidth Width of the full original image
     * @param fullHeight Height of the full original image
     */
    fun extract(
        result: HandLandmarkerResult,
        roiOffset: Rect? = null,
        croppedWidth: Int = 0,
        croppedHeight: Int = 0,
        fullWidth: Int = 0,
        fullHeight: Int = 0
    ): Hands {
        var left: List<Point3>? = null
        var right: List<Point3>? = null
        val handednesses = result.handednesses()
        val landmarks = result.landmarks()
        val size = minOf(handednesses.size, landmarks.size)
        
        // DEBUG: Log hand detection info
        Log.d(TAG, "Extracting hands: totalHands=${landmarks.size}, handednesses=${handednesses.size}, processing=$size")
        
        for (index in 0 until size) {
            val handedness = handednesses[index].firstOrNull()
            val category = handedness?.categoryName() ?: continue
            val score = handedness.score()
            val points = landmarks[index].map { landmark ->
                val point = Point3(landmark.x(), landmark.y(), landmark.z())
                // Map ROI coordinates to full image coordinates if ROI was used
                if (roiOffset != null && croppedWidth > 0 && croppedHeight > 0 && 
                    fullWidth > 0 && fullHeight > 0) {
                    RoiCoordinateMapper.mapPoint(
                        point, roiOffset, croppedWidth, croppedHeight, fullWidth, fullHeight
                    )
                } else {
                    point
                }
            }
            
            // CRITICAL: Log raw handedness from MediaPipe before mapping
            Log.d("ExpressoraHandedness", "Raw handedness: handIndex=$index, " +
                    "label='$category', score=$score, landmarks=${points.size}")
            
            // DEBUG: Log landmark extraction
            Log.v(TAG, "Hand $index: category='$category', landmarks=${points.size}")
            
            // FIX: MediaPipe's handedness labels are reversed relative to the person's actual hands
            // When MediaPipe says "Left", it's actually the person's right hand, and vice versa
            // This is because MediaPipe's handedness is based on the image orientation it sees
            when {
                category.equals(LEFT, ignoreCase = true) -> {
                    // MediaPipe "Left" = person's actual right hand
                    right = points
                    // CRITICAL: Log mapped handedness after processing
                    Log.d("ExpressoraHandedness", "Mapped handedness: appSide=RIGHT (from raw='$category', score=$score) " +
                            "[MediaPipe 'Left' → person's actual RIGHT hand]")
                    Log.d(TAG, "Right hand extracted: ${points.size} landmarks")
                }
                category.equals(RIGHT, ignoreCase = true) -> {
                    // MediaPipe "Right" = person's actual left hand
                    left = points
                    // CRITICAL: Log mapped handedness after processing
                    Log.d("ExpressoraHandedness", "Mapped handedness: appSide=LEFT (from raw='$category', score=$score) " +
                            "[MediaPipe 'Right' → person's actual LEFT hand]")
                    Log.d(TAG, "Left hand extracted: ${points.size} landmarks")
                }
                else -> {
                    // Log unknown handedness category
                    Log.w("ExpressoraHandedness", "Unknown handedness category: '$category' (score=$score)")
                }
            }
        }
        
        // DEBUG: Log extraction result
        val leftCount = left?.size ?: 0
        val rightCount = right?.size ?: 0
        Log.d(TAG, "Extraction result: left=$leftCount landmarks, right=$rightCount landmarks")
        
        if (left == null && right == null) {
            Log.w(TAG, "WARNING: No hands extracted from MediaPipe result!")
        }
        
        return Hands(left, right)
    }

    fun toVec(hands: Hands, twoHands: Boolean): FloatArray {
        val leftCount = hands.left?.size ?: 0
        val rightCount = hands.right?.size ?: 0
        Log.d(TAG, "Converting to feature vector: twoHands=$twoHands, left=$leftCount, right=$rightCount")
        
        // Extract raw feature vector from landmarks
        val rawFeatureVector = LandmarkFeatureExtractor.toFeatureVector(
            left = hands.left,
            right = hands.right,
            twoHands = twoHands,
        )
        
        // DEBUG: Log raw feature vector info
        val nonZeroCount = rawFeatureVector.count { it != 0f }
        val expectedSize = if (twoHands) 126 else 63
        LogUtils.debugIfVerbose(TAG) { 
            "Raw feature vector: size=${rawFeatureVector.size}, expected=$expectedSize, " +
            "nonZero=$nonZeroCount, isAllZeros=${nonZeroCount == 0}"
        }
        
        if (rawFeatureVector.size != expectedSize) {
            Log.w(TAG, "WARNING: Feature vector size mismatch! got=${rawFeatureVector.size}, expected=$expectedSize")
        }
        
        if (nonZeroCount == 0) {
            Log.e(TAG, "ERROR: Feature vector is all zeros! Hands may not be detected correctly.")
        }
        
        // CRITICAL: Apply feature scaling for retrained model
        // The model expects scaled features: (features - mean) / std
        val scaledFeatureVector = if (featureScaler != null && rawFeatureVector.size == 126) {
            try {
                Log.d(TAG, "🔄 Applying feature scaling to ${rawFeatureVector.size} features...")
                val scaled = featureScaler!!.scale(rawFeatureVector)
                val rawMin = rawFeatureVector.minOrNull() ?: 0f
                val rawMax = rawFeatureVector.maxOrNull() ?: 0f
                val scaledMin = scaled.minOrNull() ?: 0f
                val scaledMax = scaled.maxOrNull() ?: 0f
                Log.d(TAG, "✅ Feature scaling completed: raw[mean=${rawFeatureVector.average()}, " +
                        "range=${rawMax - rawMin}] -> " +
                        "scaled[mean=${scaled.average()}, range=${scaledMax - scaledMin}]")
                scaled
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error applying feature scaling, using raw features", e)
                rawFeatureVector
            }
        } else {
            if (featureScaler == null) {
                Log.e(TAG, "❌ CRITICAL: FeatureScaler not initialized! Model requires feature scaling. " +
                        "Call HandToFeaturesBridge.initializeScaler(context) during app startup.")
                Log.e(TAG, "❌ Without scaling, model will produce incorrect/static predictions!")
            } else if (rawFeatureVector.size != 126) {
                Log.w(TAG, "⚠️ Feature vector size is ${rawFeatureVector.size}, expected 126. Skipping scaling.")
            }
            rawFeatureVector
        }
        
        LogUtils.debugIfVerbose(TAG) {
            "Final feature vector: size=${scaledFeatureVector.size}, " +
            "nonZero=${scaledFeatureVector.count { it != 0f }}, " +
            "mean=${scaledFeatureVector.average()}, " +
            "range=[${scaledFeatureVector.minOrNull()}, ${scaledFeatureVector.maxOrNull()}]"
        }
        
        return scaledFeatureVector
    }

    private const val LEFT = "Left"
    private const val RIGHT = "Right"
}

