package com.example.expressora.recognition.pipeline

import com.example.expressora.recognition.feature.LandmarkFeatureExtractor
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor.Point3
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

object HandToFeaturesBridge {

    data class Hands(val left: List<Point3>?, val right: List<Point3>?)

    fun extract(result: HandLandmarkerResult): Hands {
        var left: List<Point3>? = null
        var right: List<Point3>? = null
        val handednesses = result.handednesses()
        val landmarks = result.landmarks()
        val size = minOf(handednesses.size, landmarks.size)
        for (index in 0 until size) {
            val category = handednesses[index].firstOrNull()?.categoryName() ?: continue
            val points = landmarks[index].map { landmark ->
                Point3(landmark.x(), landmark.y(), landmark.z())
            }
            when {
                category.equals(LEFT, ignoreCase = true) -> left = points
                category.equals(RIGHT, ignoreCase = true) -> right = points
            }
        }
        return Hands(left, right)
    }

    fun toVec(hands: Hands, twoHands: Boolean): FloatArray {
        return LandmarkFeatureExtractor.toFeatureVector(
            left = hands.left,
            right = hands.right,
            twoHands = twoHands,
        )
    }

    private const val LEFT = "Left"
    private const val RIGHT = "Right"
}

