package com.example.expressora.recognition.tflite

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.BuildConfig
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
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
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
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
    private val modelVariant: String = when {
        modelAsset.contains("int8", ignoreCase = true) -> "INT8"
        modelAsset.contains("fp16", ignoreCase = true) -> "FP16"
        else -> "FP32"
    }
    
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
        interpreter?.close()
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
                LogUtils.debugIfVerbose(TAG) { "Feature vector stats: size=${feature.size}, expected=$featureDim, " +
                        "nonZero=${featureStats.nonZeroCount}, min=${featureStats.min}, " +
                        "max=${featureStats.max}, mean=${featureStats.mean}, " +
                        "isAllZeros=${featureStats.isAllZeros}, hasNaN=${featureStats.hasNaN}" }
                
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
                
                // DEBUG: Log averaged logits (only in debug builds)
                val avgLogitsStats = calculateFeatureStats(avgLogits)
                LogUtils.debugIfVerbose(TAG) { "Averaged logits stats: size=${avgLogits.size}, " +
                        "min=${avgLogitsStats.min}, max=${avgLogitsStats.max}, mean=${avgLogitsStats.mean}" }
                
                // Apply softmax
                val probs = softmax(avgLogits)
                
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
        Log.i(TAG, "Creating TFLite interpreter: featureDim=$featureDim, numClasses=$numClasses")
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
}
