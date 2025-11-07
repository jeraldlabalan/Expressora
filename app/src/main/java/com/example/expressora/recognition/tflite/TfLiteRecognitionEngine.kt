package com.example.expressora.recognition.tflite

import android.content.Context
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.GlossEvent
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

class TfLiteRecognitionEngine(
    private val context: Context,
    private val modelAsset: String,
    private val labelAsset: String,
    private val featureDim: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : RecognitionEngine {

    private val appContext = context.applicationContext
    private val labelLock = Any()

    @Volatile
    private var labels: List<String> = LabelMap.load(appContext, labelAsset)

    @Volatile
    private var interpreterClasses: Int = 0

    @Volatile
    private var interpreter: TfLiteInterpreter? = null

    private val outputSizeHint: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { inspectOutputSize() }

    private val _events = MutableSharedFlow<GlossEvent>(replay = 1)
    override val events: Flow<GlossEvent> = _events.asSharedFlow()

    override suspend fun start() {
        _events.emit(GlossEvent.Idle)
    }

    override suspend fun stop() { /* no-op */ }

    override suspend fun onLandmarks(feature: FloatArray) {
        scope.launch {
            try {
                ensureLabels()
                val tflite = obtainInterpreter()
                val probabilities = tflite.run(feature)
                reconcileLabels(probabilities.size)
                val currentLabels = labels
                val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                val token = currentLabels.getOrElse(bestIndex) { "CLASS_$bestIndex" }
                _events.emit(GlossEvent.InProgress(listOf(token)))
            } catch (t: Throwable) {
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
            if (interpreterClasses != probsSize) {
                interpreter = createInterpreter(probsSize)
                interpreterClasses = probsSize
            }
        }
    }

    private fun createInterpreter(numClasses: Int): TfLiteInterpreter {
        return TfLiteInterpreter(appContext, modelAsset, featureDim, numClasses.coerceAtLeast(1))
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
}