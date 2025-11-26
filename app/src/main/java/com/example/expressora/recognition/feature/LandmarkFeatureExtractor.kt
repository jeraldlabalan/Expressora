package com.example.expressora.recognition.feature

import android.util.Log
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.min

class LandmarkFeatureExtractor(
    private val featureScaler: FeatureScaler? = null
) {
    data class Point3(val x: Float, val y: Float, val z: Float)
    private val TAG = "LandmarkFeatureExtractor"

    // Frame buffer for LSTM model (30 frames)
    private val frameBuffer = ArrayDeque<FloatArray>(30)
    private val BUFFER_SIZE = 30
    private val FEATURES_PER_FRAME = 237 // Left Hand (63) + Right Hand (63) + Face (111)
    
    // CRITICAL: Minimum number of frames with valid hand data required for inference
    // If too many frames are missing (all -10.0), the model collapses and outputs random predictions
    // Training data typically has at least one hand visible in most frames
    private val MIN_VALID_FRAMES = 20 // At least 20/30 frames must have valid hand data

    // HARDCODED INDICES (Matches build_unified_dataset.py EXACTLY)
    private val EYEBROW_INDICES = listOf(46, 52, 53, 65, 70, 276, 282, 283, 295, 300)
    private val LIP_INDICES = listOf(0, 13, 14, 17, 37, 39, 40, 61, 80, 81, 82, 178, 181, 185, 191, 267, 269, 270, 291, 310, 311, 312, 318, 402, 405, 409, 415)
    private val FACE_INDICES = (EYEBROW_INDICES + LIP_INDICES).sorted() // 37 Points

    // Dimensions
    private val ONE_HAND_DIM = 63 // 21 * 3

    fun process(result: HolisticLandmarkerResult, timestamp: Long): ByteBuffer? {
        // 1. Extract
        var frameFeatures = extractFeatures(result)

        // 2. Scale (Critical Step)
        if (featureScaler != null) {
            frameFeatures = featureScaler.scale(frameFeatures)
        } else {
            Log.e(TAG, "❌ FeatureScaler missing! Model will receive raw data and fail.")
        }

        // 3. Buffer
        // CRITICAL: Skip frames that are all sentinels to improve buffer quality
        // This reduces the number of sentinel values in the buffer, which improves model performance
        val bufferSizeBefore = frameBuffer.size
        val isFrameAllSentinel = isFrameAllSentinel(frameFeatures)
        val frameAdded: Boolean
        if (isFrameAllSentinel) {
            Log.e(TAG, "⏭️ Skipping frame: all sentinels (no valid landmarks detected)")
            // Still maintain buffer size, but don't add this frame
            if (frameBuffer.size >= BUFFER_SIZE) {
                frameBuffer.removeFirst()
            }
            frameAdded = false
            // Don't add this frame - it's all sentinels
        } else {
            if (frameBuffer.size >= BUFFER_SIZE) frameBuffer.removeFirst()
            frameBuffer.addLast(frameFeatures)
            frameAdded = true
        }
        val bufferSizeAfter = frameBuffer.size
        
        // DIAGNOSTIC: Log buffer status
        if (frameAdded) {
            Log.e(TAG, "📊 BUFFER STATUS: size=$bufferSizeAfter/$BUFFER_SIZE (was $bufferSizeBefore, added 1 frame)")
        } else {
            Log.e(TAG, "📊 BUFFER STATUS: size=$bufferSizeAfter/$BUFFER_SIZE (was $bufferSizeBefore, frame skipped)")
        }

        // 4. Check ready
        if (frameBuffer.size < BUFFER_SIZE) {
            Log.e(TAG, "⏸️ Buffer not full yet: $bufferSizeAfter/$BUFFER_SIZE frames. Waiting for more frames.")
            return null
        }

        // 5. CRITICAL: Check buffer quality before inference
        // Count frames with valid hand data (at least one hand section is not all -10.0)
        val validFramesCount = frameBuffer.count { frame ->
            // Check if at least one hand section (left or right) has valid data
            // A frame is valid if it has at least one hand (not all -10.0 in hand sections)
            val leftHandValid = !isSectionAllSentinel(frame, 0, ONE_HAND_DIM)
            val rightHandValid = !isSectionAllSentinel(frame, ONE_HAND_DIM, ONE_HAND_DIM * 2)
            leftHandValid || rightHandValid
        }
        
        // Count missing sections per frame
        val leftHandMissingCount = frameBuffer.count { isSectionAllSentinel(it, 0, ONE_HAND_DIM) }
        val rightHandMissingCount = frameBuffer.count { isSectionAllSentinel(it, ONE_HAND_DIM, ONE_HAND_DIM * 2) }
        val faceMissingCount = frameBuffer.count { isSectionAllSentinel(it, ONE_HAND_DIM * 2, FEATURES_PER_FRAME) }
        
        Log.e(TAG, "🔍 BUFFER QUALITY CHECK:")
        Log.e(TAG, "   Valid frames (with hand data): $validFramesCount/$BUFFER_SIZE (minimum required: $MIN_VALID_FRAMES)")
        Log.e(TAG, "   Missing sections: LeftHand=$leftHandMissingCount frames, RightHand=$rightHandMissingCount frames, Face=$faceMissingCount frames")
        
        if (validFramesCount < MIN_VALID_FRAMES) {
            Log.e(TAG, "❌ Buffer quality check FAILED: $validFramesCount/$BUFFER_SIZE frames have valid hand data (minimum: $MIN_VALID_FRAMES). Skipping inference to prevent model collapse.")
            return null
        }
        
        Log.e(TAG, "✅ Buffer quality check PASSED: $validFramesCount/$BUFFER_SIZE frames have valid hand data")

        // 6. Pack ByteBuffer (Float32)
        val byteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE * FEATURES_PER_FRAME * 4)
            .order(ByteOrder.nativeOrder())

        var frameIdx = 0
        for (frame in frameBuffer) {
            for (feature in frame) {
                byteBuffer.putFloat(feature)
            }
            // Log first and last frame statistics
            if (frameIdx == 0 || frameIdx == BUFFER_SIZE - 1) {
                val frameMin = frame.minOrNull() ?: 0f
                val frameMax = frame.maxOrNull() ?: 0f
                val frameMean = frame.average().toFloat()
                val sentinelCount = frame.count { it == -10.0f }
                val validCount = frame.size - sentinelCount
                Log.e(TAG, "   📊 Frame[$frameIdx]: range=[%.3f, %.3f], mean=%.3f, sentinels=$sentinelCount, valid=$validCount".format(frameMin, frameMax, frameMean))
            }
            frameIdx++
        }
        byteBuffer.rewind()
        
        Log.e(TAG, "✅ ByteBuffer created: capacity=${byteBuffer.capacity()} bytes (${BUFFER_SIZE} frames × $FEATURES_PER_FRAME features × 4 bytes)")
        return byteBuffer
    }

    private fun extractFeatures(result: HolisticLandmarkerResult): FloatArray {
        val features = FloatArray(FEATURES_PER_FRAME) // Zeros by default

        // Order MUST be: Left -> Right -> Face (Matches Python Script)

        // Left Hand (0-62)
        val leftHandLandmarks = result.leftHandLandmarks()
        val leftHandCount = if (leftHandLandmarks != null && leftHandLandmarks.isNotEmpty()) {
            fillHand(features, 0, leftHandLandmarks)
            leftHandLandmarks.size
        } else {
            Log.e(TAG, "   ⚠️ Left hand landmarks: null or empty")
            0
        }

        // Right Hand (63-125)
        val rightHandLandmarks = result.rightHandLandmarks()
        val rightHandCount = if (rightHandLandmarks != null && rightHandLandmarks.isNotEmpty()) {
            fillHand(features, ONE_HAND_DIM, rightHandLandmarks)
            rightHandLandmarks.size
        } else {
            Log.e(TAG, "   ⚠️ Right hand landmarks: null or empty")
            0
        }

        // Face (126-236)
        val faceLandmarks = result.faceLandmarks()
        val faceCount = if (faceLandmarks != null && faceLandmarks.isNotEmpty()) {
            fillFace(features, ONE_HAND_DIM * 2, faceLandmarks)
            FACE_INDICES.size  // Number of face indices we're extracting
        } else {
            Log.e(TAG, "   ⚠️ Face landmarks: null or empty")
            0
        }

        // DIAGNOSTIC: Log feature extraction summary
        val nonZeroCount = features.count { it != 0f }
        val zeroCount = features.size - nonZeroCount
        Log.e(TAG, "🔍 FEATURE EXTRACTION: LeftHand=$leftHandCount landmarks, RightHand=$rightHandCount landmarks, Face=$faceCount landmarks")
        Log.e(TAG, "   Feature array: total=$FEATURES_PER_FRAME, non-zero=$nonZeroCount, zero=$zeroCount")
        Log.e(TAG, "   First 10 raw features: ${features.take(10).joinToString { "%.3f".format(it) }}")

        return features
    }

    private fun fillHand(target: FloatArray, offset: Int, landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Int {
        var idx = offset
        var zNormalizationCount = 0
        var zRawMin = Float.MAX_VALUE
        var zRawMax = Float.MIN_VALUE
        var zNormalizedMin = Float.MAX_VALUE
        var zNormalizedMax = Float.MIN_VALUE
        
        for (i in 0 until min(landmarks.size, 21)) {
            val lm = landmarks[i]
            val rawX = lm.x()  // Already [0, 1]
            val rawY = lm.y()  // Already [0, 1]
            val rawZ = lm.z()  // Can be negative
            
            // Normalize z to [0, 1] to match training pipeline
            // Clamp to [-1, 1] then normalize: (z + 1.0) / 2.0
            // This ensures all coordinates are in [0, 1] before FeatureScaler applies (value - 0.5) * 2.0
            val clampedZ = rawZ.coerceIn(-1.0f, 1.0f)
            val normalizedZ = (clampedZ + 1.0f) / 2.0f
            
            target[idx++] = rawX
            target[idx++] = rawY
            target[idx++] = normalizedZ
            
            // Track z-coordinate statistics (log first 3 landmarks only)
            if (i < 3) {
                zNormalizationCount++
                if (rawZ < zRawMin) zRawMin = rawZ
                if (rawZ > zRawMax) zRawMax = rawZ
                if (normalizedZ < zNormalizedMin) zNormalizedMin = normalizedZ
                if (normalizedZ > zNormalizedMax) zNormalizedMax = normalizedZ
                
                Log.e(TAG, "   🔍 Hand Landmark[$i] z-normalization: rawZ=%.3f, clampedZ=%.3f, normalizedZ=%.3f".format(rawZ, clampedZ, normalizedZ))
            }
        }
        
        // Log z-coordinate statistics summary
        if (zNormalizationCount > 0) {
            Log.e(TAG, "   📊 Hand z-coords (first 3): raw range=[%.3f, %.3f], normalized range=[%.3f, %.3f]".format(zRawMin, zRawMax, zNormalizedMin, zNormalizedMax))
        }
        
        return landmarks.size
    }

    private fun fillFace(target: FloatArray, offset: Int, landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Int {
        var idx = offset
        var facePointsExtracted = 0
        var zNormalizationCount = 0
        var zRawMin = Float.MAX_VALUE
        var zRawMax = Float.MIN_VALUE
        var zNormalizedMin = Float.MAX_VALUE
        var zNormalizedMax = Float.MIN_VALUE
        
        for (faceIndex in FACE_INDICES) {
            if (faceIndex < landmarks.size) {
                val lm = landmarks[faceIndex]
                val rawX = lm.x()  // Already [0, 1]
                val rawY = lm.y()  // Already [0, 1]
                val rawZ = lm.z()  // Can be negative
                
                // Normalize z to [0, 1] to match training pipeline
                // Clamp to [-1, 1] then normalize: (z + 1.0) / 2.0
                // This ensures all coordinates are in [0, 1] before FeatureScaler applies (value - 0.5) * 2.0
                val clampedZ = rawZ.coerceIn(-1.0f, 1.0f)
                val normalizedZ = (clampedZ + 1.0f) / 2.0f
                
                target[idx++] = rawX
                target[idx++] = rawY
                target[idx++] = normalizedZ
                facePointsExtracted++
                
                // Track z-coordinate statistics (log first 3 face points only)
                if (zNormalizationCount < 3) {
                    zNormalizationCount++
                    if (rawZ < zRawMin) zRawMin = rawZ
                    if (rawZ > zRawMax) zRawMax = rawZ
                    if (normalizedZ < zNormalizedMin) zNormalizedMin = normalizedZ
                    if (normalizedZ > zNormalizedMax) zNormalizedMax = normalizedZ
                    
                    Log.e(TAG, "   🔍 Face Landmark[$faceIndex] z-normalization: rawZ=%.3f, clampedZ=%.3f, normalizedZ=%.3f".format(rawZ, clampedZ, normalizedZ))
                }
            } else {
                idx += 3 // Pad 0 if index missing
            }
        }
        
        // Log z-coordinate statistics summary
        if (zNormalizationCount > 0) {
            Log.e(TAG, "   📊 Face z-coords (first 3): raw range=[%.3f, %.3f], normalized range=[%.3f, %.3f]".format(zRawMin, zRawMax, zNormalizedMin, zNormalizedMax))
        }
        
        return facePointsExtracted
    }

    fun clearBuffer() { frameBuffer.clear() }
    
    fun getCurrentBufferSize(): Int = frameBuffer.size
    
    /**
     * Check if an entire section is all sentinel values (-10.0).
     * Used to determine if a frame has valid hand data.
     */
    private fun isSectionAllSentinel(frame: FloatArray, start: Int, end: Int): Boolean {
        if (start < 0 || end > frame.size || start >= end) {
            return false
        }
        for (i in start until end) {
            if (frame[i] != -10.0f) {
                return false
            }
        }
        return true
    }
    
    /**
     * Check if an entire frame is all sentinel values (-10.0).
     * Used to skip frames with no valid landmarks to improve buffer quality.
     */
    private fun isFrameAllSentinel(frame: FloatArray): Boolean {
        return isSectionAllSentinel(frame, 0, frame.size)
    }
}