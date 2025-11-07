package com.example.expressora.recognition.feature

object LandmarkFeatureExtractor {

    data class Point3(val x: Float, val y: Float, val z: Float)

    fun toFeatureVector(
        left: List<Point3>?,
        right: List<Point3>?,
        twoHands: Boolean,
    ): FloatArray {
        return if (twoHands) {
            FloatArray(TWO_HAND_DIM).also { output ->
                fill(output, 0, left)
                fill(output, HALF_DIM, right)
            }
        } else {
            val hand = left ?: right
            FloatArray(ONE_HAND_DIM).also { output ->
                fill(output, 0, hand)
            }
        }
    }

    private fun fill(target: FloatArray, offset: Int, points: List<Point3>?) {
        if (points == null) return
        var index = offset
        val limit = minOf(points.size, LANDMARK_COUNT)
        repeat(limit) { position ->
            val landmark = points[position]
            target[index++] = landmark.x
            target[index++] = landmark.y
            target[index++] = landmark.z
        }
    }

    private const val LANDMARK_COUNT = 21
    private const val COMPONENTS_PER_POINT = 3
    private const val ONE_HAND_DIM = LANDMARK_COUNT * COMPONENTS_PER_POINT
    private const val TWO_HAND_DIM = ONE_HAND_DIM * 2
    private const val HALF_DIM = ONE_HAND_DIM
}

