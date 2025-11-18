package com.example.expressora.recognition.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized performance configuration with feature-flagged presets.
 *
 * Settings are grouped by concern (Camera, MediaPipe, Frame Processing, TFLite,
 * Recognition, Adaptive, Diagnostics, Memory) and can be swapped at runtime via
 * [setMode] / [currentMode]. The default preset is [Mode.BALANCED].
 */
object PerformanceConfig {

    enum class Mode {
        ULTRA_LITE,
        LITE,
        BALANCED,
        ACCURACY
    }

    data class CameraSettings(
        val width: Int,
        val height: Int,
        val keepOnlyLatest: Boolean
    )

    data class FrameProcessingSettings(
        val baseFrameSkip: Int,
        val targetFps: Int,
        val adaptiveSkipEnabled: Boolean,
        val adaptiveSkipUpdateInterval: Int,
        val minFrameSkip: Int,
        val maxFrameSkip: Int,
        val jpegQuality: Int,
        val enableDownscaling: Boolean,
        val downscaleWidth: Int,
        val downscaleHeight: Int,
        val enableOpencvPreprocessing: Boolean = false
    )

    data class MediaPipeSettings(
        val numHands: Int,
        val handDetectionConfidence: Float,
        val handPresenceConfidence: Float,
        val handTrackingConfidence: Float,
        val minHandConfidenceFilter: Float,
        val handDetectionCadence: Int,
        val handSmoothingWindow: Int,
        val trackingDriftThreshold: Int,
        val enableMotionDetection: Boolean,
        val motionThreshold: Float,
        val skipFramesWhenStill: Int,
        val skipInferenceNoHands: Boolean,
        val singleHandMode: Boolean = false
    )

    data class FaceSettings(
        val defaultCadenceFrames: Int,
        val cadenceRange: IntRange,
        val handAbsentCooldownFrames: Int,
        val optionalBlendshapes: List<String>,
        val enableBlendshapesWhenAboveTargetFps: Boolean
    )

    data class TfliteSettings(
        val interpreterThreads: Int,
        val enableGpu: Boolean,
        val enableNnapi: Boolean,
        val enableXnnpack: Boolean,
        val delegateProbeOrder: List<String>
    )

    data class RecognitionSettings(
        val rollingBufferSize: Int,
        val minStableFrames: Int,
        val debounceFrames: Int,
        val confidenceThreshold: Float,
        val enableResultCaching: Boolean,
        val cacheDurationMs: Long
    )

    data class AdaptiveSettings(
        val targetFps: Int,
        val hysteresisPercent: Float,
        val evaluationWindowFrames: Int,
        val detectCadenceRange: IntRange,
        val faceCadenceRange: IntRange,
        val frameSkipRange: IntRange
    )

    data class DiagnosticsSettings(
        val verboseLogging: Boolean,
        val logFpsIntervalMs: Long,
        val logFrameProcessing: Boolean,
        val maxOverlayFps: Int
    )

    data class MemorySettings(
        val enableBitmapPooling: Boolean,
        val bitmapPoolSize: Int,
        val enableGcHints: Boolean
    )

    data class RoiSettings(
        val enabled: Boolean,
        val detectionCadence: Int,
        val cacheFrames: Int,
        val paddingMultiplier: Float,
        val minConfidence: Float
    )

    data class Preset(
        val label: String,
        val camera: CameraSettings,
        val frame: FrameProcessingSettings,
        val mediaPipe: MediaPipeSettings,
        val face: FaceSettings,
        val tflite: TfliteSettings,
        val recognition: RecognitionSettings,
        val adaptive: AdaptiveSettings,
        val diagnostics: DiagnosticsSettings,
        val memory: MemorySettings,
        val roi: RoiSettings
    )

