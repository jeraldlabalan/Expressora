package com.example.expressora.recognition.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarker
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult
import java.util.concurrent.atomic.AtomicInteger
import org.tensorflow.lite.gpu.CompatibilityList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class HolisticLandmarkerEngine private constructor(
    private val context: Context,
    landmarker: HolisticLandmarker,
    delegateUsed: DelegateType,
    runningMode: RunningMode
) {
    companion object {
        private const val TAG = "HolisticLandmarkerEngine"
        private const val MODEL_ASSET_PATH = "holistic_landmarker.task"
        
        // Debug counters
        val framesProcessed = AtomicInteger(0)
        val resultsReceived = AtomicInteger(0)
        val landmarksDetected = AtomicInteger(0)
        
        // Pose Gate constants
        const val POSE_WRIST_INDEX_RIGHT = 16  // Right wrist in pose landmarks (33 total)
        const val POSE_WRIST_INDEX_LEFT = 15    // Left wrist in pose landmarks
        const val POSE_WRIST_MIN_CONFIDENCE = 0.6f  // Minimum pose wrist confidence to trust hand detection
        
        // Hysteresis thresholds
        const val CONFIDENCE_TO_FIND = 0.7f  // High confidence required to initially detect hand
        const val CONFIDENCE_TO_KEEP = 0.4f  // Lower confidence to keep tracking once detected
        const val FRAMES_TO_FIND = 2  // Need 2 consistent frames to start tracking
        const val FRAMES_TO_KEEP = 1  // Need 1 consistent frame to keep tracking
        
        /**
         * Suspend factory function that performs all heavy initialization work off the main thread.
         * This makes the threading requirement explicit in the function signature.
         */
        suspend fun create(
            context: Context,
            onResult: ((HolisticLandmarkerResult) -> Unit)? = null,
            onError: ((Throwable) -> Unit)? = null
        ): HolisticLandmarkerEngine = withContext(Dispatchers.IO) {
            Log.i(TAG, "Initializing HolisticLandmarkerEngine on background thread...")
            
            // Step 1: Verify asset exists (I/O operation)
            yield() // Allow other coroutines to run
            val assetExists = runCatching {
                context.assets.open(MODEL_ASSET_PATH).use { true }
            }.getOrElse { false }
            
            if (!assetExists) {
                val errorMsg = "❌ CRITICAL: $MODEL_ASSET_PATH not found in assets! " +
                        "Please ensure the file exists in app/src/main/assets/. " +
                        "Run './gradlew downloadHolisticLandmarker' to download it."
                Log.e(TAG, errorMsg)
                throw IllegalStateException(errorMsg)
            } else {
                Log.i(TAG, "✓ Asset $MODEL_ASSET_PATH found")
            }
            
            // Step 2: Try GPU first with exception-based fallback (Gold Standard for compatibility)
            yield() // Allow other coroutines to run
            // Use LIVE_STREAM mode for all devices (CameraX handles device-specific quirks)
            val runningMode = RunningMode.LIVE_STREAM
            
            // Declare engineRef outside try-catch so listeners can access it after engine creation
            var engineRef: HolisticLandmarkerEngine? = null
            
            // Try GPU first, fallback to CPU on exception
            var delegateType: DelegateType = DelegateType.CPU
            var landmarker: HolisticLandmarker? = null
            
            // Attempt GPU delegate first
            // MediaPipe auto-selects GPU if available, so we try creation first
            // If it fails (e.g., GPU incompatible), we'll catch and fallback to CPU
            try {
                Log.i(TAG, "🎮 Attempting initialization (MediaPipe will auto-select GPU if available)...")
                val gpuBaseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build()
                
                val gpuOptionsBuilder = HolisticLandmarker.HolisticLandmarkerOptions.builder()
                    .setBaseOptions(gpuBaseOptions)
                    .setRunningMode(runningMode)
                    .setMinHandLandmarksConfidence(0.6f)
                    .setMinFaceDetectionConfidence(0.65f)
                    .setMinPoseDetectionConfidence(0.70f)
                
                // Set listeners for LIVE_STREAM mode (will capture engineRef)
                if (runningMode == RunningMode.LIVE_STREAM) {
                    gpuOptionsBuilder
                        .setResultListener { result, _ ->
                            engineRef?.handleResult(result)
                        }
                        .setErrorListener { error ->
                            Log.e(TAG, "❌ MediaPipe error: ${error.message}", error)
                            engineRef?.onError?.invoke(error)
                        }
                } else {
                    gpuOptionsBuilder.setErrorListener { error ->
                        Log.e(TAG, "❌ MediaPipe error (video mode): ${error.message}", error)
                        engineRef?.onError?.invoke(error)
                    }
                }
                
                val gpuOptions = gpuOptionsBuilder.build()
                yield() // Allow other coroutines to run before heavy operation
                landmarker = HolisticLandmarker.createFromOptions(context, gpuOptions)
                delegateType = DelegateType.GPU
                Log.i(TAG, "✅ GPU delegate initialized successfully")
            } catch (e: Exception) {
                // GPU initialization failed - log warning and fallback to CPU
                Log.w(TAG, "⚠️ GPU delegate initialization failed: ${e.message}. Falling back to CPU delegate.", e)
                Log.w(TAG, "📱 Device info: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                
                // Fallback to CPU delegate
                // Since GPU failed, create with default options (MediaPipe will use CPU)
                try {
                    val cpuBaseOptions = BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET_PATH)
                        .build()
                    
                    val cpuOptionsBuilder = HolisticLandmarker.HolisticLandmarkerOptions.builder()
                        .setBaseOptions(cpuBaseOptions)
                        .setRunningMode(runningMode)
                        .setMinHandLandmarksConfidence(0.6f)
                        .setMinFaceDetectionConfidence(0.65f)
                        .setMinPoseDetectionConfidence(0.70f)
                    
                    // Set listeners for LIVE_STREAM mode (will capture engineRef from outer scope)
                    if (runningMode == RunningMode.LIVE_STREAM) {
                        cpuOptionsBuilder
                            .setResultListener { result, _ ->
                                engineRef?.handleResult(result)
                            }
                            .setErrorListener { error ->
                                Log.e(TAG, "❌ MediaPipe error: ${error.message}", error)
                                engineRef?.onError?.invoke(error)
                            }
                    } else {
                        cpuOptionsBuilder.setErrorListener { error ->
                            Log.e(TAG, "❌ MediaPipe error (video mode): ${error.message}", error)
                            engineRef?.onError?.invoke(error)
                        }
                    }
                    
                    val cpuOptions = cpuOptionsBuilder.build()
                    yield() // Allow other coroutines to run before heavy operation
                    landmarker = HolisticLandmarker.createFromOptions(context, cpuOptions)
                    delegateType = DelegateType.CPU
                    Log.i(TAG, "✅ CPU delegate initialized successfully (GPU fallback)")
                } catch (cpuException: Exception) {
                    val errorMsg = "Failed to create HolisticLandmarker with CPU delegate: ${cpuException.message}"
                    Log.e(TAG, "❌ $errorMsg", cpuException)
                    throw IllegalStateException(errorMsg, cpuException)
                }
            }
            
            val finalLandmarker = landmarker ?: throw IllegalStateException("Landmarker initialization failed")
            
            Log.i(TAG, "✓ HolisticLandmarker initialized successfully with delegate=$delegateType, runningMode=$runningMode")
            
            // Step 3: Create instance and set callbacks
            val engine = HolisticLandmarkerEngine(context, finalLandmarker, delegateType, runningMode)
            engine.onResult = onResult
            engine.onError = onError
            
            // Set engineRef so listeners can call handleResult() (listeners capture this reference)
            engineRef = engine
            
            engine
        }
        
    }

    var onResult: ((HolisticLandmarkerResult) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    
    // Motion detection state
    private var previousHandLandmarks: List<NormalizedLandmark>? = null
    private var framesWithoutMotion = 0
    
    // Detect-track cadence state
    private var frameCounter = 0
    private var detectCadence = PerformanceConfig.HAND_DETECT_EVERY_N
    private var lastLandmarksDetected = 0
    private var trackingLostFrames = 0
    private var consecutiveTrackingLosses = 0
    private var forceDetectNext = false
    
    // Smoothing filter for landmarks (simple moving average)
    private val landmarkHistory = mutableListOf<List<NormalizedLandmark>>()
    private val smoothingWindowSize = PerformanceConfig.HAND_SMOOTHING_WINDOW
    
    // Dynamic Hysteresis (Keep-Alive) state
    private var isTrackingHand = false  // True if we're currently tracking a hand
    private var trackingConsistencyFrames = 0  // Number of consistent frames while tracking
    private var lastHandLabel: String? = null  // Last detected hand label for consistency check

    private val landmarker: HolisticLandmarker = landmarker
    private val delegateUsed: DelegateType = delegateUsed
    private val runningMode: RunningMode = runningMode

    enum class DelegateType {
        GPU,
        CPU
    }

    fun getDelegateType(): DelegateType = delegateUsed
    fun getRunningMode(): RunningMode = runningMode

    fun detectAsync(frame: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()) {
        try {
            // Thread verification: ensure we're not on main thread
            val threadName = Thread.currentThread().name
            if (threadName.contains("main", ignoreCase = true)) {
                Log.w(TAG, "⚠️ detectAsync running on main thread! Thread: $threadName")
            } else if (PerformanceConfig.VERBOSE_LOGGING && framesProcessed.get() % 60 == 0) {
                Log.d(TAG, "✅ detectAsync running on background thread: $threadName")
            }
            
            // Set background thread priority for better performance on low-end devices
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            
            framesProcessed.incrementAndGet()
            frameCounter++
            
            // Detect-track cadence: run full detection every N frames, track in between
            val shouldRunDetection = forceDetectNext || (frameCounter % detectCadence == 0)
            forceDetectNext = false
            
            val mpImage = BitmapImageBuilder(frame).build()
            when (runningMode) {
                RunningMode.LIVE_STREAM -> landmarker.detectAsync(mpImage, timestampMs)
                RunningMode.VIDEO -> {
                    val result = landmarker.detectForVideo(mpImage, timestampMs)
                    handleResult(result)
                }
                RunningMode.IMAGE -> {
                    val result = landmarker.detect(mpImage)
                    handleResult(result)
                }
            }
            
            // Log progress less frequently to reduce overhead
            if (PerformanceConfig.VERBOSE_LOGGING && framesProcessed.get() % 30 == 0) {
                Log.d(TAG, "Progress: Frames=${framesProcessed.get()}, Results=${resultsReceived.get()}, LandmarksFound=${landmarksDetected.get()}, DetectCadence=$detectCadence")
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "❌ detectAsync failed: ${throwable.message}", throwable)
            onError?.invoke(throwable)
        }
    }
    
    /**
     * Tracking drift watchdog: detect repeated landmark loss and temporarily increase detect frequency.
     */
    private fun handleTrackingDrift(landmarksCurrentlyDetected: Int) {
        if (landmarksCurrentlyDetected == 0 && lastLandmarksDetected > 0) {
            // Landmark tracking lost
            trackingLostFrames++
            if (trackingLostFrames > PerformanceConfig.HAND_TRACKING_DRIFT_THRESHOLD) {
                consecutiveTrackingLosses++
                trackingLostFrames = 0
                
                if (consecutiveTrackingLosses >= 3) {
                    // Trigger drift watchdog: temporarily lower cadence (more frequent detection)
                    detectCadence = maxOf(1, detectCadence / 2)
                    forceDetectNext = true
                    Log.i(TAG, "Tracking drift detected: lowering detect cadence to $detectCadence")
                } else {
                    // Single loss: force immediate detect
                    forceDetectNext = true
                }
            }
        } else if (landmarksCurrentlyDetected > 0) {
            // Tracking stable: restore normal cadence
            if (detectCadence != PerformanceConfig.HAND_DETECT_EVERY_N) {
                detectCadence = PerformanceConfig.HAND_DETECT_EVERY_N
                Log.i(TAG, "Tracking stable: restored detect cadence to $detectCadence")
            }
            trackingLostFrames = 0
            consecutiveTrackingLosses = 0
        }
        
        lastLandmarksDetected = landmarksCurrentlyDetected
    }

    private fun handleResult(result: HolisticLandmarkerResult) {
        resultsReceived.incrementAndGet()
        
        // Count detected landmarks (hands, face, pose)
        // MediaPipe Holistic uses leftHandLandmarks() and rightHandLandmarks()
        val leftHand = result.leftHandLandmarks()
        val rightHand = result.rightHandLandmarks()
        val numHands = (if (leftHand != null && leftHand.isNotEmpty()) 1 else 0) + 
                      (if (rightHand != null && rightHand.isNotEmpty()) 1 else 0)
        val hasFace = result.faceLandmarks() != null && result.faceLandmarks()!!.isNotEmpty()
        val poseLandmarks = result.poseLandmarks()
        val hasPose = poseLandmarks != null && poseLandmarks.isNotEmpty()
        val totalLandmarks = (if (numHands > 0) 1 else 0) + (if (hasFace) 1 else 0) + (if (hasPose) 1 else 0)

        // Tracking drift watchdog: monitor landmark loss and adjust cadence
        handleTrackingDrift(totalLandmarks)

        // POSE GATE: Validate hand detections against pose wrist confidence
        // This kills "floating ghost hands" instantly without multi-frame delays
        var poseGatePassed = true
        var wristConfidence = 0.0f
        
        if (numHands > 0 && poseLandmarks != null && poseLandmarks.isNotEmpty()) {
            // Check right wrist (index 16) and left wrist (index 15) confidence
            // MediaPipe pose landmarks: 33 points, each with x, y, z, visibility, presence
            // For normalized landmarks, we check if wrist position is valid (non-zero)
            val rightWrist = if (POSE_WRIST_INDEX_RIGHT < poseLandmarks.size) poseLandmarks[POSE_WRIST_INDEX_RIGHT] else null
            val leftWrist = if (POSE_WRIST_INDEX_LEFT < poseLandmarks.size) poseLandmarks[POSE_WRIST_INDEX_LEFT] else null
            
            // Calculate confidence based on visibility/presence (if available) or position validity
            val rightWristConfidence = if (rightWrist != null) {
                // Check if wrist is detected (non-zero position indicates detection)
                if (Math.abs(rightWrist.x()) > 0.001f || Math.abs(rightWrist.y()) > 0.001f) {
                    0.9f  // High confidence if detected
                } else {
                    0.0f  // Low confidence if not detected
                }
            } else {
                0.0f
            }
            
            val leftWristConfidence = if (leftWrist != null) {
                if (Math.abs(leftWrist.x()) > 0.001f || Math.abs(leftWrist.y()) > 0.001f) {
                    0.9f
                } else {
                    0.0f
                }
            } else {
                0.0f
            }
            
            wristConfidence = maxOf(rightWristConfidence, leftWristConfidence)
            
            // If pose wrist confidence is too low, reject hand detection (ghost hand)
            if (wristConfidence < POSE_WRIST_MIN_CONFIDENCE) {
                poseGatePassed = false
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    Log.v(TAG, "🚫 Pose Gate failed: wrist confidence ${wristConfidence} < ${POSE_WRIST_MIN_CONFIDENCE} - rejecting hand detection")
                }
                // Reset tracking state on pose gate failure
                isTrackingHand = false
                trackingConsistencyFrames = 0
                lastHandLabel = null
            } else {
                if (PerformanceConfig.VERBOSE_LOGGING && frameCounter % 30 == 0) {
                    Log.v(TAG, "✅ Pose Gate passed: wrist confidence ${wristConfidence} >= ${POSE_WRIST_MIN_CONFIDENCE}")
                }
            }
        }

        if (totalLandmarks > 0 && poseGatePassed) {
            landmarksDetected.incrementAndGet()

            // DYNAMIC HYSTERESIS (Keep-Alive): Different thresholds for finding vs keeping hands
            // To FIND a hand: Require high confidence (0.7) + 2 frames consistency
            // To KEEP a hand: Drop required confidence to 0.4 + 1 frame consistency
            // Use pose wrist confidence (already calculated in Pose Gate) as proxy for hand detection confidence
            
            val requiredConsistencyFrames = if (isTrackingHand) {
                FRAMES_TO_KEEP  // Only need 1 frame to keep
            } else {
                FRAMES_TO_FIND  // Need 2 frames to start
            }
            
            val requiredConfidence = if (isTrackingHand) {
                CONFIDENCE_TO_KEEP  // Lower threshold to keep tracking (0.4)
            } else {
                CONFIDENCE_TO_FIND  // Higher threshold to start tracking (0.7)
            }
            
            val shouldProcess = wristConfidence >= requiredConfidence && numHands > 0
            
            if (shouldProcess) {
                trackingConsistencyFrames++
                if (trackingConsistencyFrames >= requiredConsistencyFrames) {
                    // Tracking established or maintained
                    if (!isTrackingHand) {
                        isTrackingHand = true
                        if (PerformanceConfig.VERBOSE_LOGGING) {
                            Log.d(TAG, "🎯 Hand tracking started (wrist confidence: $wristConfidence >= $CONFIDENCE_TO_FIND, frames: $trackingConsistencyFrames)")
                        }
                    }
                }
            } else {
                // Confidence dropped below threshold or no hands
                if (isTrackingHand) {
                    // Still tracking but confidence dropped - reset consistency counter
                    trackingConsistencyFrames = 0
                    if (PerformanceConfig.VERBOSE_LOGGING) {
                        Log.v(TAG, "⚠️ Tracking confidence dropped (wrist: $wristConfidence < $CONFIDENCE_TO_KEEP or hands: $numHands), resetting consistency")
                    }
                } else {
                    trackingConsistencyFrames = 0
                }
            }

            // Motion detection: skip inference if landmarks are stationary
            val shouldInvoke = if (PerformanceConfig.ENABLE_MOTION_DETECTION && isTrackingHand) {
                // Use left hand for motion detection (or right if left is not available)
                val currentHandLandmarks = leftHand?.toList() ?: rightHand?.toList()
                val hasMotion = detectMotion(currentHandLandmarks)

                if (!hasMotion) {
                    framesWithoutMotion++
                    // Skip inference after N frames without motion
                    if (framesWithoutMotion <= PerformanceConfig.SKIP_FRAMES_WHEN_STILL) {
                        if (PerformanceConfig.VERBOSE_LOGGING) {
                            Log.v(TAG, "Landmarks stationary, skipping inference ($framesWithoutMotion/${PerformanceConfig.SKIP_FRAMES_WHEN_STILL})")
                        }
                        false
                    } else {
                        // Still process occasionally even if no motion
                        true
                    }
                } else {
                    framesWithoutMotion = 0
                    true
                }
            } else if (isTrackingHand) {
                // Tracking but motion detection disabled - process
                true
            } else {
                // Not tracking yet - don't process
                false
            }

            if (shouldInvoke) {
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    Log.d(TAG, "✓ Result callback: hands=$numHands, face=$hasFace, pose=$hasPose, tracking=$isTrackingHand")
                }
                onResult?.invoke(result)
            }

            // Update previous landmarks for next comparison
            if (PerformanceConfig.ENABLE_MOTION_DETECTION) {
                previousHandLandmarks = leftHand?.toList() ?: rightHand?.toList()
            }
        } else {
            // No landmarks or pose gate failed - reset tracking
            if (!poseGatePassed || totalLandmarks == 0) {
                isTrackingHand = false
                trackingConsistencyFrames = 0
                lastHandLabel = null
            }
            previousHandLandmarks = null
            framesWithoutMotion = 0
            if (PerformanceConfig.VERBOSE_LOGGING) {
                Log.v(TAG, "Result callback: 0 landmarks detected or pose gate failed")
            }
        }
    }

    /**
     * Detect motion by comparing current landmarks with previous frame.
     * Returns true if significant motion detected, false if stationary.
     */
    private fun detectMotion(currentLandmarks: List<NormalizedLandmark>?): Boolean {
        if (currentLandmarks == null || previousHandLandmarks == null) {
            return true // Assume motion on first detection
        }
        
        if (currentLandmarks.size != previousHandLandmarks!!.size) {
            return true // Different landmark count = motion
        }
        
        // Calculate average Euclidean distance between landmark positions
        var totalDistance = 0f
        val previous = previousHandLandmarks ?: return true
        for (i in currentLandmarks.indices) {
            val curr = currentLandmarks[i]
            val prev = previous[i]
            
            val dx = curr.x() - prev.x()
            val dy = curr.y() - prev.y()
            val dz = curr.z() - prev.z()
            
            val distance = kotlin.math.sqrt(((dx * dx) + (dy * dy) + (dz * dz)).toDouble()).toFloat()
            totalDistance += distance
        }
        
        val avgDistance = totalDistance / currentLandmarks.size
        val hasMotion = avgDistance >= PerformanceConfig.MOTION_THRESHOLD
        
        if (PerformanceConfig.VERBOSE_LOGGING && !hasMotion) {
            Log.v(TAG, "Motion: avgDist=$avgDistance, threshold=${PerformanceConfig.MOTION_THRESHOLD} -> STILL")
        }
        
        return hasMotion
    }
    
    fun close() {
        landmarker.close()
        Log.i(TAG, "HolisticLandmarker closed")
    }

    private fun selectDelegate(): DelegateType {
        val compatibilityList = runCatching { CompatibilityList() }.getOrNull()
        val gpuSupported = compatibilityList?.isDelegateSupportedOnThisDevice == true

        // Use GPU if supported, otherwise fallback to CPU
        // CameraX and MediaPipe handle device-specific quirks automatically
        return if (gpuSupported) {
            DelegateType.GPU
        } else {
            DelegateType.CPU
        }
    }
}

