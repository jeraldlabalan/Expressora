package com.example.expressora.recognition.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.expressora.BuildConfig
import com.example.expressora.recognition.config.PerformanceConfig
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

object RecognitionDiagnostics {
    private const val TAG = "RecognitionDiag"
    private const val MAX_FRAME_INTERVAL_SAMPLES = 120

    private var initialized = false
    private var startupLogPrinted = false

    // FPS tracking
    private val frameCount = AtomicLong(0)
    private val frameIntervalsMs = ArrayDeque<Long>()
    private var lastFrameTimestampMs: Long = 0L
    private var lastFpsLogTimeMs: Long = SystemClock.elapsedRealtime()
    
    // Lite auto-fallback tracking
    private var startupTimestampMs: Long = 0L
    private var lowFpsFrameCount = 0
    private var autoFallbackTriggered = false

    // Delegate / startup info
    private var handsDelegate = "CPU"
    private var faceDelegate = "CPU"
    private var classifierDelegate = "CPU"
    private var classifierModelVariant = "unknown"
    private var startupMode: PerformanceConfig.Mode = PerformanceConfig.currentMode
    private var startupSummary: String? = null
    private var deviceInfo: String = "unknown"

    fun logInitialization(
        modelVariant: String,
        outputMode: String,
        delegate: String,
        threads: Int,
        labelCount: Int
    ) {
        if (!initialized) {
            Log.i(TAG, "=== Recognition Engine Initialized ===")
            Log.i(TAG, "Model: $modelVariant")
            Log.i(TAG, "Output Mode: $outputMode")
            Log.i(TAG, "Delegate: $delegate")
            Log.i(TAG, "Threads: $threads")
            Log.i(TAG, "Labels: $labelCount")
            Log.i(TAG, "=====================================")
            classifierModelVariant = modelVariant
            classifierDelegate = delegate
            initialized = true
        }
    }

    fun recordFrame(timestampMs: Long = SystemClock.elapsedRealtime()) {
        frameCount.incrementAndGet()
        if (lastFrameTimestampMs != 0L) {
            val delta = timestampMs - lastFrameTimestampMs
            if (delta > 0) {
                frameIntervalsMs.addLast(delta)
                if (frameIntervalsMs.size > MAX_FRAME_INTERVAL_SAMPLES) {
                    frameIntervalsMs.removeFirst()
                }
            }
        }
        lastFrameTimestampMs = timestampMs
    }

    fun getRollingFps(windowSize: Int = frameIntervalsMs.size): Float {
        if (frameIntervalsMs.isEmpty()) return 0f
        val clampedWindow = max(1, windowSize.coerceAtMost(frameIntervalsMs.size))
        var sum = 0L
        var count = 0
        val startIndex = frameIntervalsMs.size - clampedWindow
        for (index in frameIntervalsMs.size - 1 downTo startIndex) {
            sum += frameIntervalsMs.elementAt(index)
            count++
        }
        if (count == 0 || sum <= 0L) return 0f
        val averageFrameMs = sum.toFloat() / count
        return if (averageFrameMs <= 0f) 0f else 1000f / averageFrameMs
    }

    fun logFpsIfNeeded(nowMs: Long = SystemClock.elapsedRealtime()) {
        val intervalMs = PerformanceConfig.LOG_FPS_INTERVAL_MS
        if (nowMs - lastFpsLogTimeMs >= intervalMs) {
            val fps = getRollingFps()
            if (fps > 0f) {
                Log.d(
                    TAG,
                    "FPS: %.1f (frames=%d, mode=%s)".format(
                        fps,
                        frameCount.get(),
                        PerformanceConfig.modeLabel
                    )
                )
            }
            lastFpsLogTimeMs = nowMs
        }
    }