    private val presets: Map<Mode, Preset> = mapOf(
        Mode.ULTRA_LITE to Preset(
            label = "Ultra Lite",
            camera = CameraSettings(width = 320, height = 240, keepOnlyLatest = true),
            frame = FrameProcessingSettings(
                baseFrameSkip = 5,
                targetFps = 12,
                adaptiveSkipEnabled = true,
                adaptiveSkipUpdateInterval = 60,
                minFrameSkip = 3,
                maxFrameSkip = 8,
                jpegQuality = 65,
                enableDownscaling = false,
                downscaleWidth = 160,
                downscaleHeight = 120,
                enableOpencvPreprocessing = false
            ),
            mediaPipe = MediaPipeSettings(
                numHands = 1,
                handDetectionConfidence = 0.70f,
                handPresenceConfidence = 0.70f,
                handTrackingConfidence = 0.70f,
                minHandConfidenceFilter = 0.75f,
                handDetectionCadence = 12,
                handSmoothingWindow = 2,
                trackingDriftThreshold = 6,
                enableMotionDetection = true,
                motionThreshold = 0.03f,
                skipFramesWhenStill = 8,
                skipInferenceNoHands = true,
                singleHandMode = true
            ),
            face = FaceSettings(
                defaultCadenceFrames = 12,
                cadenceRange = 10..15,
                handAbsentCooldownFrames = 120,
                optionalBlendshapes = listOf(),
                enableBlendshapesWhenAboveTargetFps = false
            ),
            tflite = TfliteSettings(
                interpreterThreads = 1,
                enableGpu = true,
                enableNnapi = true,
                enableXnnpack = true,
                delegateProbeOrder = listOf("GPU", "NNAPI", "CPU")
            ),
            recognition = RecognitionSettings(
                rollingBufferSize = 2,
                minStableFrames = 1,
                debounceFrames = 3,
                confidenceThreshold = 0.75f,
                enableResultCaching = false,
                cacheDurationMs = 100L
            ),
            adaptive = AdaptiveSettings(
                targetFps = 12,
                hysteresisPercent = 0.3f,
                evaluationWindowFrames = 48,
                detectCadenceRange = 10..15,
                faceCadenceRange = 10..18,
                frameSkipRange = 3..8
            ),
            diagnostics = DiagnosticsSettings(
                verboseLogging = false,
                logFpsIntervalMs = 6000L,
                logFrameProcessing = false,
                maxOverlayFps = 10
            ),
            memory = MemorySettings(
                enableBitmapPooling = true,
                bitmapPoolSize = 2,
                enableGcHints = true
            ),
            roi = RoiSettings(
                enabled = true,
                detectionCadence = 15,
                cacheFrames = 10,
                paddingMultiplier = 2.0f,
                minConfidence = 0.5f
            )
        ),
        Mode.LITE to Preset(
            label = "Lite",
            camera = CameraSettings(width = 480, height = 360, keepOnlyLatest = true),
            frame = FrameProcessingSettings(
                baseFrameSkip = 3,
                targetFps = 16,
                adaptiveSkipEnabled = true,
                adaptiveSkipUpdateInterval = 45,
                minFrameSkip = 2,
                maxFrameSkip = 6,
                jpegQuality = 70,
                enableDownscaling = false,
                downscaleWidth = 240,
                downscaleHeight = 180,
                enableOpencvPreprocessing = false
            ),
            mediaPipe = MediaPipeSettings(
                numHands = 2,
                handDetectionConfidence = 0.65f,
                handPresenceConfidence = 0.65f,
                handTrackingConfidence = 0.65f,
                minHandConfidenceFilter = 0.70f,
                handDetectionCadence = 8,
                handSmoothingWindow = 3,
                trackingDriftThreshold = 5,
                enableMotionDetection = true,
                motionThreshold = 0.025f,
                skipFramesWhenStill = 6,
                skipInferenceNoHands = true,
                singleHandMode = false
            ),
            face = FaceSettings(
                defaultCadenceFrames = 8,
                cadenceRange = 6..10,
                handAbsentCooldownFrames = 90,
                optionalBlendshapes = listOf("mouthOpen"),
                enableBlendshapesWhenAboveTargetFps = false
            ),
            tflite = TfliteSettings(
                interpreterThreads = 1,
                enableGpu = true,
                enableNnapi = true,
                enableXnnpack = true,
                delegateProbeOrder = listOf("GPU", "NNAPI", "CPU")
            ),
            recognition = RecognitionSettings(
                rollingBufferSize = 3,
                minStableFrames = 1,
                debounceFrames = 2,
                confidenceThreshold = 0.70f,
                enableResultCaching = false,
                cacheDurationMs = 100L
            ),
            adaptive = AdaptiveSettings(
                targetFps = 16,
                hysteresisPercent = 0.25f,
                evaluationWindowFrames = 36,
                detectCadenceRange = 6..10,
                faceCadenceRange = 6..12,
                frameSkipRange = 2..6
            ),
            diagnostics = DiagnosticsSettings(
                verboseLogging = false,
                logFpsIntervalMs = 5000L,
                logFrameProcessing = false,
                maxOverlayFps = 12
            ),
            memory = MemorySettings(
                enableBitmapPooling = true,
                bitmapPoolSize = 3,
                enableGcHints = true
            ),
            roi = RoiSettings(
                enabled = true,
                detectionCadence = 12,
                cacheFrames = 8,
                paddingMultiplier = 2.0f,
                minConfidence = 0.5f
            )
        ),
        Mode.BALANCED to Preset(
            label = "Balanced",
            camera = CameraSettings(width = 480, height = 360, keepOnlyLatest = true),
            frame = FrameProcessingSettings(
                baseFrameSkip = 2,
                targetFps = 18,
                adaptiveSkipEnabled = true,
                adaptiveSkipUpdateInterval = 30,
                minFrameSkip = 1,
                maxFrameSkip = 4,
                jpegQuality = 80,
                enableDownscaling = false,
                downscaleWidth = 240,
                downscaleHeight = 180
            ),
            mediaPipe = MediaPipeSettings(
                numHands = 2,
                handDetectionConfidence = 0.60f,
                handPresenceConfidence = 0.60f,
                handTrackingConfidence = 0.60f,
                minHandConfidenceFilter = 0.65f,
                handDetectionCadence = 5,
                handSmoothingWindow = 4,
                trackingDriftThreshold = 4,
                enableMotionDetection = true,
                motionThreshold = 0.02f,
                skipFramesWhenStill = 5,
                skipInferenceNoHands = true,
                singleHandMode = false
            ),
            face = FaceSettings(
                defaultCadenceFrames = 5,
                cadenceRange = 4..6,
                handAbsentCooldownFrames = 60,
                optionalBlendshapes = listOf("mouthOpen", "browInnerUp"),
                enableBlendshapesWhenAboveTargetFps = true
            ),
            tflite = TfliteSettings(
                interpreterThreads = 2,
                enableGpu = true,
                enableNnapi = true,
                enableXnnpack = true,
                delegateProbeOrder = listOf("GPU", "NNAPI", "CPU")
            ),
            recognition = RecognitionSettings(
                rollingBufferSize = 3,
                minStableFrames = 1,
                debounceFrames = 2,
                confidenceThreshold = 0.65f,
                enableResultCaching = false,
                cacheDurationMs = 100L
            ),
            adaptive = AdaptiveSettings(
                targetFps = 18,
                hysteresisPercent = 0.2f,
                evaluationWindowFrames = 30,
                detectCadenceRange = 4..8,
                faceCadenceRange = 4..8,
                frameSkipRange = 1..4
            ),
            diagnostics = DiagnosticsSettings(
                verboseLogging = false,
                logFpsIntervalMs = 5000L,
                logFrameProcessing = false,
                maxOverlayFps = 15
            ),
            memory = MemorySettings(
                enableBitmapPooling = true,
                bitmapPoolSize = 3,
                enableGcHints = true
            ),
            roi = RoiSettings(
                enabled = true,
                detectionCadence = 10,
                cacheFrames = 6,
                paddingMultiplier = 2.0f,
                minConfidence = 0.5f
            )
        ),
        Mode.ACCURACY to Preset(
            label = "Accuracy",
            camera = CameraSettings(width = 640, height = 480, keepOnlyLatest = true),
            frame = FrameProcessingSettings(
                baseFrameSkip = 1,
                targetFps = 20,
                adaptiveSkipEnabled = true,
                adaptiveSkipUpdateInterval = 24,
                minFrameSkip = 1,
                maxFrameSkip = 3,
                jpegQuality = 90,
                enableDownscaling = false,
                downscaleWidth = 320,
                downscaleHeight = 240,
                enableOpencvPreprocessing = false
            ),
            mediaPipe = MediaPipeSettings(
                numHands = 2,
                handDetectionConfidence = 0.55f,
                handPresenceConfidence = 0.55f,
                handTrackingConfidence = 0.55f,
                minHandConfidenceFilter = 0.60f,
                handDetectionCadence = 4,
                handSmoothingWindow = 5,
                trackingDriftThreshold = 3,
                enableMotionDetection = true,
                motionThreshold = 0.015f,
                skipFramesWhenStill = 4,
                skipInferenceNoHands = true,
                singleHandMode = false
            ),
            face = FaceSettings(
                defaultCadenceFrames = 4,
                cadenceRange = 3..5,
                handAbsentCooldownFrames = 45,
                optionalBlendshapes = listOf("mouthOpen", "browInnerUp", "cheekPuff"),
                enableBlendshapesWhenAboveTargetFps = true
            ),
            tflite = TfliteSettings(
                interpreterThreads = 2,
                enableGpu = true,
                enableNnapi = true,
                enableXnnpack = true,
                delegateProbeOrder = listOf("GPU", "NNAPI", "CPU")
            ),
            recognition = RecognitionSettings(
                rollingBufferSize = 3,
                minStableFrames = 1,
                debounceFrames = 2,
                confidenceThreshold = 0.60f,
                enableResultCaching = false,
                cacheDurationMs = 100L
            ),
            adaptive = AdaptiveSettings(
                targetFps = 20,
                hysteresisPercent = 0.15f,
                evaluationWindowFrames = 30,
                detectCadenceRange = 3..6,
                faceCadenceRange = 3..6,
                frameSkipRange = 1..3
            ),
            diagnostics = DiagnosticsSettings(
                verboseLogging = false,
                logFpsIntervalMs = 4000L,
                logFrameProcessing = false,
                maxOverlayFps = 15
            ),
            memory = MemorySettings(
                enableBitmapPooling = true,
                bitmapPoolSize = 3,
                enableGcHints = true
            ),
            roi = RoiSettings(
                enabled = false,
                detectionCadence = 8,
                cacheFrames = 5,
                paddingMultiplier = 2.0f,
                minConfidence = 0.5f
            )
        )
    )

