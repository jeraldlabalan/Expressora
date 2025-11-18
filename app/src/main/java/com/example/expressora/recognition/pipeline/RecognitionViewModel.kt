package com.example.expressora.recognition.pipeline

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.BuildConfig
import androidx.lifecycle.viewModelScope
import com.example.expressora.recognition.accumulator.AccumulatorState
import com.example.expressora.recognition.accumulator.SequenceAccumulator
import com.example.expressora.recognition.bus.GlossSequenceBus
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.GlossEvent
import com.example.expressora.recognition.model.RecognitionResult
import com.example.expressora.recognition.persistence.SequenceHistoryRepository
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.conflate

class RecognitionViewModel(
    private val engine: RecognitionEngine,
    private val context: Context
) : ViewModel() {
    private val TAG = "RecognitionViewModel"
    private val _state = MutableStateFlow<GlossEvent>(GlossEvent.Idle)
    
    // Throttled state flow for UI updates (~16 FPS = 62.5ms interval)
    // Convert to SharedFlow, throttle with sample, then back to StateFlow
    val state: StateFlow<GlossEvent> = _state
        .asSharedFlow()
        .sample(62L) // Sample every ~62ms for ~16 FPS
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _state.value)
    
    // Recognition result state (detailed) - NO throttling to ensure all updates reach UI
    // Using conflate() instead of sample() to ensure latest value is always emitted
    private val _recognitionResult = MutableStateFlow<RecognitionResult?>(null)
    val recognitionResult: StateFlow<RecognitionResult?> = _recognitionResult
        .asSharedFlow()
        .conflate() // Conflate to ensure latest value is always emitted (no throttling)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _recognitionResult.value)
    
    // Accumulator
    private val accumulator = SequenceAccumulator(viewModelScope)
    val accumulatorState: StateFlow<AccumulatorState> = accumulator.state
    
    // History repository
    private val historyRepository = SequenceHistoryRepository(context)
    
    // Last published event from bus
    private val _lastBusEvent = MutableStateFlow<String?>(null)
    val lastBusEvent: StateFlow<String?> = _lastBusEvent
    
    // Debouncing and temporal smoothing
    private var lastRecognitionLabel: String? = null
    private var stableFrameCount = 0
    private var debounceJob: Job? = null
    
    // Stuck detection tracking
    private var stuckDetectionStartTime: Long = 0L
    private var stuckDetectionLabel: String? = null
    private var stuckDetectionFrameCount = 0
    private val STUCK_DETECTION_THRESHOLD_MS = 3000L // 3 seconds
    private val STUCK_DETECTION_FRAME_THRESHOLD = 30 // 30 frames at ~10fps

    init {
        LogUtils.d(TAG) { "Initializing RecognitionViewModel" }
        viewModelScope.launch {
            LogUtils.d(TAG) { "Starting recognition engine" }
            engine.start()
            
            // Listen to basic events
            engine.events.collect { event ->
                LogUtils.verboseIfVerbose(TAG) { "Engine event received: $event" }
                _state.value = event
            }
        }
        
        // Listen to detailed recognition results if available
        if (engine is TfLiteRecognitionEngine) {
            LogUtils.d(TAG) { "TfLiteRecognitionEngine detected, subscribing to detailed results" }
            viewModelScope.launch {
                engine.results.collect { result ->
                    LogUtils.verboseIfVerbose(TAG) { "Recognition result received: label=${result.glossLabel}, conf=${result.glossConf}" }
                    processRecognitionResult(result)
                }
            }
        } else {
            Log.w(TAG, "Engine is not TfLiteRecognitionEngine, detailed results unavailable")
        }
        
        // Set up accumulator callbacks
        accumulator.onSequenceCommitted = { tokens ->
            viewModelScope.launch {
                LogUtils.d(TAG) { "Sequence committed: tokens=[${tokens.joinToString()}]" }
                // Gather origin and confidence from last recognition result
                val lastResult = _recognitionResult.value
                val origin = lastResult?.originLabel ?: "UNKNOWN"
                val confidence = lastResult?.glossConf ?: 0.0f
                
                LogUtils.d(TAG) { "Publishing sequence: tokens=${tokens.size}, origin=$origin, confidence=$confidence" }
                
                // TODO: Gather non-manual annotations from FaceLandmarkerEngine when integrated
                val nonmanuals = emptyList<com.example.expressora.recognition.bus.NonManualAnnotation>()
                
                // Publish to bus with full contract payload
                GlossSequenceBus.publishSequence(
                    tokens = tokens,
                    nonmanuals = nonmanuals,
                    origin = origin,
                    confidence = confidence
                )
                
                // Save to history
                LogUtils.d(TAG) { "Saving sequence to history: tokens=[${tokens.joinToString()}], origin=$origin" }
                historyRepository.saveSequence(tokens, origin)
                
                // Update last event display
                _lastBusEvent.value = "Sequence: ${tokens.joinToString(" ")} [$origin]"
            }
        }
        
        accumulator.onAlphabetWordCommitted = { word ->
            viewModelScope.launch {
                LogUtils.d(TAG) { "Alphabet word committed: word='$word'" }
                // Publish to bus
                GlossSequenceBus.publishAlphabetWord(word)
                
                // Update last event display
                _lastBusEvent.value = "Word: $word"
            }
        }
        
        // Listen to bus events
        viewModelScope.launch {
            GlossSequenceBus.events.collect { event ->
                val display = when (event) {
                    is com.example.expressora.recognition.bus.GlossSequenceEvent.GlossSequenceReady ->
                        "Sequence: ${event.tokens.joinToString(" ")}"
                    is com.example.expressora.recognition.bus.GlossSequenceEvent.AlphabetWordCommitted ->
                        "Word: ${event.word}"
                }
                _lastBusEvent.value = display
            }
        }
    }

    /**
     * Process recognition result with debouncing and temporal smoothing
     */
    private fun processRecognitionResult(result: RecognitionResult) {
        LogUtils.d(TAG) { "📥 Processing recognition result: label='${result.glossLabel}', conf=${result.glossConf} (${(result.glossConf * 100).toInt()}%), " +
                "origin='${result.originLabel}', originConf=${result.originConf}, " +
                "top3Probs=[${result.debugInfo?.softmaxProbs?.take(3)?.joinToString { "${it.label}=${(it.value * 100).toInt()}%" }}]" }
        
        val isHighConfidence = result.glossConf >= 0.75f
        val isSignChange = result.glossLabel != lastRecognitionLabel
        
        // CRITICAL: Also check if top 3 predictions changed (even if top label is same)
        // This helps detect when model output is changing but top prediction remains same
        val currentTop3 = result.debugInfo?.softmaxProbs?.take(3)?.map { it.label } ?: emptyList()
        val lastTop3 = _recognitionResult.value?.debugInfo?.softmaxProbs?.take(3)?.map { it.label } ?: emptyList()
        val top3Changed = currentTop3 != lastTop3
        
        if (isSignChange) {
            LogUtils.d(TAG) { "🔄 Sign changed: '${lastRecognitionLabel}' -> '${result.glossLabel}'" }
        }
        if (top3Changed && !isSignChange) {
            LogUtils.d(TAG) { "🔄 Top 3 predictions changed (same top label): " +
                    "was=[${lastTop3.joinToString()}], now=[${currentTop3.joinToString()}]" }
        }
        
        // Track stuck detection
        if (result.glossLabel == stuckDetectionLabel) {
            stuckDetectionFrameCount++
            val now = System.currentTimeMillis()
            if (stuckDetectionStartTime == 0L) {
                stuckDetectionStartTime = now
            }
            
            val stuckDuration = now - stuckDetectionStartTime
            if (stuckDuration > STUCK_DETECTION_THRESHOLD_MS || stuckDetectionFrameCount > STUCK_DETECTION_FRAME_THRESHOLD) {
                android.util.Log.w("RecognitionViewModel", 
                    "⚠️ DETECTION STUCK: Label '${result.glossLabel}' detected for ${stuckDuration}ms " +
                    "(${stuckDetectionFrameCount} frames), confidence=${result.glossConf}, " +
                    "topLogits=${result.debugInfo?.rawLogits?.take(3)?.joinToString { "${it.label}=${it.value}" }}")
            }
        } else {
            // Sign changed, reset stuck detection tracking
            if (stuckDetectionLabel != null && stuckDetectionFrameCount > 10) {
                android.util.Log.d("RecognitionViewModel", 
                    "Detection unstuck: was '${stuckDetectionLabel}' for ${stuckDetectionFrameCount} frames, " +
                    "now '${result.glossLabel}'")
            }
            stuckDetectionLabel = result.glossLabel
            stuckDetectionStartTime = System.currentTimeMillis()
            stuckDetectionFrameCount = 1
        }
        
        // Temporal smoothing: only accept stable predictions
        if (result.glossLabel == lastRecognitionLabel) {
            stableFrameCount++
        } else {
            stableFrameCount = 1
            lastRecognitionLabel = result.glossLabel
        }
        
        // CRITICAL: Always emit updates to ensure UI stays responsive
        // Even if model output is static, we want to show that it's processing
        // Allow immediate update if:
        // 1. High confidence (>= 75%) - more reliable, show immediately
        // 2. Sign changed significantly - reset and show new sign
        // 3. Top 3 predictions changed (even if top label is same) - model output is changing
        // 4. Confidence changed significantly (>= 5% change) - model is updating
        // 5. Otherwise, wait for MIN_STABLE_FRAMES (but still emit periodically)
        val lastConf = _recognitionResult.value?.glossConf ?: 0f
        val confChanged = kotlin.math.abs(result.glossConf - lastConf) >= 0.05f
        
        val shouldEmit = isHighConfidence || 
                        (isSignChange && stableFrameCount >= 1) ||
                        (top3Changed && stableFrameCount >= 1) || // Allow updates when top 3 changes
                        (confChanged && stableFrameCount >= 1) || // Allow updates when confidence changes
                        stableFrameCount >= PerformanceConfig.MIN_STABLE_FRAMES ||
                        stableFrameCount % 5 == 0 // Emit every 5 frames even if nothing changed (to show activity)
        
        if (shouldEmit) {
            // CRITICAL: Create a new RecognitionResult with updated timestamp to force UI recomposition
            // This ensures the UI updates even when the model output values are identical
            val resultWithNewTimestamp = result.copy(timestamp = System.currentTimeMillis())
            
            LogUtils.d(TAG) { "✅ UPDATING ViewModel state: label='${result.glossLabel}', conf=${result.glossConf}, " +
                    "stableFrames=$stableFrameCount, isHighConf=$isHighConfidence, isSignChange=$isSignChange, " +
                    "top3Changed=$top3Changed, confChanged=$confChanged, top3=[${currentTop3.joinToString()}]" }
            _recognitionResult.value = resultWithNewTimestamp
            
            // Debounce accumulator updates to avoid rapid-fire additions
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(50) // Reduced delay for more responsive detection
                LogUtils.verboseIfVerbose(TAG) { "Passing result to accumulator: label='${result.glossLabel}'" }
                accumulator.onRecognitionResult(result)
            }
        } else {
            LogUtils.d(TAG) { "⏸️ Skipping state update: stableFrames=$stableFrameCount (need ${PerformanceConfig.MIN_STABLE_FRAMES}), " +
                    "isHighConf=$isHighConfidence, isSignChange=$isSignChange, top3Changed=$top3Changed, " +
                    "currentLabel='${result.glossLabel}', top3=[${currentTop3.joinToString()}]" }
        }
    }
    
    fun onFeatures(vec: FloatArray) = viewModelScope.launch {
        val nonZeroCount = vec.count { it != 0f }
        // Log first few values (left hand, may be zeros) AND right hand portion (indices 63-67)
        val rightHandStart = if (vec.size >= 126) 63 else vec.size / 2
        val rightHandSample = if (vec.size > rightHandStart) {
            vec.slice(rightHandStart until minOf(rightHandStart + 5, vec.size))
        } else {
            emptyList<Float>()
        }
        LogUtils.d(TAG) { "📤 onFeatures called: vecSize=${vec.size}, nonZero=$nonZeroCount, " +
                "leftHand[0-4]=[${vec.take(5).joinToString()}], " +
                "rightHand[$rightHandStart-${rightHandStart + rightHandSample.size - 1}]=[${rightHandSample.joinToString()}]" }
        engine.onLandmarks(vec)
    }
    
    fun backspace() {
        LogUtils.d(TAG) { "Backspace requested" }
        accumulator.backspace()
    }
    
    fun clear() {
        LogUtils.d(TAG) { "Clear requested" }
        accumulator.clear()
    }
    
    fun send() {
        LogUtils.d(TAG) { "Send/commit requested" }
        accumulator.commitSequence()
    }
    
    fun copySequence(): String {
        val tokens = accumulator.getCurrentTokens()
        return """{"tokens": ${tokens.joinToString(prefix = "[\"", postfix = "\"]", separator = "\", \"")}}"""
    }
    
    suspend fun saveSequence() {
        val tokens = accumulator.getCurrentTokens()
        if (tokens.isNotEmpty()) {
            val origin = _recognitionResult.value?.originLabel
            historyRepository.saveSequence(tokens, origin)
        }
    }
    
    suspend fun exportLastSequence(): String? {
        return historyRepository.exportLastSequenceAsJson()
    }
    
    /**
     * Get the active delegate used by the TFLite classifier.
     */
    fun getClassifierDelegate(): String {
        return if (engine is TfLiteRecognitionEngine) {
            engine.getActiveDelegate()
        } else {
            "Unknown"
        }
    }

    fun getClassifierModelVariant(): String {
        return if (engine is TfLiteRecognitionEngine) {
            engine.getModelVariant()
        } else {
            "UNKNOWN"
        }
    }

    /**
     * Stop the recognition engine and cleanup resources.
     * Can be called explicitly for cleanup, or will be called automatically in onCleared().
     */
    suspend fun stop() {
        LogUtils.d(TAG) { "Stopping recognition engine" }
        try {
            engine.stop()
        } catch (e: Exception) {
            LogUtils.w(TAG) { "Error stopping engine: ${e.message}" }
        }
    }
    
    override fun onCleared() {
        LogUtils.d(TAG) { "RecognitionViewModel cleared" }
        viewModelScope.launch { 
            try {
                engine.stop()
            } catch (e: Exception) {
                LogUtils.w(TAG) { "Error stopping engine in onCleared: ${e.message}" }
            }
        }
        super.onCleared()
    }
}