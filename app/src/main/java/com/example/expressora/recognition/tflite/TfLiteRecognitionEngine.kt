package com.example.expressora.recognition.tflite

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.example.expressora.recognition.config.PerformanceConfig
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
                
                // Check if we can use cached result
                if (PerformanceConfig.ENABLE_RESULT_CACHING) {
                    val now = SystemClock.elapsedRealtime()
                    val cached = cachedResult
                    if (cached != null && (now - cacheTimestamp) < PerformanceConfig.CACHE_DURATION_MS) {
                        _results.emit(cached)
                        _events.emit(GlossEvent.InProgress(listOf(cached.glossLabel)))
                        return@launch
                    }
                }
                
                ensureLabels()
                val tflite = obtainInterpreter()
                val result = tflite.runMultiOutput(feature)
                
                reconcileLabels(result.glossLogits.size)
                
                // Add to rolling buffer and get averaged logits
                rollingBuffer.add(result.glossLogits)
                val avgLogits = rollingBuffer.getAverage()
                
                // Apply softmax
                val probs = softmax(avgLogits)
                
                // Get top prediction
                val bestIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
                val bestConf = probs[bestIndex]
                val rawLabel = labels.getOrElse(bestIndex) { "CLASS_$bestIndex" }
                val mappedLabel = mappedLabels.getOrElse(bestIndex) { rawLabel }
                
                // Resolve origin
                val originBadge = if (result.originLogits != null) {
                    val originProbs = softmax(result.originLogits)
                    OriginResolver.resolveFromMultiHead(originLabels, originProbs)
                } else {
                    OriginResolver.resolveFromPriors(rawLabel)
                }
                
                // Emit detailed result
                val recognitionResult = RecognitionResult(
                    glossLabel = mappedLabel,
                    glossConf = bestConf,
                    glossIndex = bestIndex,
                    originLabel = originBadge.origin,
                    originConf = originBadge.confidence
                )
                
                // Cache stable results
                if (PerformanceConfig.ENABLE_RESULT_CACHING && bestConf >= 0.8f) {
                    cachedResult = recognitionResult
                    cacheTimestamp = SystemClock.elapsedRealtime()
                }
                
                _results.emit(recognitionResult)
                
                // Emit simple event for backward compatibility
                _events.emit(GlossEvent.InProgress(listOf(mappedLabel)))
                
            } catch (t: Throwable) {
                Log.e(TAG, "Recognition error", t)
                _events.emit(GlossEvent.Error(t.message ?: "Recognition failure"))
            }
        }
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
