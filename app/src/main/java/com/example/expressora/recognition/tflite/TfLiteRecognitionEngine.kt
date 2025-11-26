package com.example.expressora.recognition.tflite

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.BuildConfig
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.diagnostics.ConfidenceDiagnostics
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.GlossEvent
import com.example.expressora.recognition.model.ModelSignature
import com.example.expressora.recognition.model.RecognitionResult
import com.example.expressora.recognition.origin.OriginResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import kotlin.math.exp

class TfLiteRecognitionEngine(
    private val context: Context,
    private val modelAsset: String,
    private val labelAsset: String,
    private val labelMappedAsset: String,
    private val featureDim: Int,
    private val modelSignature: ModelSignature? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : RecognitionEngine {

    private val TAG = "TfLiteRecognitionEngine"
    private val appContext = context.applicationContext
    private val labelLock = Any()
    // CRITICAL: Mutex to prevent Scudo race condition (thread-safe inference gate)
    private val inferenceMutex = Mutex()
    // FP32 model is always used now - no other variants
    private val modelVariant: String = "FP32"
    
    private val hasMultiHead = modelSignature?.isMultiHead() ?: false
    private val originLabels = listOf("ASL", "FSL")

    @Volatile
    private var labels: List<String> = emptyList()
    
    @Volatile
    private var mappedLabels: List<String> = emptyList()

    @Volatile
    private var interpreterClasses: Int = 0

    @Volatile
    private var interpreter: TfLiteInterpreter? = null

    private val outputSizeHint: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { inspectOutputSize() }
    
    // Rolling buffer optimized with PerformanceConfig
    private val rollingBuffer = RollingLogitsBuffer(PerformanceConfig.ROLLING_BUFFER_SIZE)
    
    // Result caching for stable predictions
    private var cachedResult: RecognitionResult? = null
    private var cacheTimestamp: Long = 0L
    
    // Track previous inputs/outputs for change detection
    private var lastFeatureVectorHash: Int = 0
    private var lastRawLogitsHash: Int = 0
    private var staticOutputFrameCount = 0
    private var lastRawLogits: FloatArray? = null
    
    // Option to bypass rolling buffer for debugging
    private val bypassRollingBuffer = false // Set to true to see raw model outputs

    private val _events = MutableSharedFlow<GlossEvent>(replay = 1)
    override val events: Flow<GlossEvent> = _events.asSharedFlow()
    
    // Internal result flow for detailed recognition results
    private val _results = MutableSharedFlow<RecognitionResult>(replay = 1)
    val results: Flow<RecognitionResult> = _results.asSharedFlow()

    init {
        // Clear any cached results on initialization
        cachedResult = null
        cacheTimestamp = 0L
        Log.i(TAG, "🧹 Cache cleared on engine initialization")
        
        // Load labels and origin stats
        labels = LabelMap.load(appContext, labelAsset)
        mappedLabels = LabelMap.loadMapped(appContext, labelMappedAsset)
        LabelMap.loadOriginStats(appContext)
        OriginResolver.initialize(appContext)
    }

    override suspend fun start() {
        _events.emit(GlossEvent.Idle)
    }

    override suspend fun stop() {
        // CRITICAL: Acquire lock before closing to prevent closing while inference is running
        inferenceMutex.withLock {
            interpreter?.close()
            interpreter = null
            Log.i(TAG, "🛑 Interpreter closed safely")
        }
    }
    
    /**
     * Clear in-memory result cache to force fresh predictions.
     * Useful for testing or when model is updated.
     */
    fun clearCache() {
        cachedResult = null
        cacheTimestamp = 0L
        rollingBuffer.clear()
        lastFeatureVectorHash = 0
        lastRawLogitsHash = 0
        staticOutputFrameCount = 0
        lastRawLogits = null
        Log.i(TAG, "🧹 Cache cleared: result cache, rolling buffer, and state reset")
    }

    override suspend fun onLandmarks(feature: FloatArray) {
        scope.launch {
            try {
                RecognitionDiagnostics.recordFrame()
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    RecognitionDiagnostics.logFPSIfNeeded()
                }
                
                // DEBUG: Log feature vector statistics (only in debug builds)
                val featureStats = calculateFeatureStats(feature)
                
                // CRITICAL: Validate that features are scaled (for retrained model)
                // Scaled features should have mean near 0 and std near 1 (after scaling)
                // Raw MediaPipe coordinates are in [0, 1] range, so unscaled features would have mean ~0.5
                val featureMean = featureStats.mean
                val isLikelyScaled = kotlin.math.abs(featureMean) < 0.1f // Scaled features should have mean near 0
                
                LogUtils.debugIfVerbose(TAG) { "Feature vector stats: size=${feature.size}, expected=$featureDim, " +
                        "nonZero=${featureStats.nonZeroCount}, min=${featureStats.min}, " +
                        "max=${featureStats.max}, mean=${featureStats.mean}, " +
                        "isAllZeros=${featureStats.isAllZeros}, hasNaN=${featureStats.hasNaN}, " +
                        "isLikelyScaled=$isLikelyScaled" }
                
                if (!isLikelyScaled && featureStats.nonZeroCount > 0) {
                    Log.w(TAG, "⚠️ WARNING: Features may not be scaled! Mean=$featureMean (expected near 0 for scaled features). " +
                            "Model requires feature scaling: (features - mean) / std")
                }
                
                // Calculate feature vector hash to detect significant changes
                val featureHash = calculateArrayHash(feature)
                val featureChangedSignificantly = kotlin.math.abs(featureHash - lastFeatureVectorHash) > 1000
                
                // Log feature vector changes for debugging
                if (featureChangedSignificantly) {
                    LogUtils.d(TAG) { "📊 Feature vector changed significantly: hash=$featureHash (was=$lastFeatureVectorHash), nonZero=${featureStats.nonZeroCount}, clearing rolling buffer" }
                    rollingBuffer.clear()
                    lastFeatureVectorHash = featureHash
                } else if (lastFeatureVectorHash != 0) {
                    LogUtils.debugIfVerbose(TAG) { "Feature vector similar: hash=$featureHash (was=$lastFeatureVectorHash), nonZero=${featureStats.nonZeroCount}" }
                } else {
                    LogUtils.d(TAG) { "📊 First feature vector received: hash=$featureHash, nonZero=${featureStats.nonZeroCount}" }
                    lastFeatureVectorHash = featureHash
                }
                
                // Validation: Check if feature vector is valid
                if (feature.size != featureDim) {
                    Log.w(TAG, "Feature vector size mismatch: got ${feature.size}, expected $featureDim")
                }
                if (featureStats.isAllZeros) {
                    Log.w(TAG, "WARNING: Feature vector is all zeros! Model will not produce valid results.")
                    rollingBuffer.clear()
                }
                if (featureStats.hasNaN) {
                    Log.w(TAG, "WARNING: Feature vector contains NaN values!")
                }
                
                // CRITICAL: Always run inference to ensure buffer is written, even if we use cached result
                // The caching should only affect result emission, not input preparation
                ensureLabels()
                val tflite = obtainInterpreter()
                
                // CRITICAL: Always call runMultiOutput to ensure ByteBuffer is written with fresh data
                // This ensures we always process the current frame's data
                LogUtils.d(TAG) { "🔄 Running inference on frame @ ${System.currentTimeMillis()}, featureVectorHash=$featureHash" }
                val result = tflite.runMultiOutput(feature)
                
                // Log that inference completed with fresh data
                LogUtils.debugIfVerbose(TAG) { "Inference completed, processing new result" }
                
                // DEBUG: Log raw logits from model (only in debug builds)
                val logitsStats = calculateFeatureStats(result.glossLogits)
                LogUtils.debugIfVerbose(TAG) { "Raw logits stats: size=${result.glossLogits.size}, " +
                        "min=${logitsStats.min}, max=${logitsStats.max}, mean=${logitsStats.mean}, " +
                        "isAllZeros=${logitsStats.isAllZeros}, hasNaN=${logitsStats.hasNaN}" }
                
                // Log top 5 raw logits (only in debug builds)
                val topLogits = result.glossLogits.mapIndexed { index, value -> index to value }
                    .sortedByDescending { it.second }
                    .take(5)
                LogUtils.debugIfVerbose(TAG) { "Top 5 raw logits: ${topLogits.joinToString { "idx=${it.first} val=${it.second}" }}" }
                
                // Detect static model output
                val currentLogitsHash = calculateArrayHash(result.glossLogits)
                val isStaticOutput = lastRawLogits != null && 
                    currentLogitsHash == lastRawLogitsHash && 
                    arraysEqual(result.glossLogits, lastRawLogits!!, tolerance = 0.0001f)
                
                if (isStaticOutput) {
                    staticOutputFrameCount++
                    if (staticOutputFrameCount > 3) {
                        Log.e(TAG, "❌ CRITICAL: Model output is STATIC for $staticOutputFrameCount frames! " +
                                "Clearing rolling buffer and forcing reset.")
                        rollingBuffer.clear()
                        staticOutputFrameCount = 0
                    }
                } else {
                    staticOutputFrameCount = 0
                }
                lastRawLogitsHash = currentLogitsHash
                lastRawLogits = result.glossLogits.copyOf()
                
                // Validation: Check if model output is valid
                if (logitsStats.isAllZeros) {
                    Log.e(TAG, "ERROR: Model output logits are all zeros! Model may not be working correctly.")
                    rollingBuffer.clear()
                }
                if (logitsStats.hasNaN) {
                    Log.e(TAG, "ERROR: Model output logits contain NaN values!")
                }
                
                // Check if confidence is extremely low (suggests model not working)
                val maxLogit = result.glossLogits.maxOrNull() ?: 0f
                val minLogit = result.glossLogits.minOrNull() ?: 0f
                val logitRange = maxLogit - minLogit
                if (logitRange < 0.1f && staticOutputFrameCount > 5) {
                    Log.e(TAG, "ERROR: Model output range is too small ($logitRange), model may not be working. Clearing buffer.")
                    rollingBuffer.clear()
                }
                
                reconcileLabels(result.glossLogits.size)
                
                // Add to rolling buffer and get averaged logits (or bypass if debugging)
                val avgLogits = if (bypassRollingBuffer) {
                    LogUtils.d(TAG) { "⚠️ BYPASSING rolling buffer - using raw logits" }
                    result.glossLogits
                } else {
                    rollingBuffer.add(result.glossLogits)
                    rollingBuffer.getAverage()
                }
                
                // DEBUG: Log averaged probabilities (only in debug builds)
                // NOTE: Model already outputs probabilities (softmax applied in model), so we use them directly
                val avgProbsStats = calculateFeatureStats(avgLogits)
                LogUtils.debugIfVerbose(TAG) { "Averaged probabilities stats: size=${avgLogits.size}, " +
                        "min=${avgProbsStats.min}, max=${avgProbsStats.max}, mean=${avgProbsStats.mean}" }
                
                // Model already outputs probabilities (sum to ~1.0), so use them directly - DO NOT apply softmax again!
                val probs = avgLogits
                
                // DEBUG: Log softmax probabilities (top 5) - only in debug builds
                val topProbs = probs.mapIndexed { index, value -> index to value }
                    .sortedByDescending { it.second }
                    .take(5)
                LogUtils.debugIfVerbose(TAG) { "Top 5 softmax probabilities: ${topProbs.joinToString { pair ->
                    val label = labels.getOrElse(pair.first) { "CLASS_${pair.first}" }
                    "idx=${pair.first} label=$label prob=${pair.second}" 
                }}" }
                
                // Get top prediction
                val bestIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
                val bestConf = probs[bestIndex]
                val rawLabel = labels.getOrElse(bestIndex) { "CLASS_$bestIndex" }
                val mappedLabel = mappedLabels.getOrElse(bestIndex) { rawLabel }
                
                // DEBUG: Log final prediction (only in debug builds)
                LogUtils.debugIfVerbose(TAG) { "Final prediction: label='$mappedLabel' (raw='$rawLabel'), " +
                        "index=$bestIndex, confidence=$bestConf (${(bestConf * 100).toInt()}%)" }
                
                // DIAGNOSTIC: Analyze low confidence issues
                if (bestConf < 0.5f) {
                    Log.w(TAG, "⚠️ LOW CONFIDENCE DETECTED: ${(bestConf * 100).toInt()}% for '$mappedLabel'")
                    ConfidenceDiagnostics.logDiagnostics(avgLogits, probs, feature)
                }
                
                // Resolve origin
                val originBadge = if (result.originLogits != null) {
                    val originProbs = softmax(result.originLogits)
                    val originStats = calculateFeatureStats(result.originLogits)
                    LogUtils.debugIfVerbose(TAG) { "Origin logits stats: size=${result.originLogits.size}, " +
                            "min=${originStats.min}, max=${originStats.max}, mean=${originStats.mean}" }
                    LogUtils.debugIfVerbose(TAG) { "Origin probabilities: ${originProbs.mapIndexed { i, p -> 
                        "${originLabels.getOrElse(i) { "UNK" }}=$p" 
                    }.joinToString()}" }
                    OriginResolver.resolveFromMultiHead(originLabels, originProbs)
                } else {
                    OriginResolver.resolveFromPriors(rawLabel)
                }
                
                LogUtils.debugIfVerbose(TAG) { "Origin badge: ${originBadge.origin}, confidence=${originBadge.confidence}" }
                
                // Build debug info
                val debugInfo = com.example.expressora.recognition.model.DebugInfo(
                    featureVectorStats = com.example.expressora.recognition.model.FeatureVectorStats(
                        size = feature.size,
                        expectedSize = featureDim,
                        nonZeroCount = featureStats.nonZeroCount,
                        min = featureStats.min,
                        max = featureStats.max,
                        mean = featureStats.mean,
                        isAllZeros = featureStats.isAllZeros,
                        hasNaN = featureStats.hasNaN
                    ),
                    rawLogits = topLogits.map { (idx, value) ->
                        com.example.expressora.recognition.model.TopPrediction(
                            index = idx,
                            label = labels.getOrElse(idx) { "CLASS_$idx" },
                            value = value
                        )
                    },
                    softmaxProbs = topProbs.map { (idx, value) ->
                        com.example.expressora.recognition.model.TopPrediction(
                            index = idx,
                            label = labels.getOrElse(idx) { "CLASS_$idx" },
                            value = value
                        )
                    },
                    averagedLogits = avgLogits.mapIndexed { idx, value -> idx to value }
                        .sortedByDescending { it.second }
                        .take(5)
                        .map { (idx, value) ->
                            com.example.expressora.recognition.model.TopPrediction(
                                index = idx,
                                label = labels.getOrElse(idx) { "CLASS_$idx" },
                                value = value
                            )
                        }
                )
                
                // Emit detailed result - ALWAYS emit fresh result from current inference
                val recognitionResult = RecognitionResult(
                    glossLabel = mappedLabel,
                    glossConf = bestConf,
                    glossIndex = bestIndex,
                    originLabel = originBadge.origin,
                    originConf = originBadge.confidence,
                    debugInfo = debugInfo
                )
                
                // Log result emission for debugging
                LogUtils.d(TAG) { "✅ Emitting fresh recognition result: label='$mappedLabel', conf=$bestConf (${(bestConf * 100).toInt()}%), origin='${originBadge.origin}'" }
                
                // Cache stable results for potential future use (but always emit fresh results)
                if (PerformanceConfig.ENABLE_RESULT_CACHING && bestConf >= 0.8f) {
                    cachedResult = recognitionResult
                    cacheTimestamp = SystemClock.elapsedRealtime()
                    LogUtils.debugIfVerbose(TAG) { "Cached result for future reference: $mappedLabel" }
                }
                
                // ALWAYS emit the fresh result from current inference
                _results.emit(recognitionResult)
                
                // Emit simple event for backward compatibility
                _events.emit(GlossEvent.InProgress(listOf(mappedLabel)))
                
            } catch (t: Throwable) {
                Log.e(TAG, "Recognition error", t)
                _events.emit(GlossEvent.Error(t.message ?: "Recognition failure"))
            }
        }
    }
    
    /**
     * Handle ByteBuffer input for LSTM sequence models (e.g., 30 frames × 237 features).
     * Bypasses single-frame mean check and processes ByteBuffer directly.
     * 
     * @param inputBuffer ByteBuffer containing scaled features (30 frames × 237 features = 7110 floats)
     */
    suspend fun onLandmarksSequence(inputBuffer: ByteBuffer) {
        Log.e(TAG, "🚀 onLandmarksSequence() CALLED - buffer capacity: ${inputBuffer.capacity()} bytes")
        scope.launch {
            try {
                Log.e(TAG, "🚀 onLandmarksSequence() coroutine started")
                RecognitionDiagnostics.recordFrame()
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    RecognitionDiagnostics.logFPSIfNeeded()
                }
                
                // Ensure labels are loaded
                ensureLabels()
                Log.e(TAG, "✅ Labels ensured")
                
                // CRITICAL: Verify interpreter is available before inference
                val tflite = try {
                    obtainInterpreter()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ CRITICAL: Failed to obtain interpreter for LSTM inference", e)
                    Log.e(TAG, "❌ Interpreter error type: ${e.javaClass.simpleName}, message: ${e.message}")
                    _events.emit(GlossEvent.Error("Interpreter initialization failed: ${e.message ?: e.javaClass.simpleName}"))
                    return@launch
                }
                
                if (tflite == null) {
                    Log.e(TAG, "❌ CRITICAL: Interpreter is null!")
                    _events.emit(GlossEvent.Error("Interpreter is null"))
                    return@launch
                }
                
                Log.e(TAG, "✅ Interpreter obtained successfully")
                
                // Rewind buffer to ensure we read from start
                inputBuffer.rewind()
                
                Log.e(TAG, "🔄 Running LSTM inference on sequence buffer: size=${inputBuffer.capacity()} bytes")
                
                // --- SANITY CHECK ---
                if (inputBuffer.capacity() >= 40) { // Ensure we have at least 10 floats
                    val debugArr = FloatArray(10)
                    val originalPos = inputBuffer.position()
                    inputBuffer.position(0)
                    inputBuffer.asFloatBuffer().get(debugArr)
                    inputBuffer.position(originalPos) // Reset position!
                    
                    val debugStr = debugArr.joinToString(", ") { "%.3f".format(it) }
                    android.util.Log.e("SANITY_CHECK", "🔍 MODEL INPUT: [$debugStr ...]")
                    
                    // Check for "Dead" Input
                    val isDead = debugArr.all { it == 0f } || debugArr.all { it == -1f }
                    if (isDead) {
                        android.util.Log.e("SANITY_CHECK", "❌ WARNING: INPUT IS DEAD (All Zeros or -1)")
                    }
                }
                // --------------------
                
                // Run LSTM inference with enhanced error handling (THREAD-SAFE BLOCK)
                Log.e(TAG, "🔍 About to call runMultiOutputSequence() - acquiring mutex...")
                val result = try {
                    inferenceMutex.withLock {
                        Log.e(TAG, "🔍 Mutex acquired, calling runMultiOutputSequence()...")
                        // Rewind buffer before reading (inside lock to ensure consistency)
                        inputBuffer.rewind()
                        val inferenceResult = tflite.runMultiOutputSequence(inputBuffer)
                        Log.e(TAG, "✅ runMultiOutputSequence() completed successfully")
                        inferenceResult
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "❌ IllegalStateException in runMultiOutputSequence()", e)
                    // Re-throw IllegalStateException (already logged in TfLiteInterpreter)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "❌ CRITICAL: LSTM inference call failed!", e)
                    Log.e(TAG, "❌ Inference error type: ${e.javaClass.simpleName}, message: ${e.message}")
                    Log.e(TAG, "❌ Buffer state: capacity=${inputBuffer.capacity()}, position=${inputBuffer.position()}, limit=${inputBuffer.limit()}")
                    throw e
                }
                
                Log.e(TAG, "✅ LSTM inference completed, processing result")
                
                // Process output similar to onLandmarks but without mean check
                val logitsStats = calculateFeatureStats(result.glossLogits)
                
                // DIAGNOSTIC: Log raw model output statistics
                val rawMax = result.glossLogits.maxOrNull() ?: 0f
                val rawMin = result.glossLogits.minOrNull() ?: 0f
                val rawMean = result.glossLogits.average().toFloat()
                val rawStd = kotlin.math.sqrt(result.glossLogits.map { (it - rawMean) * (it - rawMean) }.average()).toFloat()
                val rawSum = result.glossLogits.sum()
                val rawTop5 = result.glossLogits.mapIndexed { idx, value -> idx to value }
                    .sortedByDescending { it.second }
                    .take(5)
                
                Log.e(TAG, "📊 RAW MODEL OUTPUT (from inference):")
                Log.e(TAG, "   Range: [%.6f, %.6f] (span: %.6f)".format(rawMin, rawMax, rawMax - rawMin))
                Log.e(TAG, "   Mean: %.6f, Std: %.6f, Sum: %.6f".format(rawMean, rawStd, rawSum))
                Log.e(TAG, "   Top 5: ${rawTop5.joinToString { "idx[${it.first}]=${"%.6f".format(it.second)}" }}")
                
                // Skip mean check for sequence data (already scaled in buffer)
                
                // DIAGNOSTIC: Log rolling buffer state BEFORE adding
                val bufferSizeBefore = rollingBuffer.size()
                Log.e(TAG, "🔄 ROLLING BUFFER STATE (before adding): size=$bufferSizeBefore")
                
                // Add to rolling buffer
                // NOTE: Model already outputs probabilities (softmax applied in model), so we use them directly
                val avgProbs = if (bypassRollingBuffer) {
                    Log.e(TAG, "⚠️ BYPASSING rolling buffer - using raw probabilities")
                    result.glossLogits
                } else {
                    rollingBuffer.add(result.glossLogits)
                    val bufferSizeAfter = rollingBuffer.size()
                    Log.e(TAG, "✅ Added to rolling buffer: size=$bufferSizeAfter (was $bufferSizeBefore)")
                    rollingBuffer.getAverage()
                }
                
                // DIAGNOSTIC: Log averaged probabilities statistics
                val avgMax = avgProbs.maxOrNull() ?: 0f
                val avgMin = avgProbs.minOrNull() ?: 0f
                val avgMean = avgProbs.average().toFloat()
                val avgStd = kotlin.math.sqrt(avgProbs.map { (it - avgMean) * (it - avgMean) }.average()).toFloat()
                val avgSum = avgProbs.sum()
                val avgTop5 = avgProbs.mapIndexed { idx, value -> idx to value }
                    .sortedByDescending { it.second }
                    .take(5)
                
                Log.e(TAG, "📊 AVERAGED PROBABILITIES (after rolling buffer):")
                Log.e(TAG, "   Range: [%.6f, %.6f] (span: %.6f)".format(avgMin, avgMax, avgMax - avgMin))
                Log.e(TAG, "   Mean: %.6f, Std: %.6f, Sum: %.6f".format(avgMean, avgStd, avgSum))
                Log.e(TAG, "   Top 5: ${avgTop5.joinToString { "idx[${it.first}]=${"%.6f".format(it.second)}" }}")
                
                // Model already outputs probabilities (sum to ~1.0), so use them directly - DO NOT apply softmax again!
                val probs = avgProbs
                
                // Get top prediction
                val bestIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
                val bestConf = probs[bestIndex]
                val rawLabel = labels.getOrElse(bestIndex) { "CLASS_$bestIndex" }
                val mappedLabel = mappedLabels.getOrElse(bestIndex) { rawLabel }
                
                // DIAGNOSTIC: Log confidence calculation and label mapping
                Log.e(TAG, "🎯 CONFIDENCE CALCULATION:")
                Log.e(TAG, "   Best index: $bestIndex")
                Log.e(TAG, "   Best confidence: %.6f (%d%%)".format(bestConf, (bestConf * 100).toInt()))
                Log.e(TAG, "   Raw label: '$rawLabel'")
                Log.e(TAG, "   Mapped label: '$mappedLabel'")
                Log.e(TAG, "   Label mapping: ${if (rawLabel == mappedLabel) "no mapping" else "mapped from '$rawLabel' to '$mappedLabel'"}")
                
                // DIAGNOSTIC: Log top 5 predictions with labels
                val top5Predictions = probs.mapIndexed { idx, value -> 
                    Triple(idx, labels.getOrElse(idx) { "CLASS_$idx" }, mappedLabels.getOrElse(idx) { labels.getOrElse(idx) { "CLASS_$idx" } })
                }
                    .sortedByDescending { probs[it.first] }
                    .take(5)
                
                Log.e(TAG, "🏆 TOP 5 PREDICTIONS:")
                top5Predictions.forEachIndexed { rank, (idx, raw, mapped) ->
                    val conf = probs[idx]
                    Log.e(TAG, "   ${rank + 1}. idx[$idx]: '$mapped' (raw: '$raw') = %.6f (%d%%)".format(conf, (conf * 100).toInt()))
                }
                
                Log.e(TAG, "✅ LSTM prediction: label='$mappedLabel' (raw='$rawLabel'), index=$bestIndex, confidence=$bestConf (${(bestConf * 100).toInt()}%)")
                
                // DIAGNOSTIC: Analyze low confidence issues
                if (bestConf < 0.5f) {
                    Log.e(TAG, "⚠️ LOW CONFIDENCE DETECTED (LSTM): ${(bestConf * 100).toInt()}% for '$mappedLabel'")
                    ConfidenceDiagnostics.logDiagnostics(avgProbs, probs, null)
                }
                
                // DIAGNOSTIC SUMMARY: Log complete pipeline state after inference
                logDiagnosticSummary(
                    rawOutput = result.glossLogits,
                    averagedOutput = avgProbs,
                    finalProbs = probs,
                    bestIndex = bestIndex,
                    bestConf = bestConf,
                    rawLabel = rawLabel,
                    mappedLabel = mappedLabel,
                    rollingBufferSize = if (bypassRollingBuffer) 0 else rollingBuffer.size()
                )
                
                // Resolve origin
                val originBadge = if (result.originLogits != null) {
                    val originProbs = softmax(result.originLogits)
                    OriginResolver.resolveFromMultiHead(originLabels, originProbs)
                } else {
                    OriginResolver.resolveFromPriors(rawLabel)
                }
                
                // Build debug info
                val debugInfo = com.example.expressora.recognition.model.DebugInfo(
                    featureVectorStats = com.example.expressora.recognition.model.FeatureVectorStats(
                        size = 7110, // 30 frames × 237 features
                        expectedSize = 7110,
                        nonZeroCount = 0, // Not calculated for sequence
                        min = 0f,
                        max = 0f,
                        mean = 0f, // Not checked for sequence (assumed scaled)
                        isAllZeros = false,
                        hasNaN = false
                    ),
                    rawLogits = result.glossLogits.mapIndexed { idx, value -> idx to value }
                        .sortedByDescending { it.second }
                        .take(5)
                        .map { (idx, value) ->
                            com.example.expressora.recognition.model.TopPrediction(
                                index = idx,
                                label = labels.getOrElse(idx) { "CLASS_$idx" },
                                value = value
                            )
                        },
                    softmaxProbs = probs.mapIndexed { idx, value -> idx to value }
                        .sortedByDescending { it.second }
                        .take(5)
                        .map { (idx, value) ->
                            com.example.expressora.recognition.model.TopPrediction(
                                index = idx,
                                label = labels.getOrElse(idx) { "CLASS_$idx" },
                                value = value
                            )
                        },
                    averagedLogits = avgProbs.mapIndexed { idx, value -> idx to value }
                        .sortedByDescending { it.second }
                        .take(5)
                        .map { (idx, value) ->
                            com.example.expressora.recognition.model.TopPrediction(
                                index = idx,
                                label = labels.getOrElse(idx) { "CLASS_$idx" },
                                value = value
                            )
                        }
                )
                
                // Emit detailed result
                val recognitionResult = RecognitionResult(
                    glossLabel = mappedLabel,
                    glossConf = bestConf,
                    glossIndex = bestIndex,
                    originLabel = originBadge.origin,
                    originConf = originBadge.confidence,
                    timestamp = System.currentTimeMillis(),
                    debugInfo = debugInfo
                )
                
                LogUtils.d(TAG) { "✅ Emitting LSTM recognition result: label='$mappedLabel', conf=$bestConf (${(bestConf * 100).toInt()}%), origin='${originBadge.origin}'" }
                
                // Cache stable results
                if (PerformanceConfig.ENABLE_RESULT_CACHING && bestConf >= 0.8f) {
                    cachedResult = recognitionResult
                    cacheTimestamp = SystemClock.elapsedRealtime()
                }
                
                // Emit results
                _results.emit(recognitionResult)
                _events.emit(GlossEvent.InProgress(listOf(mappedLabel)))
                
            } catch (t: Throwable) {
                Log.e(TAG, "❌ LSTM recognition error", t)
                Log.e(TAG, "❌ Error type: ${t.javaClass.simpleName}, message: ${t.message}")
                Log.e(TAG, "❌ Buffer size: ${inputBuffer.capacity()} bytes, position: ${inputBuffer.position()}, limit: ${inputBuffer.limit()}")
                t.printStackTrace()
                _events.emit(GlossEvent.Error("LSTM recognition failure: ${t.message ?: t.javaClass.simpleName}"))
            }
        }
    }
    
    /**
     * Calculate statistics for a feature vector or logits array
     */
    private data class FeatureStats(
        val nonZeroCount: Int,
        val min: Float,
        val max: Float,
        val mean: Float,
        val isAllZeros: Boolean,
        val hasNaN: Boolean
    )
    
    private fun calculateFeatureStats(array: FloatArray): FeatureStats {
        if (array.isEmpty()) {
            return FeatureStats(0, 0f, 0f, 0f, true, false)
        }
        
        var nonZeroCount = 0
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        var sum = 0f
        var hasNaN = false
        
        for (value in array) {
            if (value.isNaN()) {
                hasNaN = true
            } else {
                if (value != 0f) nonZeroCount++
                if (value < min) min = value
                if (value > max) max = value
                sum += value
            }
        }
        
        val mean = if (array.isNotEmpty()) sum / array.size else 0f
        val isAllZeros = nonZeroCount == 0
        
        return FeatureStats(nonZeroCount, min, max, mean, isAllZeros, hasNaN)
    }
    
    /**
     * Calculate a simple hash of array for change detection
     * For two-hand vectors, left hand (first 63 values) may be zeros, so we check
     * both the left hand portion AND the right hand portion (indices 63-125)
     * to detect changes even when only one hand is present.
     */
    private fun calculateArrayHash(array: FloatArray): Int {
        var hash = 0
        // Check first 10 values (left hand start)
        val leftCheck = minOf(10, array.size)
        for (i in 0 until leftCheck) {
            hash = 31 * hash + array[i].toBits()
        }
        // Also check right hand portion (starting at index 63 for two-hand vectors)
        // This ensures we detect changes even when left hand is empty
        if (array.size >= 126) {
            // Two-hand vector: check right hand portion
            val rightStart = 63
            val rightCheck = minOf(10, array.size - rightStart)
            for (i in rightStart until (rightStart + rightCheck)) {
                hash = 31 * hash + array[i].toBits()
            }
        } else if (array.size > 10) {
            // One-hand vector: check middle and end portions
            val midStart = array.size / 2
            val midCheck = minOf(10, array.size - midStart)
            for (i in midStart until (midStart + midCheck)) {
                hash = 31 * hash + array[i].toBits()
            }
        }
        return hash
    }
    
    /**
     * Check if two arrays are equal within tolerance
     */
    private fun arraysEqual(a: FloatArray, b: FloatArray, tolerance: Float = 0.0001f): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (kotlin.math.abs(a[i] - b[i]) > tolerance) return false
        }
        return true
    }

    private suspend fun ensureLabels() {
        if (labels.isNotEmpty()) return

        withContext(Dispatchers.IO) {
            if (labels.isNotEmpty()) return@withContext
            synchronized(labelLock) {
                if (labels.isNotEmpty()) return@synchronized
                val count = outputSizeHint.coerceAtLeast(1)
                labels = List(count) { index -> "CLASS_$index" }
                mappedLabels = labels
                if (interpreterClasses != count) {
                    interpreter = createInterpreter(count)
                    interpreterClasses = count
                }
            }
        }
    }

    private suspend fun obtainInterpreter(): TfLiteInterpreter {
        val desired = desiredNumClasses()
        val existing = interpreter
        if (existing != null && interpreterClasses == desired) return existing

        return withContext(Dispatchers.IO) {
            synchronized(labelLock) {
                val current = interpreter
                if (current != null && interpreterClasses == desired) {
                    current
                } else {
                    val fresh = createInterpreter(desired)
                    interpreter = fresh
                    interpreterClasses = desired
                    fresh
                }
            }
        }
    }

    private fun desiredNumClasses(): Int {
        val labelCount = labels.size
        if (labelCount > 0) return labelCount
        val cachedClasses = interpreterClasses
        if (cachedClasses > 0) return cachedClasses
        return outputSizeHint.coerceAtLeast(1)
    }

    private fun reconcileLabels(probsSize: Int) {
        val current = labels
        if (current.size == probsSize && current.isNotEmpty()) return

        synchronized(labelLock) {
            val refreshed = labels
            if (refreshed.size == probsSize && refreshed.isNotEmpty()) return@synchronized
            labels = List(probsSize) { index -> refreshed.getOrElse(index) { "CLASS_$index" } }
            mappedLabels = List(probsSize) { index -> 
                LabelMap.getMappedLabel(index).ifEmpty { labels[index] }
            }
            if (interpreterClasses != probsSize) {
                interpreter = createInterpreter(probsSize)
                interpreterClasses = probsSize
            }
        }
    }

    private fun createInterpreter(numClasses: Int): TfLiteInterpreter {
        // CRITICAL: Verify model name - should ALWAYS be FP32 now
        Log.i(TAG, "🔍 Creating TFLite interpreter with model: '$modelAsset'")
        if (!modelAsset.contains("expressora_unified_v19.tflite")) {
            Log.e(TAG, "❌ CRITICAL ERROR: Wrong model selected! Expected 'expressora_unified_v19.tflite' (FP32), got: '$modelAsset'")
        } else {
            Log.i(TAG, "✅ Model verified: FP32 model '$modelAsset' (Float32 inputs = 28440 bytes)")
        }
        Log.i(TAG, "Creating TFLite interpreter: featureDim=$featureDim, numClasses=$numClasses, modelVariant=$modelVariant")
        return TfLiteInterpreter(
            appContext, 
            modelAsset, 
            featureDim, 
            numClasses.coerceAtLeast(1),
            modelSignature
        )
    }

    private fun inspectOutputSize(): Int {
        return runCatching {
            val buffer = FileUtil.loadMappedFile(appContext, modelAsset)
            val interpreter = Interpreter(buffer)
            try {
                val shape = interpreter.getOutputTensor(0).shape()
                (shape.lastOrNull() ?: 1).coerceAtLeast(1)
            } finally {
                interpreter.close()
            }
        }.getOrElse { 1 }
    }
    
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp((it - maxLogit).toDouble()).toFloat() }
        val sumExps = exps.sum()
        return exps.map { it / sumExps }.toFloatArray()
    }
    
    /**
     * Log complete diagnostic summary after each inference cycle.
     * Provides a comprehensive view of the entire pipeline state.
     */
    private fun logDiagnosticSummary(
        rawOutput: FloatArray,
        averagedOutput: FloatArray,
        finalProbs: FloatArray,
        bestIndex: Int,
        bestConf: Float,
        rawLabel: String,
        mappedLabel: String,
        rollingBufferSize: Int
    ) {
        Log.e(TAG, "═══════════════════════════════════════════════════════════════")
        Log.e(TAG, "📋 DIAGNOSTIC SUMMARY - Complete Pipeline State")
        Log.e(TAG, "═══════════════════════════════════════════════════════════════")
        
        // Input Pipeline Summary
        Log.e(TAG, "📥 INPUT PIPELINE SUMMARY:")
        Log.e(TAG, "   → Feature extraction: MediaPipe landmarks → 237 features per frame")
        Log.e(TAG, "   → Z-coordinate normalization: clamped to [-1,1] then mapped to [0,1]")
        Log.e(TAG, "   → Feature scaling: [0,1] → [-1,1] using (value - 0.5) * 2.0")
        Log.e(TAG, "   → Buffer: 30 frames × 237 features = 7110 floats")
        Log.e(TAG, "   → ByteBuffer: 7110 floats × 4 bytes = 28440 bytes")
        
        // Model Input Summary
        Log.e(TAG, "🔬 MODEL INPUT SUMMARY:")
        Log.e(TAG, "   → Tensor shape: [1, 30, 237] (batch=1, time=30, features=237)")
        Log.e(TAG, "   → Data type: FLOAT32")
        Log.e(TAG, "   → Expected range: sentinels=-10.0, valid data in [-1.0, 1.0]")
        
        // Model Output Summary
        val rawMax = rawOutput.maxOrNull() ?: 0f
        val rawMin = rawOutput.minOrNull() ?: 0f
        val rawMean = rawOutput.average().toFloat()
        val rawStd = kotlin.math.sqrt(rawOutput.map { (it - rawMean) * (it - rawMean) }.average()).toFloat()
        val rawSum = rawOutput.sum()
        val rawEntropy = -rawOutput.sumOf { if (it > 0) it * kotlin.math.ln(it.toDouble()) else 0.0 }.toFloat()
        val maxEntropy = kotlin.math.ln(rawOutput.size.toDouble()).toFloat()
        
        Log.e(TAG, "📊 MODEL OUTPUT SUMMARY (raw from inference):")
        Log.e(TAG, "   → Range: [%.6f, %.6f] (span: %.6f)".format(rawMin, rawMax, rawMax - rawMin))
        Log.e(TAG, "   → Mean: %.6f, Std: %.6f".format(rawMean, rawStd))
        Log.e(TAG, "   → Sum: %.6f (if ~1.0, model outputs probabilities)".format(rawSum))
        Log.e(TAG, "   → Entropy: %.6f / %.6f (%.1f%%) - higher = more uniform".format(rawEntropy, maxEntropy, (rawEntropy / maxEntropy * 100)))
        
        // Post-Processing Summary
        val avgMax = averagedOutput.maxOrNull() ?: 0f
        val avgMin = averagedOutput.minOrNull() ?: 0f
        val avgMean = averagedOutput.average().toFloat()
        val avgStd = kotlin.math.sqrt(averagedOutput.map { (it - avgMean) * (it - avgMean) }.average()).toFloat()
        val avgSum = averagedOutput.sum()
        
        Log.e(TAG, "🔄 POST-PROCESSING SUMMARY:")
        Log.e(TAG, "   → Rolling buffer size: $rollingBufferSize")
        Log.e(TAG, "   → Averaged probabilities range: [%.6f, %.6f]".format(avgMin, avgMax))
        Log.e(TAG, "   → Averaged probabilities mean: %.6f, Std: %.6f".format(avgMean, avgStd))
        Log.e(TAG, "   → Averaged probabilities sum: %.6f".format(avgSum))
        
        // Final Prediction Summary
        val finalMax = finalProbs.maxOrNull() ?: 0f
        val finalMin = finalProbs.minOrNull() ?: 0f
        val finalMean = finalProbs.average().toFloat()
        val finalStd = kotlin.math.sqrt(finalProbs.map { (it - finalMean) * (it - finalMean) }.average()).toFloat()
        val finalSum = finalProbs.sum()
        
        Log.e(TAG, "🎯 FINAL PREDICTION SUMMARY:")
        Log.e(TAG, "   → Best index: $bestIndex")
        Log.e(TAG, "   → Best confidence: %.6f (%d%%)".format(bestConf, (bestConf * 100).toInt()))
        Log.e(TAG, "   → Raw label: '$rawLabel'")
        Log.e(TAG, "   → Mapped label: '$mappedLabel'")
        Log.e(TAG, "   → Final probabilities range: [%.6f, %.6f]".format(finalMin, finalMax))
        Log.e(TAG, "   → Final probabilities mean: %.6f, Std: %.6f".format(finalMean, finalStd))
        Log.e(TAG, "   → Final probabilities sum: %.6f".format(finalSum))
        
        // Top 5 Predictions
        val top5 = finalProbs.mapIndexed { idx, value -> idx to value }
            .sortedByDescending { it.second }
            .take(5)
        
        Log.e(TAG, "🏆 TOP 5 PREDICTIONS:")
        top5.forEachIndexed { rank, (idx, conf) ->
            val label = labels.getOrElse(idx) { "CLASS_$idx" }
            val mapped = mappedLabels.getOrElse(idx) { label }
            Log.e(TAG, "   ${rank + 1}. idx[$idx]: '$mapped' (raw: '$label') = %.6f (%d%%)".format(conf, (conf * 100).toInt()))
        }
        
        // Health Indicators
        Log.e(TAG, "💊 HEALTH INDICATORS:")
        val isUniform = (rawMax - rawMin) < 0.01f
        val isLowConfidence = bestConf < 0.5f
        val isHighEntropy = (rawEntropy / maxEntropy) > 0.9f
        val isSumCorrect = kotlin.math.abs(rawSum - 1.0f) < 0.01f
        
        Log.e(TAG, "   → Output uniformity: ${if (isUniform) "❌ UNIFORM (model collapse)" else "✅ VARIED"}")
        Log.e(TAG, "   → Confidence level: ${if (isLowConfidence) "❌ LOW (<50%)" else "✅ ACCEPTABLE"}")
        Log.e(TAG, "   → Entropy level: ${if (isHighEntropy) "❌ HIGH (uniform distribution)" else "✅ NORMAL"}")
        Log.e(TAG, "   → Sum check: ${if (isSumCorrect) "✅ ~1.0 (probabilities)" else "⚠️ NOT ~1.0 (logits)"}")
        
        Log.e(TAG, "═══════════════════════════════════════════════════════════════")
    }
    
    /**
     * Get the active delegate being used by the interpreter.
     * Call this after the interpreter has been initialized.
     */
    fun getActiveDelegate(): String {
        return interpreter?.activeDelegate ?: "CPU"
    }

    fun getModelVariant(): String = modelVariant
}

/**
 * Rolling buffer for averaging logits over last N frames
 */
private class RollingLogitsBuffer(private val size: Int) {
    private val buffer = mutableListOf<FloatArray>()
    
    @Synchronized
    fun add(logits: FloatArray) {
        buffer.add(logits.copyOf())
        if (buffer.size > size) {
            buffer.removeAt(0)
        }
    }
    
    @Synchronized
    fun getAverage(): FloatArray {
        if (buffer.isEmpty()) return FloatArray(0)
        
        val numClasses = buffer[0].size
        val avg = FloatArray(numClasses)
        
        for (logits in buffer) {
            for (i in logits.indices) {
                avg[i] += logits[i]
            }
        }
        
        val count = buffer.size.toFloat()
        for (i in avg.indices) {
            avg[i] /= count
        }
        
        return avg
    }
    
    @Synchronized
    fun clear() {
        buffer.clear()
    }
    
    @Synchronized
    fun size(): Int {
        return buffer.size
    }
    
    fun capacity(): Int {
        return size
    }
}