    private val modeRef = AtomicReference(Mode.BALANCED)

    val currentMode: Mode
        get() = modeRef.get()

    val currentPreset: Preset
        get() = presets.getValue(currentMode)

    val availableModes: List<Mode>
        get() = presets.keys.sortedBy { it.ordinal }

    fun setMode(mode: Mode) {
        modeRef.set(mode)
    }

    fun cycleMode(forward: Boolean = true): Mode {
        val modes = availableModes
        val currentIndex = modes.indexOf(currentMode)
        val nextIndex = if (forward) {
            (currentIndex + 1) % modes.size
        } else {
            if (currentIndex == 0) modes.size - 1 else currentIndex - 1
        }
        val nextMode = modes[nextIndex]
        setMode(nextMode)
        return nextMode
    }

    /**
     * Backwards-compatible Lite mode toggle. When set to false the preset
     * reverts to [Mode.BALANCED]. Prefer calling [setMode] directly.
     */
    var LITE_MODE_ENABLED: Boolean
        get() = currentMode == Mode.LITE
        set(value) {
            setMode(if (value) Mode.LITE else Mode.BALANCED)
        }

    val modeLabel: String
        get() = currentPreset.label

    // Group accessors --------------------------------------------------------
    val camera: CameraSettings
        get() = currentPreset.camera

