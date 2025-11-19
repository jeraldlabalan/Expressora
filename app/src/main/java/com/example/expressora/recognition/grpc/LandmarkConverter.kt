package com.example.expressora.recognition.grpc

import android.util.Log
import com.example.expressora.grpc.LandmarkFrame
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult

object LandmarkConverter {
    private const val TAG = "LandmarkConverter"
    
    // Constants for landmark counts
    private const val HAND_LANDMARKS_PER_HAND = 21
    private const val FACE_LANDMARKS_COUNT = 468
    private const val COORDS_PER_LANDMARK = 3 // x, y, z
    
    /**
     * Convert HolisticLandmarkerResult to LandmarkFrame proto message.
     * Extracts hands and face landmarks into flattened float arrays.
     * Pose detection removed - only hands and face are sent.
     * Proto structure: hands, face (flattened arrays), timestamp.
     */
    fun toLandmarkFrame(
        result: HolisticLandmarkerResult,
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): LandmarkFrame {
        val builder = LandmarkFrame.newBuilder()
            .setTimestamp(timestampMs)
        
        // Extract hand landmarks (up to 2 hands) - flattened array
        val handLandmarks = extractHandLandmarks(result)
        builder.addAllHands(handLandmarks)
        
        // Extract face landmarks - flattened array
        val faceLandmarks = extractFaceLandmarks(result)
        builder.addAllFace(faceLandmarks)
        
        // Pose detection removed - not needed for sign language recognition
        
        return builder.build()
    }
    
    /**
     * Extract hand landmarks from result.
     * Format: [hand0_x0, hand0_y0, hand0_z0, hand0_x1, ... hand1_x0, ...]
     * Total: 2 hands * 21 landmarks * 3 coords = 126 floats
     */
    private fun extractHandLandmarks(result: HolisticLandmarkerResult): List<Float> {
        // MediaPipe Holistic uses leftHandLandmarks() and rightHandLandmarks()
        val leftHand = result.leftHandLandmarks()
        val rightHand = result.rightHandLandmarks()
        val flattened = mutableListOf<Float>()
        
        // Process left hand (if available)
        if (leftHand != null && leftHand.isNotEmpty()) {
            for (landmark in leftHand) {
                flattened.add(landmark.x())
                flattened.add(landmark.y())
                flattened.add(landmark.z())
            }
        } else {
            // Zero-pad left hand
            repeat(HAND_LANDMARKS_PER_HAND * COORDS_PER_LANDMARK) {
                flattened.add(0f)
            }
        }
        
        // Process right hand (if available)
        if (rightHand != null && rightHand.isNotEmpty()) {
            for (landmark in rightHand) {
                flattened.add(landmark.x())
                flattened.add(landmark.y())
                flattened.add(landmark.z())
            }
        } else {
            // Zero-pad right hand
            repeat(HAND_LANDMARKS_PER_HAND * COORDS_PER_LANDMARK) {
                flattened.add(0f)
            }
        }
        
        return flattened
    }
    
    /**
     * Extract face landmarks from result.
     * Format: [x0, y0, z0, x1, y1, z1, ...]
     * Total: 468 * 3 = 1404 floats
     */
    private fun extractFaceLandmarks(result: HolisticLandmarkerResult): List<Float> {
        val faceLandmarks = result.faceLandmarks()
        val flattened = mutableListOf<Float>()
        
        if (faceLandmarks != null && faceLandmarks.isNotEmpty()) {
            for (landmark in faceLandmarks) {
                flattened.add(landmark.x())
                flattened.add(landmark.y())
                flattened.add(landmark.z())
            }
        }
        
        // Zero-pad to maintain consistent 1404-float format
        while (flattened.size < FACE_LANDMARKS_COUNT * COORDS_PER_LANDMARK) {
            flattened.add(0f)
            flattened.add(0f)
            flattened.add(0f)
        }
        
        return flattened
    }
    
}