    /**
     * Emits the required one-line startup summary once per process.
     *
     * Example:
     * 480x360 | latest | Hands: GPU H=2 thr=0.60 detectN=5 | Face: GPU /5f |
     * Clf: INT8 GPU thr=0.65 hold=3 | TARGET=18FPS | Device: Pixel6 / GPU
     */
    fun logStartupConfig(
        context: Context,
        handsDelegate: String,
        classifierDelegate: String,
        classifierModel: String,
        faceDelegate: String = "CPU",
        cameraWidth: Int = PerformanceConfig.CAMERA_WIDTH,
        cameraHeight: Int = PerformanceConfig.CAMERA_HEIGHT,
        keepOnlyLatest: Boolean = PerformanceConfig.KEEP_ONLY_LATEST,
        handDetectEveryN: Int = PerformanceConfig.HAND_DETECT_EVERY_N,
        faceCadenceFrames: Int = PerformanceConfig.FACE_DEFAULT_CADENCE,
        classifierThreshold: Float = PerformanceConfig.CONFIDENCE_THRESHOLD,
        holdFrames: Int = PerformanceConfig.DEBOUNCE_FRAMES,
        targetFps: Int = PerformanceConfig.TARGET_FPS
    ) {
        if (startupLogPrinted) return
        startupLogPrinted = true
        startupTimestampMs = SystemClock.elapsedRealtime()

        this.handsDelegate = handsDelegate
        this.classifierDelegate = classifierDelegate
        this.faceDelegate = faceDelegate
        this.classifierModelVariant = classifierModel
        this.startupMode = PerformanceConfig.currentMode
        this.deviceInfo = getDeviceInfo(context)
        
        // Debug build warning banner
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.w(TAG, "║  ⚠️  DEBUG BUILD DETECTED — FPS WILL BE SIGNIFICANTLY LOWER  ║")
            Log.w(TAG, "║     Use Release build for accurate performance testing     ║")
            Log.w(TAG, "╚════════════════════════════════════════════════════════════╝")
        }

        val logLine = buildString {
            append("${cameraWidth}x${cameraHeight} | ")
            append(if (keepOnlyLatest) "latest" else "queued")
            append(" | Hands: $handsDelegate H=${PerformanceConfig.NUM_HANDS} ")
            append("thr=%.2f ".format(PerformanceConfig.HAND_DETECTION_CONFIDENCE))
            append("detectN=$handDetectEveryN | ")
            append("Face: $faceDelegate /${faceCadenceFrames}f | ")
            append("Clf: $classifierModel $classifierDelegate ")
            append("thr=%.2f ".format(classifierThreshold))
            append("hold=$holdFrames | ")
            append("TARGET=${targetFps}FPS | ")
            append("Device: $deviceInfo | ")
            append("Mode: ${PerformanceConfig.modeLabel}")
            if (BuildConfig.DEBUG) {
                append(" | ⚠️ DEBUG")
            }
        }

        startupSummary = logLine
        Log.i(TAG, logLine)
    }
    
    /**
     * Extract device model and GPU capability info for telemetry.
     */
    private fun getDeviceInfo(context: Context): String {
        val model = Build.MODEL ?: "unknown"
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: ""
        } else {
            ""
        }
        
        val hasGPU = context.packageManager.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK)
        val gpuInfo = if (hasGPU) "GPU" else "NoGPU"
        
        return if (socModel.isNotEmpty()) {
            "$model ($socModel) / $gpuInfo"
        } else {
            "$model / $gpuInfo"
        }
    }
    
    /**
     * Check if sustained low FPS warrants auto-fallback to Lite mode.
     * Call this periodically (e.g., every second during startup phase).
     * Returns true if fallback should be triggered.
     */
    fun shouldAutoFallbackToLite(): Boolean {
        if (autoFallbackTriggered) return false
        if (PerformanceConfig.currentMode == PerformanceConfig.Mode.LITE) return false
        
        val elapsed = SystemClock.elapsedRealtime() - startupTimestampMs
        if (elapsed < 10_000) { // Within 10s startup window
            val fps = getCurrentFPS()
            if (fps > 0f && fps < 5f) {
                lowFpsFrameCount++
                // If sustained <5 FPS for majority of startup window
                if (lowFpsFrameCount > 8) {
                    autoFallbackTriggered = true
                    Log.w(TAG, "Auto-fallback to Lite mode triggered: sustained FPS < 5 detected")
                    return true
                }
            } else if (fps >= 5f) {
                lowFpsFrameCount = 0 // Reset if FPS recovers
            }
        }
        return false
    }

    /**
     * Backwards-compatible FPS accessor.
     */
    fun getCurrentFPS(): Float = getRollingFps()

    /**
     * Backwards-compatible FPS logger.
     */
    fun logFPSIfNeeded() = logFpsIfNeeded()

    fun getStartupSummary(): String? = startupSummary

    fun getDelegateInfo(): Pair<String, String> = handsDelegate to classifierDelegate

    fun getDelegates(): Triple<String, String, String> =
        Triple(handsDelegate, faceDelegate, classifierDelegate)

    fun getClassifierModelVariant(): String = classifierModelVariant

    fun getStartupMode(): PerformanceConfig.Mode = startupMode

    fun reset() {
        frameCount.set(0)
        frameIntervalsMs.clear()
        lastFrameTimestampMs = 0L
        lastFpsLogTimeMs = SystemClock.elapsedRealtime()
        startupLogPrinted = false
        startupSummary = null
        initialized = false
    }
}