    val frame: FrameProcessingSettings
        get() = currentPreset.frame

    val mediaPipe: MediaPipeSettings
        get() = currentPreset.mediaPipe

    val face: FaceSettings
        get() = currentPreset.face

    val tflite: TfliteSettings
        get() = currentPreset.tflite

    val recognition: RecognitionSettings
        get() = currentPreset.recognition

    val adaptive: AdaptiveSettings
        get() = currentPreset.adaptive

    val diagnostics: DiagnosticsSettings
        get() = currentPreset.diagnostics

    val memory: MemorySettings
        get() = currentPreset.memory

    val roi: RoiSettings
        get() = currentPreset.roi

    // Bridged constants for legacy call sites -------------------------------
    val NUM_HANDS: Int
        get() = mediaPipe.numHands

    val HAND_DETECTION_CONFIDENCE: Float
        get() = mediaPipe.handDetectionConfidence

    val HAND_PRESENCE_CONFIDENCE: Float
        get() = mediaPipe.handPresenceConfidence

    val HAND_TRACKING_CONFIDENCE: Float
        get() = mediaPipe.handTrackingConfidence

    val MIN_HAND_CONFIDENCE_FILTER: Float
        get() = mediaPipe.minHandConfidenceFilter

    val HAND_DETECT_EVERY_N: Int
        get() = mediaPipe.handDetectionCadence

    val HAND_SMOOTHING_WINDOW: Int
        get() = mediaPipe.handSmoothingWindow
    
    val HAND_TRACKING_DRIFT_THRESHOLD: Int
        get() = mediaPipe.trackingDriftThreshold

    val ENABLE_MOTION_DETECTION: Boolean
        get() = mediaPipe.enableMotionDetection

