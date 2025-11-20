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
import com.example.expressora.recognition.grpc.LandmarkStreamer
import com.example.expressora.grpc.RecognitionEvent
import com.example.expressora.grpc.TranslationResult
import com.example.expressora.recognition.model.GlossEvent
import com.example.expressora.recognition.model.RecognitionResult
import com.example.expressora.recognition.model.DebugInfo
import com.example.expressora.recognition.model.TopPrediction
import com.example.expressora.recognition.model.FeatureVectorStats
import com.example.expressora.recognition.persistence.SequenceHistoryRepository
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor
import com.example.expressora.recognition.feature.MotionVarianceDetector
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecognitionViewModel(
    private val engine: RecognitionEngine? = null, // Optional for offline mode (Tweak 3)
    private val streamer: LandmarkStreamer? = null, // Optional for online mode
    private val context: Context,
    private val useOnlineMode: Boolean = true // Tweak 3: Hybrid Intelligence - toggle between modes
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
    
    // Client-managed gloss list (for user editing/deletion)
    private val _glossList = MutableStateFlow<List<String>>(emptyList())
    val glossList: StateFlow<List<String>> = _glossList.asStateFlow()
    
    // Current detected tone (from face, not added to gloss list)
    private val _currentTone = MutableStateFlow<String>("/neutral")
    val currentTone: StateFlow<String> = _currentTone.asStateFlow()
    
    // Camera scanning state (paused during translation)
    private val _isScanning = MutableStateFlow<Boolean>(true)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    /**
     * Set scanning state (used by lifecycle observer to stop camera on pause).
     */
    fun setIsScanning(value: Boolean) {
        _isScanning.value = value
    }
    
    // Translation result (from TranslateSequence)
    private val _translationResult = MutableStateFlow<TranslationResult?>(null)
    val translationResult: StateFlow<TranslationResult?> = _translationResult.asStateFlow()
    
    // Translation loading state
    private val _isTranslating = MutableStateFlow<Boolean>(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()
    
    // Trigger Discipline: Time-based restrictions to prevent spam detection
    private var lastDetectedGloss: String? = null
    private var lastDetectionTime: Long = 0L
    private val smoothedConfidenceMap = java.util.concurrent.ConcurrentHashMap<String, Float>()
    
    companion object {
        private const val MAX_SENTENCE_LENGTH = 7
        private const val SAME_SIGN_COOLDOWN_MS = 1500L // 1.5s for same sign
        private const val TRANSITION_COOLDOWN_MS = 600L  // 0.6s between different signs
        private const val MIN_SMOOTHED_CONFIDENCE = 0.65f // Threshold after smoothing
    }
    
    // Accumulator
    private val accumulator = SequenceAccumulator(viewModelScope)
    val accumulatorState: StateFlow<AccumulatorState> = accumulator.state
    
    // Variance detector for pre-filtering noise before sending to server (optional bandwidth optimization)
    private val varianceDetector = MotionVarianceDetector(bufferSize = 10, varianceThreshold = 0.01f)
    
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
        LogUtils.d(TAG) { "Initializing RecognitionViewModel (useOnlineMode=$useOnlineMode)" }
        
        // CRITICAL: Observe accumulator state and sync to UI in real-time
        // Accumulator is the SINGLE SOURCE OF TRUTH - all modifications must go through it
        viewModelScope.launch {
            accumulatorState.collect { state ->
                // Always mirror the accumulator's tokens to the UI
                // Use direct assignment (not update) since accumulator is the source of truth
                val oldTokens = _glossList.value
                _glossList.value = state.tokens
                if (oldTokens != state.tokens) {
                    LogUtils.i(TAG) { "🔄🔄🔄 UI Synced to Accumulator: ${oldTokens.size} -> ${state.tokens.size} items, tokens=[${state.tokens.joinToString(", ")}]" }
                }
            }
        }
        
        if (useOnlineMode && streamer != null) {
            // Online mode: Use gRPC streaming
            LogUtils.d(TAG) { "Using ONLINE mode with gRPC streaming" }
            viewModelScope.launch {
                // Connect to server
                streamer.connect()
                
                // Listen to recognition events (GLOSS, TONE, HANDS_DOWN)
                streamer.recognitionEvents.collect { event ->
                    LogUtils.d(TAG) { "Recognition event received: type=${event.type}, label='${event.label}', confidence=${event.confidence}" }
                    
                    when (event.type) {
                        RecognitionEvent.Type.GLOSS -> {
                            LogUtils.d(TAG) { "📥 Server sent GLOSS: '${event.label}' (conf=${event.confidence}) - Using single-shot (instant but safe)" }
                            
                            // Server responses are already validated (multi-frame validation on server)
                            // Use single-shot method: instant acceptance but debounces duplicates
                            accumulator.onSingleShotResult(event.label)
                            
                            LogUtils.d(TAG) { "✅ Token processed via single-shot, current tokens: ${accumulatorState.value.tokens.size}" }
                        }
                        RecognitionEvent.Type.TONE -> {
                            // Handle tone events (if needed)
                            LogUtils.d(TAG) { "Tone detected: ${event.label}" }
                        }
                        RecognitionEvent.Type.HANDS_DOWN -> {
                            // Reset accumulator when hands are down
                            accumulator.clear()
                        }
                        else -> {
                            // Handle any unrecognized types (e.g., UNRECOGNIZED from protobuf)
                            LogUtils.w(TAG) { "Unrecognized event type: ${event.type}" }
                        }
                    }
                }
                
                // Listen to connection state
                streamer.connectionState.collect { state ->
                    // Always check isTranslating FIRST to prevent race conditions
                    val currentlyTranslating = _isTranslating.value
                    Log.d(TAG, "📡 Connection state changed: $state, isTranslating=$currentlyTranslating")
                    
                    when (state) {
                        LandmarkStreamer.ConnectionState.CONNECTED -> {
                            if (!currentlyTranslating) {
                                _state.value = GlossEvent.Idle
                            }
                        }
                        LandmarkStreamer.ConnectionState.ERROR,
                        LandmarkStreamer.ConnectionState.DISCONNECTED -> {
                            // CRITICAL: Don't show error during translation - connection is intentionally stopped
                            // This prevents activity exit when stopStreaming() is called
                            if (!currentlyTranslating) {
                                Log.i(TAG, "⚠️ Connection lost when not translating - showing error")
                                _state.value = GlossEvent.Error("Connection lost. Switch to offline mode?")
                            } else {
                                Log.i(TAG, "✅ Connection state changed to DISCONNECTED during translation - ignoring (expected behavior, prevents activity exit)")
                            }
                        }
                        LandmarkStreamer.ConnectionState.CONNECTING -> {
                            if (!currentlyTranslating) {
                                _state.value = GlossEvent.Idle
                            }
                        }
                    }
                }
            }
        } else if (engine != null) {
            // Offline mode: Use TFLite engine (Tweak 3 - Fallback)
            LogUtils.d(TAG) { "Using OFFLINE mode with TFLite engine" }
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
        } else {
            Log.e(TAG, "Neither streamer nor engine provided! Cannot initialize.")
            _state.value = GlossEvent.Error("No recognition backend available")
        }
        
        // Set up accumulator callbacks
        accumulator.onSequenceCommitted = { tokens ->
            viewModelScope.launch {
                LogUtils.d(TAG) { "Sequence committed: tokens=[${tokens.joinToString()}]" }
                
                // NOTE: _glossList is automatically synced by the state observer in init
                // No need to manually update it here - accumulator state observer handles UI updates
                
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
    
    /**
     * Handle landmarks from HolisticLandmarkerEngine (Online mode)
     * Only processes if isScanning is true (camera not paused).
     */
    fun onLandmarks(
        result: HolisticLandmarkerResult,
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int
    ) = viewModelScope.launch {
        try {
            // Check if scanning is paused (during translation)
            val isScanning = _isScanning.value
            if (!isScanning) {
                LogUtils.v(TAG) { "⏸️ Skipping frame - scanning paused (isScanning=false)" }
                return@launch // Skip processing while translation result is showing
            }
            
            if (useOnlineMode && streamer != null) {
            // Additional safety check: verify connection is ready before sending
            val isConnected = streamer.isConnected()
            if (!isConnected) {
                LogUtils.d(TAG) { "⚠️ Skipping frame send - stream not connected yet (waiting for connection). isConnected=$isConnected" }
                return@launch
            }
            
            // Optional: Variance pre-filter to skip noise frames (bandwidth optimization)
            val leftHandLandmarks = result.leftHandLandmarks()
            val rightHandLandmarks = result.rightHandLandmarks()
            val wristX = leftHandLandmarks?.get(0)?.x() ?: rightHandLandmarks?.get(0)?.x()
            val wristY = leftHandLandmarks?.get(0)?.y() ?: rightHandLandmarks?.get(0)?.y()
            
            if (wristX != null && wristY != null) {
                varianceDetector.addWristPosition(wristX, wristY)
                
                // Only send if variance indicates meaningful motion (skip noise)
                if (varianceDetector.hasEnoughData()) {
                    val variance = varianceDetector.getVariance()
                    if (variance < 0.001f) {
                        // Very low variance = static/noise, skip sending
                        LogUtils.v(TAG) { "Skipping frame: low variance ($variance) - likely noise" }
                        return@launch
                    }
                }
            }
            
            // RESTORE: Stream skeleton to Python server for recognition
            streamer.sendLandmarks(result, timestampMs, imageWidth, imageHeight)
        } else {
            // OFFLINE MODE: Process landmarks locally using TfLiteRecognitionEngine
            if (engine == null) {
                Log.w(TAG, "onLandmarks called in offline mode but engine not available")
                return@launch
            }
            
            // Process on background thread to avoid blocking
            withContext(Dispatchers.Default) {
                try {
                    // Extract hand landmarks from HolisticLandmarkerResult
                    val leftHandLandmarks = result.leftHandLandmarks()
                    val rightHandLandmarks = result.rightHandLandmarks()
                    
                    val hasLeftHand = leftHandLandmarks != null && leftHandLandmarks.isNotEmpty()
                    val hasRightHand = rightHandLandmarks != null && rightHandLandmarks.isNotEmpty()
                    
                    if (!hasLeftHand && !hasRightHand) {
                        LogUtils.v(TAG) { "⏸️ No hands detected in offline mode, skipping" }
                        return@withContext
                    }
                    
                    // Convert NormalizedLandmark to Point3
                    val leftPoints = leftHandLandmarks?.map { landmark ->
                        LandmarkFeatureExtractor.Point3(landmark.x(), landmark.y(), landmark.z())
                    }
                    val rightPoints = rightHandLandmarks?.map { landmark ->
                        LandmarkFeatureExtractor.Point3(landmark.x(), landmark.y(), landmark.z())
                    }
                    
                    // Create Hands object and convert to feature vector
                    // Always use two-hand format (126 dims) - missing hands are zero-padded by extractor
                    // The twoHands parameter is deprecated but kept for API compatibility
                    val hands = HandToFeaturesBridge.Hands(left = leftPoints, right = rightPoints)
                    val featureVector = HandToFeaturesBridge.toVec(hands, twoHands = true)
                    
                    // Pass feature vector to engine for classification
                    engine.onLandmarks(featureVector)
                    
                    LogUtils.v(TAG) { "✅ Offline recognition: processed ${featureVector.size}D features (left=${hasLeftHand}, right=${hasRightHand})" }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing landmarks in offline mode", e)
                }
            }
            }
        } catch (e: Exception) {
            // Prevent app crash if server disconnects or network error occurs
            LogUtils.e(TAG) { "⚠️ Error in onLandmarks (server likely disconnected): ${e.message}" }
            // Don't rethrow - allow app to continue functioning
        }
    }
    
    /**
     * Handle feature vector from HandToFeaturesBridge (Offline mode - Tweak 3)
     */
    fun onFeatures(vec: FloatArray) = viewModelScope.launch {
        if (engine == null) {
            Log.w(TAG, "onFeatures called but engine not available (online mode?)")
            return@launch
        }
        
        val nonZeroCount = vec.count { it != 0f }
        // Log first few values (left hand, may be zeros) AND right hand portion (indices 63-67)
        val rightHandStart = if (vec.size >= 126) 63 else vec.size / 2
        val rightHandSample = if (vec.size > rightHandStart) {
            vec.slice(rightHandStart until minOf(rightHandStart + 5, vec.size))
        } else {
            emptyList<Float>()
        }
        LogUtils.d(TAG) { "📤 onFeatures called (OFFLINE): vecSize=${vec.size}, nonZero=$nonZeroCount, " +
                "leftHand[0-4]=[${vec.take(5).joinToString()}], " +
                "rightHand[$rightHandStart-${rightHandStart + rightHandSample.size - 1}]=[${rightHandSample.joinToString()}]" }
        engine.onLandmarks(vec)
    }
    
    /**
     * Process recognition event (GLOSS, TONE, or HANDS_DOWN).
     * Heavy processing moved to background thread to prevent UI lag.
     */
    private fun processRecognitionEvent(event: RecognitionEvent) {
        when (event.type) {
            RecognitionEvent.Type.GLOSS -> {
                // Move heavy list processing to background thread to prevent UI lag
                viewModelScope.launch(Dispatchers.Default) {
                    val currentTime = System.currentTimeMillis()
                    val newGloss = event.label
                    val rawConfidence = event.confidence
                    val currentList = _glossList.value
                    
                    // --- STEP 1: ALWAYS SMOOTH CONFIDENCE FIRST (Fixes the "Stale Math" bug) ---
                    // We process the math regardless of whether we show the sign yet
                    // This keeps the EMA accurate during cooldown periods
                    val prevSmoothed = smoothedConfidenceMap[newGloss] ?: rawConfidence
                    val smoothedScore = (rawConfidence * 0.7f) + (prevSmoothed * 0.3f)
                    smoothedConfidenceMap[newGloss] = smoothedScore
                    LogUtils.d(TAG) { "📊 Confidence smoothing: '$newGloss' raw=$rawConfidence, prevSmoothed=$prevSmoothed, smoothed=$smoothedScore" }
                    
                    // Optional: If the smoothed score is too low, ignore it immediately
                    // This filters out "flickering" weak detections
                    if (smoothedScore < MIN_SMOOTHED_CONFIDENCE) {
                        LogUtils.v(TAG) { "🚫 GLOSS filtered (low smoothed confidence): '$newGloss' (raw: $rawConfidence, smoothed: $smoothedScore < $MIN_SMOOTHED_CONFIDENCE)" }
                        return@launch
                    }
                    
                    // --- STEP 2: SENTENCE CAP CHECK ---
                    if (currentList.size >= MAX_SENTENCE_LENGTH) {
                        LogUtils.d(TAG) { "🚫 GLOSS rejected (sentence limit reached): '$newGloss' (list size: ${currentList.size} >= $MAX_SENTENCE_LENGTH)" }
                        return@launch
                    }
                    
                    // --- STEP 3: TRANSITION COOLDOWN (Global) ---
                    // Prevent any new sign if we JUST added one (prevents rapid-fire errors)
                    if (currentTime - lastDetectionTime < TRANSITION_COOLDOWN_MS) {
                        val remainingMs = TRANSITION_COOLDOWN_MS - (currentTime - lastDetectionTime)
                        LogUtils.v(TAG) { "🚫 GLOSS rejected (transition cooldown): '$newGloss' (${remainingMs}ms remaining, lastDetectionTime=$lastDetectionTime, currentTime=$currentTime)" }
                        return@launch
                    }
                    LogUtils.d(TAG) { "✅ Transition cooldown passed: '$newGloss' (timeSinceLast=${currentTime - lastDetectionTime}ms >= ${TRANSITION_COOLDOWN_MS}ms)" }
                    
                    // --- STEP 4: SAME-SIGN DEBOUNCE ---
                    // If it's the same sign, require a long pause (1.5s)
                    if (newGloss == lastDetectedGloss) {
                        if (currentTime - lastDetectionTime < SAME_SIGN_COOLDOWN_MS) {
                            val remainingMs = SAME_SIGN_COOLDOWN_MS - (currentTime - lastDetectionTime)
                            LogUtils.v(TAG) { "🚫 GLOSS rejected (same-sign cooldown): '$newGloss' (${remainingMs}ms remaining, lastDetectedGloss='$lastDetectedGloss')" }
                            return@launch
                        }
                        LogUtils.d(TAG) { "✅ Same-sign cooldown passed: '$newGloss' (timeSinceLast=${currentTime - lastDetectionTime}ms >= ${SAME_SIGN_COOLDOWN_MS}ms)" }
                    } else {
                        LogUtils.d(TAG) { "✅ Different sign detected: '$newGloss' (was '$lastDetectedGloss')" }
                    }
                    
                    // --- STEP 5: COMMIT ---
                    // If we passed all guards, accept the sign
                    LogUtils.d(TAG) { "📝 Before StateFlow update: currentList.size=${currentList.size}, newGloss='$newGloss'" }
                    lastDetectedGloss = newGloss
                    lastDetectionTime = currentTime
                    
                    LogUtils.d(TAG) { "✅ GLOSS accepted: '$newGloss' (raw: $rawConfidence, smoothed: $smoothedScore)" }
                    
                    // Convert to RecognitionResult for UI compatibility
                    val recognitionResult = RecognitionResult(
                        glossLabel = newGloss,
                        glossConf = smoothedScore, // Use smoothed confidence for UI
                        glossIndex = 0,
                        originLabel = null,
                        originConf = null,
                        timestamp = currentTime,
                        debugInfo = DebugInfo(
                            featureVectorStats = FeatureVectorStats(
                                size = 0,
                                expectedSize = 0,
                                nonZeroCount = 0,
                                min = 0f,
                                max = 0f,
                                mean = 0f,
                                isAllZeros = false,
                                hasNaN = false
                            ),
                            rawLogits = listOf(TopPrediction(0, newGloss, rawConfidence)),
                            softmaxProbs = listOf(TopPrediction(0, newGloss, smoothedScore)),
                            averagedLogits = null
                        )
                    )
                    
                    // Emit to UI on main thread (atomic update using StateFlow.update for guaranteed recomposition)
                    withContext(Dispatchers.Main) {
                        _glossList.update { currentList ->
                            val oldSize = currentList.size
                            val newList = currentList + newGloss
                            Log.d(TAG, "✅ Gloss list updated: oldSize=$oldSize -> newSize=${newList.size}, items=[${newList.joinToString(", ")}]")
                            newList
                        }
                        processRecognitionResult(recognitionResult)
                        
                        // Auto-translate removed: Users should manually trigger translation
                        // to review and delete incorrect glosses before translating
                    }
                }
            }
            
            RecognitionEvent.Type.TONE -> {
                // Tone updates are lightweight, but still use atomic update
                val tone = event.label
                _currentTone.value = tone
                LogUtils.d(TAG) { "😊 TONE event processed: '$tone' (confidence: ${event.confidence}) - NOT triggering translation (manual only)" }
            }
            
            RecognitionEvent.Type.HANDS_DOWN -> {
                // Informational only - do NOT trigger translation
                // User may be deleting, so we don't auto-translate
                LogUtils.d(TAG) { "👋 HANDS_DOWN event detected (informational only, no action)" }
            }
            
            else -> {
                Log.w(TAG, "Unknown recognition event type: ${event.type}")
            }
        }
    }
    
    /**
     * Remove the last gloss from the list (LIFO - Last In First Out).
     * Since gloss sequence order matters, users can only delete the most recently added gloss.
     */
    fun removeLastGloss() {
        // Remove from the accumulator. The observer in init will 
        // automatically see this change and update _glossList.
        accumulator.backspace()
        LogUtils.d(TAG) { "✅ Removed last gloss via accumulator.backspace()" }
    }
    
    /**
     * Confirm translation: pause camera, disconnect stream, call TranslateSequence, show result.
     */
    fun confirmTranslation() {
        // Stack trace logging to identify callers
        val stackTrace = Thread.currentThread().stackTrace.take(5).joinToString("\n") { 
            "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        Log.i(TAG, "🔄 confirmTranslation() called from:\n$stackTrace")
        
        // Guard check to prevent translation if already translating
        if (_isTranslating.value) {
            Log.w(TAG, "⚠️ confirmTranslation() called while already translating - ignoring")
            return
        }
        
        if (!useOnlineMode || streamer == null) {
            Log.w(TAG, "❌ Cannot translate: not in online mode or streamer not available (useOnlineMode=$useOnlineMode, streamer=${if (streamer == null) "null" else "not null"})")
            return
        }
        
        val glosses = _glossList.value
        if (glosses.isEmpty()) {
            Log.w(TAG, "❌ Cannot translate: gloss list is empty")
            return
        }
        
        Log.i(TAG, "✅ Starting translation process: ${glosses.size} glosses, current tone=${_currentTone.value}")
        Log.i(TAG, "📋 Gloss list: ${glosses.joinToString(", ")}")
        
        // Set loading state IMMEDIATELY (synchronously) before launching coroutine
        // This ensures UI shows ModalBottomSheet instantly with loading state
        Log.i(TAG, "⏳ Step 1/5: Setting isTranslating = true (SYNCHRONOUSLY)")
        _isTranslating.value = true
        Log.i(TAG, "✅ isTranslating state updated: ${_isTranslating.value}")
        
        // Pause camera scanning IMMEDIATELY
        Log.i(TAG, "⏸️ Step 2/5: Pausing camera scanning (isScanning = false) (SYNCHRONOUSLY)")
        _isScanning.value = false
        Log.i(TAG, "✅ isScanning state updated: ${_isScanning.value}")
        
        viewModelScope.launch {
            try {
                // Stop gRPC stream to prevent interference with TranslateSequence (keep channel for blocking call)
                Log.i(TAG, "🛑 Step 3/5: Stopping gRPC stream before translation")
                streamer?.stopStreaming()
                Log.i(TAG, "✅ gRPC stream stopped (channel kept alive for blocking call)")
                
                val tone = _currentTone.value
                // Get origin from last recognition result (for code-switching support)
                val origin = _recognitionResult.value?.originLabel
                Log.i(TAG, "📞 Step 4/5: Calling TranslateSequence: ${glosses.size} glosses, tone=$tone, origin=$origin")
                
                // Call translation service with origin for code-switching support
                val startTime = System.currentTimeMillis()
                val result = streamer.translateSequence(glosses, tone, origin)
                val duration = System.currentTimeMillis() - startTime
                
                Log.i(TAG, "⏱️ TranslateSequence completed in ${duration}ms")
                
                // Update translation result
                Log.i(TAG, "💾 Step 5/5: Updating translation result")
                _translationResult.value = result
                Log.i(TAG, "✅ Translation result state updated: sentence='${result.sentence}', source='${result.source}'")
                
                Log.i(TAG, "🎉 Translation process completed successfully: '${result.sentence}' (source: ${result.source})")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Translation failed with exception: ${e.message}", e)
                Log.e(TAG, "📊 Exception type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                
                // Show error result
                val errorResult = TranslationResult.newBuilder()
                    .setSentence("Translation error: ${e.message}")
                    .setSource("Offline (Local)")
                    .build()
                _translationResult.value = errorResult
                Log.i(TAG, "💾 Error result set in translationResult state")
            } finally {
                // Always reset loading state
                Log.i(TAG, "🔄 Finally block: Resetting isTranslating = false")
                _isTranslating.value = false
                Log.i(TAG, "✅ isTranslating state reset: ${_isTranslating.value}")
            }
        }
    }
    
    /**
     * Resume scanning: clear state, reconnect stream, and resume camera.
     */
    fun resumeScanning() {
        Log.i(TAG, "🔄 resumeScanning() called")
        Log.i(TAG, "📊 Current state before resume: glossList.size=${_glossList.value.size}, translationResult=${if (_translationResult.value == null) "null" else "not null"}, isScanning=${_isScanning.value}, isTranslating=${_isTranslating.value}")
        
        Log.i(TAG, "🧹 Step 1/4: Clearing gloss list and resetting trigger discipline state")
        _glossList.value = emptyList()
        lastDetectedGloss = null
        lastDetectionTime = 0L
        smoothedConfidenceMap.clear()
        Log.i(TAG, "✅ Gloss list cleared: size=${_glossList.value.size}, trigger discipline state reset")
        
        Log.i(TAG, "🧹 Step 2/4: Clearing translation result")
        _translationResult.value = null
        Log.i(TAG, "✅ Translation result cleared: ${if (_translationResult.value == null) "null" else "not null"}")
        
        Log.i(TAG, "🔄 Step 3/4: Resetting tone to /neutral")
        _currentTone.value = "/neutral"
        Log.i(TAG, "✅ Tone reset: ${_currentTone.value}")
        
        // Reconnect gRPC stream FIRST, then resume scanning (prevents race condition)
        if (useOnlineMode && streamer != null) {
            Log.i(TAG, "🔌 Step 4/5: Reconnecting gRPC stream before resuming scanning")
            viewModelScope.launch {
                try {
                    val currentStreamer = streamer
                    if (currentStreamer == null) {
                        Log.w(TAG, "⚠️ Streamer is null - resuming scanning without connection")
                        _isScanning.value = true
                        return@launch
                    }
                    
                    Log.i(TAG, "📡 Calling streamer.connect()...")
                    currentStreamer.connect()
                    
                    // Wait for connection to be established before resuming scanning
                    Log.i(TAG, "⏳ Waiting for connection to be established...")
                    currentStreamer.connectionState
                        .filter { it == LandmarkStreamer.ConnectionState.CONNECTED }
                        .first() // Wait for first CONNECTED state
                    
                    // Now safe to resume scanning
                    Log.i(TAG, "✅ Step 5/5: Stream connected - resuming camera scanning (isScanning = true)")
                    _isScanning.value = true
                    Log.i(TAG, "✅ isScanning state updated: ${_isScanning.value}")
                    Log.i(TAG, "🎉 Stream connected and scanning resumed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to reconnect stream: ${e.message}", e)
                    Log.e(TAG, "📊 Reconnection exception type: ${e.javaClass.simpleName}")
                    e.printStackTrace()
                    // Still resume scanning even if connection fails (graceful degradation)
                    Log.i(TAG, "⚠️ Resuming scanning despite connection failure (graceful degradation)")
                    _isScanning.value = true
                }
            }
        } else {
            // Offline mode - resume immediately
            Log.i(TAG, "▶️ Step 4/4: Resuming camera scanning (offline mode, isScanning = true)")
            _isScanning.value = true
            Log.i(TAG, "✅ isScanning state updated: ${_isScanning.value}")
            Log.w(TAG, "⚠️ Skipping stream reconnection: useOnlineMode=$useOnlineMode, streamer=${if (streamer == null) "null" else "not null"}")
        }
        
        Log.i(TAG, "🎉 Resume scanning process completed")
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
     * Get the active delegate used by the TFLite classifier (offline mode only).
     */
    fun getClassifierDelegate(): String {
        return if (engine is TfLiteRecognitionEngine) {
            engine.getActiveDelegate()
        } else if (useOnlineMode) {
            "gRPC Streaming"
        } else {
            "Unknown"
        }
    }

    fun getClassifierModelVariant(): String {
        return if (engine is TfLiteRecognitionEngine) {
            engine.getModelVariant()
        } else if (useOnlineMode) {
            "Server-Side"
        } else {
            "UNKNOWN"
        }
    }

    /**
     * Stop the recognition engine/streamer and cleanup resources.
     * Can be called explicitly for cleanup, or will be called automatically in onCleared().
     */
    suspend fun stop() {
        LogUtils.d(TAG) { "Stopping recognition backend" }
        try {
            if (useOnlineMode && streamer != null) {
                streamer.disconnect()
            } else if (engine != null) {
                engine.stop()
            }
        } catch (e: Exception) {
            LogUtils.w(TAG) { "Error stopping backend: ${e.message}" }
        }
    }
    
    override fun onCleared() {
        LogUtils.d(TAG) { "RecognitionViewModel cleared" }
        viewModelScope.launch { 
            try {
                if (useOnlineMode && streamer != null) {
                    streamer.disconnect()
                } else if (engine != null) {
                    engine.stop()
                }
            } catch (e: Exception) {
                LogUtils.w(TAG) { "Error stopping backend in onCleared: ${e.message}" }
            }
        }
        super.onCleared()
    }
}