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

class HolisticLandmarkerEngine(
    context: Context,
) {
    companion object {
        private const val TAG = "HolisticLandmarkerEngine"
        private const val MODEL_ASSET_PATH = "holistic_landmarker.task"
        
        // Debug counters
        val framesProcessed = AtomicInteger(0)
        val resultsReceived = AtomicInteger(0)
        val landmarksDetected = AtomicInteger(0)
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

    private val landmarker: HolisticLandmarker
    private val delegateUsed: DelegateType
    private val runningMode: RunningMode

    enum class DelegateType {
        GPU,
        CPU
    }

    init {
        Log.i(TAG, "Initializing HolisticLandmarkerEngine...")
        
        // Verify asset exists
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
        
        val delegateType = selectDelegate()
        delegateUsed = delegateType
        runningMode = selectRunningMode()

        // Build base options
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val optionsBuilder = HolisticLandmarker.HolisticLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(runningMode)
            .setMinHandLandmarksConfidence(0.6f)  // Lower threshold to capture all potential hands (server filters ghosts)
            .setMinFaceDetectionConfidence(0.65f)  // More lenient for face (only for tone detection)
            .setMinPoseDetectionConfidence(0.70f)  // Balanced threshold for pose
        if (runningMode == RunningMode.LIVE_STREAM) {
            optionsBuilder
                .setResultListener { result, _ ->
                    handleResult(result)
                }
                .setErrorListener { error ->
                    Log.e(TAG, "❌ MediaPipe error: ${error.message}", error)
                    onError?.invoke(error)
                }
        } else {
            optionsBuilder.setErrorListener { error ->
                Log.e(TAG, "❌ MediaPipe error (video mode): ${error.message}", error)
                onError?.invoke(error)
            }
        }
        val options = optionsBuilder.build()
        
        // Create landmarker with error handling
        try {
            landmarker = HolisticLandmarker.createFromOptions(context, options)
            Log.i(TAG, "✓ HolisticLandmarker initialized successfully with delegate=$delegateType, runningMode=$runningMode")
        } catch (e: Exception) {
            val errorMsg = "Failed to create HolisticLandmarker from $MODEL_ASSET_PATH: ${e.message}"
            Log.e(TAG, "❌ $errorMsg", e)
            throw IllegalStateException(errorMsg, e)
        }
    }

    fun getDelegateType(): DelegateType = delegateUsed
    fun getRunningMode(): RunningMode = runningMode

    fun detectAsync(frame: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()) {
        try {
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

    private fun selectRunningMode(): RunningMode {
        val isKnownLiveStreamCrashDevice =
            Build.MANUFACTURER.equals("vivo", ignoreCase = true) &&
            Build.MODEL.equals("V2318", ignoreCase = true)
        return if (isKnownLiveStreamCrashDevice) {
            RunningMode.VIDEO
        } else {
            RunningMode.LIVE_STREAM
        }
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
        val hasPose = result.poseLandmarks() != null && result.poseLandmarks()!!.isNotEmpty()
        val totalLandmarks = (if (numHands > 0) 1 else 0) + (if (hasFace) 1 else 0) + (if (hasPose) 1 else 0)

        // Tracking drift watchdog: monitor landmark loss and adjust cadence
        handleTrackingDrift(totalLandmarks)

        if (totalLandmarks > 0) {
            landmarksDetected.incrementAndGet()

            // Motion detection: skip inference if landmarks are stationary
            val shouldInvoke = if (PerformanceConfig.ENABLE_MOTION_DETECTION) {
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
            } else {
                true
            }

            if (shouldInvoke) {
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    Log.d(TAG, "✓ Result callback: hands=$numHands, face=$hasFace, pose=$hasPose")
                }
                onResult?.invoke(result)
            }

            // Update previous landmarks for next comparison
            if (PerformanceConfig.ENABLE_MOTION_DETECTION) {
                previousHandLandmarks = leftHand?.toList() ?: rightHand?.toList()
            }
        } else {
            previousHandLandmarks = null
            framesWithoutMotion = 0
            if (PerformanceConfig.VERBOSE_LOGGING) {
                Log.v(TAG, "Result callback: 0 landmarks detected")
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
        val isKnownBadGpuDevice =
            Build.MANUFACTURER.equals("vivo", ignoreCase = true)
                    || Build.BRAND.equals("vivo", ignoreCase = true)

        return if (gpuSupported && !isKnownBadGpuDevice) {
            DelegateType.GPU
        } else {
            DelegateType.CPU
        }
    }
}

