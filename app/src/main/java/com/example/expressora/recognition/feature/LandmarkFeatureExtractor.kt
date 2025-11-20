package com.example.expressora.recognition.feature

import android.util.Log

object LandmarkFeatureExtractor {
    private const val TAG = "LandmarkFeatureExtractor"

    data class Point3(val x: Float, val y: Float, val z: Float)

    fun toFeatureVector(
        left: List<Point3>?,
        right: List<Point3>?,
        twoHands: Boolean, // Deprecated: kept for API compatibility but ignored - always returns 126 dims
    ): FloatArray {
        // DEBUG: Log input parameters
        Log.v(TAG, "toFeatureVector: twoHands=$twoHands (ignored), left=${left?.size ?: 0}, right=${right?.size ?: 0}")
        
        // ALWAYS return 126 dimensions (TWO_HAND_DIM) to match model input shape [1, 126]
        // Missing hands are automatically zero-padded: fill() returns early if points is null, leaving zeros
        // FloatArray initializes to 0.0f by default, so null hands result in zero-filled slots
        return FloatArray(TWO_HAND_DIM).also { output ->
            fill(output, 0, left, "left")      // First 63: left hand (or zeros if null)
            fill(output, HALF_DIM, right, "right") // Last 63: right hand (or zeros if null)
            
            // DEBUG: Log filled result
            val leftFilled = output.slice(0 until HALF_DIM).count { it != 0f }
            val rightFilled = output.slice(HALF_DIM until TWO_HAND_DIM).count { it != 0f }
            Log.d(TAG, "Feature vector: totalSize=$TWO_HAND_DIM, leftFilled=$leftFilled, rightFilled=$rightFilled")
        }
    }

    private fun fill(target: FloatArray, offset: Int, points: List<Point3>?, handLabel: String) {
        if (points == null) {
            Log.v(TAG, "fill($handLabel): points is null, leaving zeros at offset $offset")
            return
        }
        
        var index = offset
        val limit = minOf(points.size, LANDMARK_COUNT)
        
        // DEBUG: Log landmark range
        if (points.isNotEmpty()) {
            val firstPoint = points[0]
            val lastPoint = points[minOf(points.size - 1, limit - 1)]
            Log.v(TAG, "fill($handLabel): processing $limit landmarks, " +
                    "first=(${firstPoint.x}, ${firstPoint.y}, ${firstPoint.z}), " +
                    "last=(${lastPoint.x}, ${lastPoint.y}, ${lastPoint.z})")
        }
        
        repeat(limit) { position ->
            val landmark = points[position]
            target[index++] = landmark.x
            target[index++] = landmark.y
            target[index++] = landmark.z
        }
        
        // DEBUG: Warn if we got fewer landmarks than expected
        if (points.size < LANDMARK_COUNT) {
            Log.w(TAG, "fill($handLabel): Only got ${points.size} landmarks, expected $LANDMARK_COUNT. Padding with zeros.")
        }
        
        Log.v(TAG, "fill($handLabel): Filled ${limit * COMPONENTS_PER_POINT} values starting at offset $offset")
    }

    private const val LANDMARK_COUNT = 21
    private const val COMPONENTS_PER_POINT = 3
    private const val ONE_HAND_DIM = LANDMARK_COUNT * COMPONENTS_PER_POINT
    private const val TWO_HAND_DIM = ONE_HAND_DIM * 2
    private const val HALF_DIM = ONE_HAND_DIM
}