    val MOTION_THRESHOLD: Float
        get() = mediaPipe.motionThreshold

    val SKIP_FRAMES_WHEN_STILL: Int
        get() = mediaPipe.skipFramesWhenStill

    val SKIP_INFERENCE_NO_HANDS: Boolean
        get() = mediaPipe.skipInferenceNoHands

    val SINGLE_HAND_MODE: Boolean
        get() = mediaPipe.singleHandMode

    val CAMERA_WIDTH: Int
        get() = camera.width

    val CAMERA_HEIGHT: Int
        get() = camera.height

    val KEEP_ONLY_LATEST: Boolean
        get() = camera.keepOnlyLatest

    val JPEG_QUALITY: Int
        get() = frame.jpegQuality

    val ENABLE_DOWNSCALING: Boolean
        get() = frame.enableDownscaling
    
    val ENABLE_OPENCV_PREPROCESSING: Boolean
        get() = frame.enableOpencvPreprocessing

    val DOWNSCALE_WIDTH: Int
        get() = frame.downscaleWidth

    val DOWNSCALE_HEIGHT: Int
        get() = frame.downscaleHeight

    val BASE_FRAME_SKIP: Int
        get() = frame.baseFrameSkip

    val TARGET_FPS: Int
        get() = frame.targetFps

    val ADAPTIVE_SKIP_ENABLED: Boolean
        get() = frame.adaptiveSkipEnabled

    val ADAPTIVE_SKIP_UPDATE_INTERVAL: Int
        get() = frame.adaptiveSkipUpdateInterval

    val MIN_FRAME_SKIP: Int
        get() = frame.minFrameSkip

    val MAX_FRAME_SKIP: Int
        get() = frame.maxFrameSkip

    val INTERPRETER_THREADS: Int
        get() = tflite.interpreterThreads

    val USE_GPU: Boolean
        get() = tflite.enableGpu

    val USE_NNAPI: Boolean
        get() = tflite.enableNnapi

    val USE_XNNPACK: Boolean
        get() = tflite.enableXnnpack

    val DELEGATE_PROBE_ORDER: List<String>
        get() = tflite.delegateProbeOrder

    val ROLLING_BUFFER_SIZE: Int
        get() = recognition.rollingBufferSize

    val MIN_STABLE_FRAMES: Int
        get() = recognition.minStableFrames

    val DEBOUNCE_FRAMES: Int
        get() = recognition.debounceFrames

    val CONFIDENCE_THRESHOLD: Float
        get() = recognition.confidenceThreshold

    val ENABLE_RESULT_CACHING: Boolean
        get() = recognition.enableResultCaching

    val CACHE_DURATION_MS: Long
        get() = recognition.cacheDurationMs

    val ENABLE_BITMAP_POOLING: Boolean
        get() = memory.enableBitmapPooling

    val BITMAP_POOL_SIZE: Int
        get() = memory.bitmapPoolSize

    val ENABLE_GC_HINTS: Boolean
        get() = memory.enableGcHints

    val VERBOSE_LOGGING: Boolean
        get() = diagnostics.verboseLogging

    val LOG_FPS_INTERVAL_MS: Long
        get() = diagnostics.logFpsIntervalMs

    val LOG_FRAME_PROCESSING: Boolean
        get() = diagnostics.logFrameProcessing

    val MAX_OVERLAY_FPS: Int
        get() = diagnostics.maxOverlayFps

    val FACE_DEFAULT_CADENCE: Int
        get() = face.defaultCadenceFrames

    val FACE_CADENCE_RANGE: IntRange
        get() = face.cadenceRange

    val FACE_HAND_ABSENT_COOLDOWN_FRAMES: Int
        get() = face.handAbsentCooldownFrames

    val FACE_OPTIONAL_BLENDSHAPES: List<String>
        get() = face.optionalBlendshapes

    val FACE_ENABLE_BLENDSHAPES_WHEN_FAST: Boolean
        get() = face.enableBlendshapesWhenAboveTargetFps

    val ROI_ENABLED: Boolean
        get() = roi.enabled

    val ROI_DETECTION_CADENCE: Int
        get() = roi.detectionCadence

    val ROI_CACHE_FRAMES: Int
        get() = roi.cacheFrames

    val ROI_PADDING_MULTIPLIER: Float
        get() = roi.paddingMultiplier

    val ROI_MIN_CONFIDENCE: Float
        get() = roi.minConfidence
}
