package com.example.expressora.recognition.feature

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LandmarkFeatureExtractor(
    private val featureScaler: FeatureScaler? = null
) {
    private val TAG = "LandmarkFeatureExtractor"
    
    // Frame buffer for LSTM model (30 frames)
    private val frameBuffer = ArrayDeque<FloatArray>(30)
    private val BUFFER_SIZE = 30
    private val FEATURES_PER_FRAME = 237 // Left Hand (63) + Right Hand (63) + Face (111)
    
    data class Point3(val x: Float, val y: Float, val z: Float)
    
    // Face landmark indices (MediaPipe Face Mesh indices 0-467)
    private val FACE_EYEBROW_INDICES = intArrayOf(46, 52, 53, 65, 70, 276, 282, 283, 295, 300)
    private val FACE_LIP_INDICES = intArrayOf(0, 13, 14, 17, 37, 39, 40, 61, 80, 81, 82, 178, 181, 185, 191, 267, 269, 270, 291, 310, 311, 312, 318, 402, 405, 409, 415)
    private val FACE_INDICES = FACE_EYEBROW_INDICES + FACE_LIP_INDICES // 10 + 27 = 37 indices
    
    // Feature dimensions (FACE_DIM is not const because it depends on FACE_INDICES.size which is runtime)
    private val FACE_DIM = FACE_INDICES.size * COMPONENTS_PER_POINT // 111
    
    /**
     * Process HolisticLandmarkerResult and return ByteBuffer for LSTM model.
     * Returns null if buffer is not full yet (needs 30 frames).
     * 
     * @param result Holistic landmarker result containing hands and face
     * @param timestamp Timestamp of the frame
     * @return ByteBuffer of size [1, 30, 237] (28,440 bytes) or null if buffer not full
     */
    fun process(result: HolisticLandmarkerResult, timestamp: Long): ByteBuffer? {
        // Extract 237 features from this frame
        var frameFeatures = extractFeatures(result)
        
        // Apply normalization before buffering (scale each frame's 237 features)
        if (featureScaler != null) {
            try {
                frameFeatures = featureScaler.scale(frameFeatures)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error scaling features, using unscaled features", e)
            }
        } else {
            Log.w(TAG, "⚠️ FeatureScaler not provided - features will not be normalized")
        }
        
        // Add to buffer (remove oldest if full)
        if (frameBuffer.size >= BUFFER_SIZE) {
            frameBuffer.removeFirst()
        }
        frameBuffer.addLast(frameFeatures)
        
        // Return null if buffer not full yet
        if (frameBuffer.size < BUFFER_SIZE) {
            Log.v(TAG, "Buffer not full: ${frameBuffer.size}/$BUFFER_SIZE frames")
            return null
        }
        
        // Buffer is full - create ByteBuffer for TFLite
        // Size: 1 * 30 * 237 * 4 bytes = 28,440 bytes
        val byteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * FEATURES_PER_FRAME * 4)
            .order(ByteOrder.nativeOrder())
        
        // Write all frames in row-major order: [frame0_237_features, frame1_237_features, ..., frame29_237_features]
        for (frame in frameBuffer) {
            for (feature in frame) {
                byteBuffer.putFloat(feature)
            }
        }
        
        byteBuffer.rewind()
        Log.d(TAG, "Buffer full: returning ByteBuffer of size ${byteBuffer.capacity()} bytes (${frameBuffer.size} frames × $FEATURES_PER_FRAME features)")
        
        return byteBuffer
    }
    
    /**
     * Extract 237 features from HolisticLandmarkerResult.
     * Order: [Left Hand (63), Right Hand (63), Face (111)]
     */
    private fun extractFeatures(result: HolisticLandmarkerResult): FloatArray {
        val features = FloatArray(FEATURES_PER_FRAME) // Initialize to zeros
        
        // Extract left hand (indices 0-62)
        val leftHandLandmarks = result.leftHandLandmarks()
        if (leftHandLandmarks != null && leftHandLandmarks.isNotEmpty()) {
            fillHandFeatures(features, 0, leftHandLandmarks, "left")
        }
        
        // Extract right hand (indices 63-125)
        val rightHandLandmarks = result.rightHandLandmarks()
        if (rightHandLandmarks != null && rightHandLandmarks.isNotEmpty()) {
            fillHandFeatures(features, ONE_HAND_DIM, rightHandLandmarks, "right")
        }
        
        // Extract face (indices 126-236)
        val faceLandmarks = result.faceLandmarks()
        if (faceLandmarks != null && faceLandmarks.isNotEmpty()) {
            fillFaceFeatures(features, ONE_HAND_DIM * 2, faceLandmarks)
        }
        
        return features
    }
    
    /**
     * Fill hand features into the feature array.
     */
    private fun fillHandFeatures(
        target: FloatArray,
        offset: Int,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        handLabel: String
    ) {
        var index = offset
        val limit = minOf(landmarks.size, LANDMARK_COUNT)
        
        repeat(limit) { position ->
            val landmark = landmarks[position]
            target[index++] = landmark.x()
            target[index++] = landmark.y()
            target[index++] = landmark.z()
        }
        
        if (landmarks.size < LANDMARK_COUNT) {
            Log.v(TAG, "fillHandFeatures($handLabel): Only got ${landmarks.size} landmarks, expected $LANDMARK_COUNT. Padding with zeros.")
        }
    }
    
    /**
     * Fill face features using specific MediaPipe indices.
     */
    private fun fillFaceFeatures(
        target: FloatArray,
        offset: Int,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ) {
        var index = offset
        
        // Extract only the 37 specific face indices (eyebrows + lips)
        for (faceIndex in FACE_INDICES) {
            if (faceIndex < landmarks.size) {
                val landmark = landmarks[faceIndex]
                target[index++] = landmark.x()
                target[index++] = landmark.y()
                target[index++] = landmark.z()
            } else {
                // Index out of bounds - pad with zeros
                target[index++] = 0f
                target[index++] = 0f
                target[index++] = 0f
            }
        }
        
        Log.v(TAG, "fillFaceFeatures: Extracted ${FACE_INDICES.size} face landmarks from ${landmarks.size} total landmarks")
    }
    
    /**
     * Clear the frame buffer (useful for resetting state).
     */
    fun clearBuffer() {
        frameBuffer.clear()
        Log.d(TAG, "Frame buffer cleared")
    }
    
    /**
     * Get current buffer size.
     */
    fun getBufferSize(): Int = frameBuffer.size
    
    // ========== DEPRECATED METHODS (for backward compatibility) ==========
    
    /**
     * @deprecated Use process() method instead. This method is kept for backward compatibility.
     */
    @Deprecated("Use process() method for LSTM model support", ReplaceWith("process(result, timestamp)"))
    fun toFeatureVector(
        left: List<Point3>?,
        right: List<Point3>?,
        twoHands: Boolean, // Deprecated: kept for API compatibility but ignored - always returns 126 dims
    ): FloatArray {
        // DEBUG: Log input parameters
        Log.v(TAG, "toFeatureVector: twoHands=$twoHands (ignored), left=${left?.size ?: 0}, right=${right?.size ?: 0}")
        
        // ALWAYS return 126 dimensions (TWO_HAND_DIM) to match old model input shape [1, 126]
        // Missing hands are automatically zero-padded: fill() returns early if points is null, leaving zeros
        // FloatArray initializes to 0.0f by default, so null hands result in zero-filled slots
        return FloatArray(TWO_HAND_DIM).also { output ->
            fillLegacy(output, 0, left, "left")      // First 63: left hand (or zeros if null)
            fillLegacy(output, HALF_DIM, right, "right") // Last 63: right hand (or zeros if null)
            
            // DEBUG: Log filled result
            val leftFilled = output.slice(0 until HALF_DIM).count { it != 0f }
            val rightFilled = output.slice(HALF_DIM until TWO_HAND_DIM).count { it != 0f }
            Log.d(TAG, "Feature vector: totalSize=$TWO_HAND_DIM, leftFilled=$leftFilled, rightFilled=$rightFilled")
        }
    }
    
    private fun fillLegacy(target: FloatArray, offset: Int, points: List<Point3>?, handLabel: String) {
        if (points == null) {
            Log.v(TAG, "fillLegacy($handLabel): points is null, leaving zeros at offset $offset")
            return
        }
        
        var index = offset
        val limit = minOf(points.size, LANDMARK_COUNT)
        
        // DEBUG: Log landmark range
        if (points.isNotEmpty()) {
            val firstPoint = points[0]
            val lastPoint = points[minOf(points.size - 1, limit - 1)]
            Log.v(TAG, "fillLegacy($handLabel): processing $limit landmarks, " +
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
            Log.w(TAG, "fillLegacy($handLabel): Only got ${points.size} landmarks, expected $LANDMARK_COUNT. Padding with zeros.")
        }
        
        Log.v(TAG, "fillLegacy($handLabel): Filled ${limit * COMPONENTS_PER_POINT} values starting at offset $offset")
    }
    
    // ========== COMPANION OBJECT (for backward compatibility) ==========
    
    companion object {
        // Feature dimensions (compile-time constants)
        // Note: Not private so they can be accessed from instance methods
        const val LANDMARK_COUNT = 21
        const val COMPONENTS_PER_POINT = 3
        const val ONE_HAND_DIM = LANDMARK_COUNT * COMPONENTS_PER_POINT // 63
        const val TWO_HAND_DIM = ONE_HAND_DIM * 2
        const val HALF_DIM = ONE_HAND_DIM
        /**
         * @deprecated Use instance method toFeatureVector() or process() method instead.
         * This static method is kept for backward compatibility (legacy code may still reference it).
         */
        @Deprecated("Use instance method or process() for LSTM model support", ReplaceWith("LandmarkFeatureExtractor().toFeatureVector(left, right, twoHands)"))
        fun toFeatureVector(
            left: List<Point3>?,
            right: List<Point3>?,
            twoHands: Boolean
        ): FloatArray {
            // Create temporary instance for backward compatibility
            val extractor = LandmarkFeatureExtractor()
            return extractor.toFeatureVector(left, right, twoHands)
        }
    }
}
