package com.example.expressora.recognition.pipeline

import android.util.Log
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor.Point3
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

object HandToFeaturesBridge {
    private const val TAG = "HandToFeaturesBridge"

    data class Hands(val left: List<Point3>?, val right: List<Point3>?)

    fun extract(result: HandLandmarkerResult): Hands {
        var left: List<Point3>? = null
        var right: List<Point3>? = null
        val handednesses = result.handednesses()
        val landmarks = result.landmarks()
        val size = minOf(handednesses.size, landmarks.size)
        
        // DEBUG: Log hand detection info
        Log.d(TAG, "Extracting hands: totalHands=${landmarks.size}, handednesses=${handednesses.size}, processing=$size")
        
        for (index in 0 until size) {
            val category = handednesses[index].firstOrNull()?.categoryName() ?: continue
            val points = landmarks[index].map { landmark ->
                Point3(landmark.x(), landmark.y(), landmark.z())
            }
            
            // DEBUG: Log landmark extraction
            Log.v(TAG, "Hand $index: category='$category', landmarks=${points.size}")
            
            when {
                category.equals(LEFT, ignoreCase = true) -> {
                    left = points
                    Log.d(TAG, "Left hand extracted: ${points.size} landmarks")
                }
                category.equals(RIGHT, ignoreCase = true) -> {
                    right = points
                    Log.d(TAG, "Right hand extracted: ${points.size} landmarks")
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
        
        val featureVector = LandmarkFeatureExtractor.toFeatureVector(
            left = hands.left,
            right = hands.right,
            twoHands = twoHands,
        )
        
        // DEBUG: Log feature vector info
        val nonZeroCount = featureVector.count { it != 0f }
        val expectedSize = if (twoHands) 126 else 63
        Log.d(TAG, "Feature vector created: size=${featureVector.size}, expected=$expectedSize, " +
                "nonZero=$nonZeroCount, isAllZeros=${nonZeroCount == 0}")
        
        if (featureVector.size != expectedSize) {
            Log.w(TAG, "WARNING: Feature vector size mismatch! got=${featureVector.size}, expected=$expectedSize")
        }
        
        if (nonZeroCount == 0) {
            Log.e(TAG, "ERROR: Feature vector is all zeros! Hands may not be detected correctly.")
        }
        
        return featureVector
    }

    private const val LEFT = "Left"
    private const val RIGHT = "Right"
}

