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
import com.example.expressora.recognition.feature.FeatureScaler
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.example.expressora.translation.OfflineTranslationEngine

class RecognitionViewModel(
    private val engine: RecognitionEngine? = null, // Optional for offline mode (Tweak 3)
    private val streamer: LandmarkStreamer? = null, // Optional for online mode
    private val context: Context,
    initialUseOnlineMode: Boolean = false // Default: Offline (for TFLite testing)
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
    
    // Online/Offline mode toggle (runtime switching)
    private val _useOnlineMode = MutableStateFlow(initialUseOnlineMode)
    val useOnlineMode: StateFlow<Boolean> = _useOnlineMode.asStateFlow()
    
    // Offline translation engine (initialized on background thread)
    private var offlineTranslator: OfflineTranslationEngine? = null
    
    /**
     * Toggle between Online Mode (Server/gRPC) and Offline Mode (Local TFLite).
     * Handles connection/disconnection when switching modes.
     */
    fun toggleOnlineMode() {
        val newMode = !_useOnlineMode.value
        _useOnlineMode.value = newMode
        
        viewModelScope.launch {
            if (newMode) {
                // Switching to Online Mode: Connect streamer and set up event listener
                if (streamer != null) {
                    LogUtils.d(TAG) { "🔄 Switching to Online Mode: Connecting streamer" }
                    streamer.connect()
                    // Set up event listener for online mode
                    setupOnlineModeEventListener()
                }
            } else {
                // Switching to Offline Mode: Cancel event listener, disconnect streamer, and ensure engine is ready
                onlineModeEventJob?.cancel()
                onlineModeEventJob = null
                
                if (streamer != null && streamer.isConnected()) {
                    LogUtils.d(TAG) { "🔄 Switching to Offline Mode: Disconnecting streamer" }
                    streamer.stopStreaming()
                }
                // Ensure engine is started if switching to offline mode
                if (engine != null) {
                    try {
                        engine.start()
                        LogUtils.d(TAG) { "✅ Offline Mode: Engine started" }
                    } catch (e: Exception) {
                        LogUtils.w(TAG) { "⚠️ Failed to start engine: ${e.message}" }
                    }
                } else {
                    LogUtils.w(TAG) { "⚠️ Cannot switch to offline mode: engine is null (TFLite model missing or failed to load)" }
                    LogUtils.w(TAG) { "   Reverting to online mode..." }
                    _useOnlineMode.value = true
                    if (streamer != null) {
                        streamer.connect()
                        setupOnlineModeEventListener()
                    }
                }
            }
        }
    }
    
    /**
     * Set up the event listener for online mode (gRPC recognition events)
     * This must be called when switching to online mode to process GLOSS events
     */
    private fun setupOnlineModeEventListener() {
        if (streamer == null) {
            LogUtils.w(TAG) { "⚠️ Cannot set up online mode listener: streamer is null" }
            return
        }
        
        // Cancel any existing listener
        onlineModeEventJob?.cancel()
        
        // Set up new event listener
        onlineModeEventJob = viewModelScope.launch {
            LogUtils.i(TAG) { "🔌 Setting up online mode event listener..." }
            
            // Listen to recognition events (GLOSS, TONE, HANDS_DOWN)
            streamer!!.recognitionEvents.collect { event ->
                Log.i(TAG, "📥 ========== RECOGNITION EVENT RECEIVED ==========")
                Log.i(TAG, "   Type: ${event.type}")
                Log.i(TAG, "   Label: '${event.label}'")
                Log.i(TAG, "   Confidence: ${event.confidence} (${(event.confidence * 100).toInt()}%)")
                Log.i(TAG, "   Current glossList size: ${_glossList.value.size}")
                Log.i(TAG, "   Current accumulator tokens: ${accumulatorState.value.tokens.size}")
                
                when (event.type) {
                    RecognitionEvent.Type.GLOSS -> {
                        Log.i(TAG, "✅ GLOSS event detected: '${event.label}' (conf=${event.confidence})")
                        Log.i(TAG, "   Server has already validated this gloss (multi-frame validation)")
                        Log.i(TAG, "   Calling accumulator.onSingleShotResult()...")
                        
                        val tokensBefore = accumulatorState.value.tokens.size
                        val glossListBefore = _glossList.value.size
                        
                        // Server responses are already validated (multi-frame validation on server)
                        // Use single-shot method: instant acceptance but debounces duplicates
                        accumulator.onSingleShotResult(event.label)
                        
                        // Give a small delay to allow state to propagate
                        kotlinx.coroutines.delay(50)
                        
                        val tokensAfter = accumulatorState.value.tokens.size
                        val glossListAfter = _glossList.value.size
                        
                        Log.i(TAG, "   ✅ Token processed via single-shot")
                        Log.i(TAG, "   Accumulator: $tokensBefore -> $tokensAfter tokens")
                        Log.i(TAG, "   GlossList: $glossListBefore -> $glossListAfter items")
                        Log.i(TAG, "   Current tokens: [${accumulatorState.value.tokens.joinToString(", ")}]")
                        Log.i(TAG, "   Current glossList: [${_glossList.value.joinToString(", ")}]")
                        
                        if (tokensAfter == tokensBefore) {
                            Log.w(TAG, "   ⚠️ WARNING: Token count did not increase! Possible duplicate or accumulator issue.")
                        }
                        if (glossListAfter == glossListBefore) {
                            Log.w(TAG, "   ⚠️ WARNING: GlossList did not update! Check accumulator state observer.")
                        }
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
        }
        
        // Also listen to connection state
        viewModelScope.launch {
            streamer!!.connectionState.collect { state ->
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
    }
    
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
    
    // Feature extractor for LSTM model (with 30-frame buffer)
    private val featureExtractor: LandmarkFeatureExtractor
    
    // History repository
    private val historyRepository = SequenceHistoryRepository(context)
    
    // Last published event from bus
    private val _lastBusEvent = MutableStateFlow<String?>(null)
    val lastBusEvent: StateFlow<String?> = _lastBusEvent
    
    // Job for online mode event listener (to cancel when switching modes)
    private var onlineModeEventJob: Job? = null
    
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
        LogUtils.d(TAG) { "Initializing RecognitionViewModel (initialUseOnlineMode=$initialUseOnlineMode)" }
        
        // Initialize feature extractor with FeatureScaler for LSTM model
        Log.i(TAG, "🔧 Initializing FeatureScaler...")
        Log.i(TAG, "📂 Context: ${context.javaClass.simpleName}, assets available: ${try { context.assets.list("") != null } catch (e: Exception) { false }}")
        
        val featureScaler = try {
            FeatureScaler.create(context)
        } catch (e: Exception) {
            Log.e(TAG, "❌ EXCEPTION during FeatureScaler.create(): ${e.javaClass.simpleName} - ${e.message}", e)
            null
        }
        
        if (featureScaler == null) {
            Log.e(TAG, "❌ CRITICAL: Failed to initialize FeatureScaler! LSTM model requires feature scaling.")
        } else {
            Log.i(TAG, "✅ FeatureScaler initialized successfully (Robust Min-Max Mode)")
            Log.i(TAG, "✅ FeatureExtractor initialized with FeatureScaler (237 features)")
        }
        
        featureExtractor = LandmarkFeatureExtractor(featureScaler)
        
        // Initialize offline translation engine on background thread
        viewModelScope.launch(Dispatchers.IO) {
            val initStartTime = System.currentTimeMillis()
            Log.i(TAG, "🔧 [OFFLINE_INIT] ========================================")
            Log.i(TAG, "🔧 [OFFLINE_INIT] Starting offline translation engine initialization...")
            Log.d(TAG, "🔧 [OFFLINE_INIT] Thread: ${Thread.currentThread().name}")
            Log.d(TAG, "🔧 [OFFLINE_INIT] Dispatcher: IO")
            try {
                offlineTranslator = OfflineTranslationEngine(context)
                val initDuration = System.currentTimeMillis() - initStartTime
                Log.i(TAG, "✅ [OFFLINE_INIT] Offline translation engine initialized successfully in ${initDuration}ms")
                Log.d(TAG, "✅ [OFFLINE_INIT] Engine instance: ${offlineTranslator != null}")
            } catch (e: Exception) {
                val initDuration = System.currentTimeMillis() - initStartTime
                Log.e(TAG, "❌ [OFFLINE_INIT] Failed to initialize offline translation engine after ${initDuration}ms", e)
                Log.e(TAG, "❌ [OFFLINE_INIT] Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "❌ [OFFLINE_INIT] Exception message: ${e.message}")
                e.printStackTrace()
                // Continue without offline fallback - online translation will still work
                offlineTranslator = null
                Log.w(TAG, "⚠️ [OFFLINE_INIT] Continuing without offline fallback - online translation will still work")
            }
            Log.i(TAG, "🔧 [OFFLINE_INIT] ========================================")
        }
        
        // CRITICAL: Observe accumulator state and sync to UI in real-time
        // Accumulator is the SINGLE SOURCE OF TRUTH - all modifications must go through it
        viewModelScope.launch {
            accumulatorState.collect { state ->
                // Always mirror the accumulator's tokens to the UI
                // Use direct assignment (not update) since accumulator is the source of truth
                val oldTokens = _glossList.value
                _glossList.value = state.tokens
                if (oldTokens != state.tokens) {
                    Log.i(TAG, "🔄 ========== ACCUMULATOR STATE SYNC ==========")
                    Log.i(TAG, "   Old tokens: ${oldTokens.size} items [${oldTokens.joinToString(", ")}]")
                    Log.i(TAG, "   New tokens: ${state.tokens.size} items [${state.tokens.joinToString(", ")}]")
                    Log.i(TAG, "   _glossList updated: ${oldTokens.size} -> ${state.tokens.size}")
                    Log.i(TAG, "   ✅ UI will be notified of glossList change")
                    LogUtils.i(TAG) { "🔄🔄🔄 UI Synced to Accumulator: ${oldTokens.size} -> ${state.tokens.size} items, tokens=[${state.tokens.joinToString(", ")}]" }
                } else {
                    Log.d(TAG, "   Accumulator state unchanged: ${state.tokens.size} tokens")
                }
            }
        }
        
        if (initialUseOnlineMode && streamer != null) {
            // Online mode: Use gRPC streaming
            LogUtils.d(TAG) { "Using ONLINE mode with gRPC streaming" }
            viewModelScope.launch {
                // Connect to server
                streamer.connect()
                // Set up event listener
                setupOnlineModeEventListener()
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
                
                // TODO: Gather non-manual annotations (face expressions, etc.) from HolisticLandmarkerEngine when needed
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
            
            // Dynamic mode check: use StateFlow value instead of constructor parameter
            if (_useOnlineMode.value && streamer != null) {
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
                
                // CRITICAL: Also update accumulator's wrist history for variance detection in onSingleShotResult()
                // This allows online mode to use FSM Exit C (variance-based dynamic sign detection)
                // Create a dummy RecognitionResult to pass wrist data to accumulator (wrist tracking only)
                val dummyResult = RecognitionResult(
                    glossLabel = "",  // Not used, just for wrist tracking
                    glossConf = 0f,
                    glossIndex = -1,  // Not used
                    originLabel = null,
                    originConf = null,
                    debugInfo = null
                )
                accumulator.onRecognitionResult(dummyResult, wristX, wristY)
                
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
                Log.w(TAG, "⚠️ onLandmarks called in offline mode but engine not available")
                Log.w(TAG, "   Engine is null - this means TFLite model file is missing or failed to load")
                Log.w(TAG, "   Automatically switching to online mode as fallback...")
                
                // Automatically switch to online mode if engine is null and streamer is available
                if (streamer != null && !_useOnlineMode.value) {
                    Log.i(TAG, "🔄 Auto-switching to online mode (engine unavailable)")
                    _useOnlineMode.value = true
                    viewModelScope.launch {
                        streamer.connect()
                        setupOnlineModeEventListener()
                    }
                } else if (streamer == null) {
                    Log.e(TAG, "❌ CRITICAL: Both engine and streamer are null! Recognition will not work.")
                    Log.e(TAG, "   Engine: null")
                    Log.e(TAG, "   Streamer: null")
                    Log.e(TAG, "   This indicates a serious initialization problem.")
                }
                return@launch
            }
            
            // Process on background thread to avoid blocking
            withContext(Dispatchers.Default) {
                try {
                    // DIAGNOSTIC: Log feature extractor buffer status
                    val bufferSizeBefore = featureExtractor.getCurrentBufferSize()
                    Log.e(TAG, "🔍 PIPELINE ORCHESTRATION:")
                    Log.e(TAG, "   Feature extractor buffer status: $bufferSizeBefore/30 frames")
                    
                    // Use new Holistic LSTM feature extractor (30-frame buffer, 237 features per frame)
                    val byteBuffer = featureExtractor.process(result, timestampMs)
                    
                    if (byteBuffer == null) {
                        // Buffer not full yet - wait for more frames
                        val bufferSizeAfter = featureExtractor.getCurrentBufferSize()
                        Log.e(TAG, "⏸️ Waiting for buffer: $bufferSizeAfter/30 frames (was $bufferSizeBefore)")
                        return@withContext
                    }
                    
                    // DIAGNOSTIC: Log ByteBuffer creation and properties
                    val bufferSizeAfter = featureExtractor.getCurrentBufferSize()
                    Log.e(TAG, "✅ ByteBuffer created: capacity=${byteBuffer.capacity()} bytes, position=${byteBuffer.position()}, limit=${byteBuffer.limit()}")
                    Log.e(TAG, "   Buffer status: $bufferSizeAfter/30 frames (was $bufferSizeBefore, added 1 frame)")
                    Log.e(TAG, "   Expected size: 30 frames × 237 features × 4 bytes = ${30 * 237 * 4} bytes")
                    
                    // Buffer is full - pass ByteBuffer directly to engine for LSTM inference
                    Log.e(TAG, "🔍 Checking engine type: engine=${engine?.javaClass?.simpleName}, isNull=${engine == null}")
                    if (engine is TfLiteRecognitionEngine) {
                        Log.e(TAG, "✅ Engine is TfLiteRecognitionEngine, calling onLandmarksSequence() with buffer size: ${byteBuffer.capacity()} bytes")
                        // Pass ByteBuffer directly to engine (contains scaled 30-frame sequence)
                        // No conversion needed - engine handles ByteBuffer directly
                        engine.onLandmarksSequence(byteBuffer)
                        Log.e(TAG, "✅ onLandmarksSequence() call completed")
                        Log.e(TAG, "✅ Offline recognition: processing ByteBuffer with 30 frames × 237 features (7110 floats) directly")
                    } else {
                        Log.e(TAG, "⚠️ Engine is not TfLiteRecognitionEngine (type: ${engine?.javaClass?.simpleName}), cannot process ByteBuffer")
                    }
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
     * Handle feature vector (Legacy method - Offline mode)
     * Note: Main offline processing now uses LandmarkFeatureExtractor.process() in onLandmarks()
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
                    Log.e(TAG, "📊 CONFIDENCE SMOOTHING: '$newGloss'")
                    Log.e(TAG, "   Raw confidence: %.6f (${(rawConfidence * 100).toInt()}%)".format(rawConfidence))
                    Log.e(TAG, "   Previous smoothed: %.6f (${(prevSmoothed * 100).toInt()}%)".format(prevSmoothed))
                    Log.e(TAG, "   Smoothed confidence: %.6f (${(smoothedScore * 100).toInt()}%) [formula: raw*0.7 + prev*0.3]".format(smoothedScore))
                    
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
                    
                    Log.e(TAG, "✅ FINAL PREDICTION SELECTION:")
                    Log.e(TAG, "   Gloss: '$newGloss'")
                    Log.e(TAG, "   Raw confidence: %.6f (${(rawConfidence * 100).toInt()}%)".format(rawConfidence))
                    Log.e(TAG, "   Smoothed confidence: %.6f (${(smoothedScore * 100).toInt()}%)".format(smoothedScore))
                    Log.e(TAG, "   Reason: Passed all filters (cooldown, same-sign check, sentence limit)")
                    Log.e(TAG, "✅ GLOSS accepted: '$newGloss' (raw: $rawConfidence, smoothed: $smoothedScore)")
                    
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
        val confirmStartTime = System.currentTimeMillis()
        Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] ========================================")
        Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] confirmTranslation() called")
        
        // Stack trace logging to identify callers
        val stackTrace = Thread.currentThread().stackTrace.take(5).joinToString("\n") { 
            "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        Log.d(TAG, "📞 [CONFIRM_TRANSLATE] Call stack:\n$stackTrace")
        Log.d(TAG, "📞 [CONFIRM_TRANSLATE] Thread: ${Thread.currentThread().name}")
        
        // Guard check to prevent translation if already translating
        val isCurrentlyTranslating = _isTranslating.value
        Log.d(TAG, "🔍 [CONFIRM_TRANSLATE] Current state check: isTranslating=$isCurrentlyTranslating")
        if (isCurrentlyTranslating) {
            Log.w(TAG, "⚠️ [CONFIRM_TRANSLATE] Already translating - ignoring duplicate call")
            return
        }
        
        val glosses = _glossList.value
        Log.d(TAG, "📋 [CONFIRM_TRANSLATE] Current gloss list: ${glosses.size} items")
        if (glosses.isEmpty()) {
            Log.w(TAG, "❌ [CONFIRM_TRANSLATE] Cannot translate: gloss list is empty")
            return
        }
        
        val tone = _currentTone.value
        val origin = _recognitionResult.value?.originLabel
        Log.i(TAG, "✅ [CONFIRM_TRANSLATE] Starting translation process")
        Log.d(TAG, "📊 [CONFIRM_TRANSLATE] Input details:")
        Log.d(TAG, "   - Gloss count: ${glosses.size}")
        Log.d(TAG, "   - Glosses: ${glosses.joinToString(", ")}")
        Log.d(TAG, "   - Tone: $tone")
        Log.d(TAG, "   - Origin: $origin")
        Log.d(TAG, "   - Online mode: ${_useOnlineMode.value}")
        Log.d(TAG, "   - Streamer available: ${streamer != null}")
        Log.d(TAG, "   - Offline translator available: ${offlineTranslator != null}")
        
        // Set loading state IMMEDIATELY (synchronously) before launching coroutine
        // This ensures UI shows ModalBottomSheet instantly with loading state
        Log.i(TAG, "🚀 ========== TRANSLATE BUTTON CLICKED ==========")
        Log.i(TAG, "   Current glossList: [${_glossList.value.joinToString(", ")}] (${_glossList.value.size} items)")
        Log.i(TAG, "   Current isTranslating: ${_isTranslating.value}")
        Log.i(TAG, "   Current translationResult: ${if (_translationResult.value != null) "not null" else "null"}")
        
        Log.i(TAG, "⏳ [CONFIRM_TRANSLATE] Step 1/5: Setting isTranslating = true (SYNCHRONOUSLY)")
        _isTranslating.value = true
        Log.i(TAG, "✅ [CONFIRM_TRANSLATE] isTranslating state updated: ${_isTranslating.value}")
        Log.i(TAG, "   ✅ ModalBottomSheet should show immediately with loading indicator")
        
        // Pause camera scanning IMMEDIATELY
        Log.d(TAG, "⏸️ [CONFIRM_TRANSLATE] Step 2/5: Pausing camera scanning (isScanning = false) (SYNCHRONOUSLY)")
        _isScanning.value = false
        Log.d(TAG, "✅ [CONFIRM_TRANSLATE] isScanning state updated: ${_isScanning.value}")
        
        viewModelScope.launch {
            val translationStartTime = System.currentTimeMillis()
            Log.d(TAG, "🚀 [TRANSLATE] Launching translation coroutine...")
            Log.d(TAG, "📞 [TRANSLATE] Coroutine thread: ${Thread.currentThread().name}")
            
            try {
                // STRATEGY: Use Online if in online mode, otherwise use Offline
                val useOnline = _useOnlineMode.value && streamer != null
                Log.d(TAG, "🔀 [TRANSLATE] Strategy decision:")
                Log.d(TAG, "   - Online mode enabled: ${_useOnlineMode.value}")
                Log.d(TAG, "   - Streamer available: ${streamer != null}")
                Log.d(TAG, "   - Will use online: $useOnline")
                
                if (useOnline) {
                    // ONLINE MODE: Use gRPC TranslateSequence API (NO fallback to offline)
                    val onlineStartTime = System.currentTimeMillis()
                    Log.i(TAG, "☁️ [TRANSLATE] ========================================")
                    Log.i(TAG, "☁️ [TRANSLATE] Using ONLINE (Cloud) Translation API...")
                    try {
                        // Stop gRPC stream to prevent interference with TranslateSequence (keep channel for blocking call)
                        val stopStreamStartTime = System.currentTimeMillis()
                        Log.d(TAG, "🛑 [TRANSLATE] Step 3/5: Stopping gRPC stream before translation...")
                        streamer?.stopStreaming()
                        val stopStreamDuration = System.currentTimeMillis() - stopStreamStartTime
                        Log.d(TAG, "✅ [TRANSLATE] gRPC stream stopped in ${stopStreamDuration}ms (channel kept alive for blocking call)")
                        
                        Log.d(TAG, "📞 [TRANSLATE] Calling TranslateSequence RPC...")
                        Log.d(TAG, "📊 [TRANSLATE] Request parameters:")
                        Log.d(TAG, "   - Glosses: ${glosses.joinToString(", ")}")
                        Log.d(TAG, "   - Tone: $tone")
                        Log.d(TAG, "   - Origin: $origin")
                        
                        // Try Cloud translation with 30s timeout (Gemini API can take 10-20s)
                        val cloudStartTime = System.currentTimeMillis()
                        Log.d(TAG, "⏱️ [TRANSLATE] Starting cloud translation with 30s timeout...")
                        val result = withTimeout(30000L) {
                            streamer!!.translateSequence(glosses, tone, origin)
                        }
                        val cloudDuration = System.currentTimeMillis() - cloudStartTime
                        Log.i(TAG, "⏱️ [TRANSLATE] Cloud translation completed in ${cloudDuration}ms")
                        
                        // Update translation result
                        Log.d(TAG, "💾 [TRANSLATE] Updating translation result from Cloud...")
                        Log.d(TAG, "📊 [TRANSLATE] Cloud result:")
                        Log.d(TAG, "   - Sentence: '${result.sentence}'")
                        Log.d(TAG, "   - Sentence Filipino: '${result.sentenceFilipino}'")
                        Log.d(TAG, "   - Source: '${result.source}'")
                        Log.d(TAG, "   - Tone: '${result.tone}'")
                        
                        _translationResult.value = result
                        val onlineDuration = System.currentTimeMillis() - onlineStartTime
                        Log.i(TAG, "✅ [TRANSLATE] Translation result state updated")
                        Log.i(TAG, "🎉 [TRANSLATE] Cloud translation completed successfully in ${onlineDuration}ms")
                        Log.i(TAG, "☁️ [TRANSLATE] ========================================")
                        
                        val totalDuration = System.currentTimeMillis() - translationStartTime
                        Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] Total translation time: ${totalDuration}ms")
                        Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] ========================================")
                        
                        // Success - return early
                        return@launch
                    } catch (e: TimeoutCancellationException) {
                        val onlineDuration = System.currentTimeMillis() - onlineStartTime
                        Log.e(TAG, "❌ [TRANSLATE] Cloud translation timed out after ${onlineDuration}ms (10s limit)")
                        Log.e(TAG, "❌ [TRANSLATE] Online mode: NOT falling back to offline - showing error")
                        
                        // In online mode, don't fall back - show error instead
                        val errorResult = TranslationResult.newBuilder()
                            .setSentence("Translation timeout: Server did not respond in time. Please check your connection.")
                            .setSource("Error")
                            .build()
                        _translationResult.value = errorResult
                        _isTranslating.value = false
                        return@launch
                    } catch (e: Exception) {
                        val onlineDuration = System.currentTimeMillis() - onlineStartTime
                        Log.e(TAG, "❌ [TRANSLATE] Cloud translation failed after ${onlineDuration}ms: ${e.message}", e)
                        Log.e(TAG, "❌ [TRANSLATE] Exception type: ${e.javaClass.simpleName}")
                        Log.e(TAG, "❌ [TRANSLATE] Online mode: NOT falling back to offline - showing error")
                        e.printStackTrace()
                        
                        // In online mode, don't fall back - show error instead
                        val errorResult = TranslationResult.newBuilder()
                            .setSentence("Translation failed: ${e.message ?: "Unknown error"}. Please check your connection.")
                            .setSource("Error")
                            .build()
                        _translationResult.value = errorResult
                        _isTranslating.value = false
                        return@launch
                    }
                } else {
                    Log.d(TAG, "⏭️ [TRANSLATE] Skipping online translation (not in online mode or streamer unavailable)")
                }
                
                // OFFLINE MODE: Use local TFLite translation (only when NOT in online mode)
                val offlineStartTime = System.currentTimeMillis()
                Log.i(TAG, "📱 [TRANSLATE] ========================================")
                Log.i(TAG, "📱 [TRANSLATE] Running OFFLINE Translation...")
                
                val offlineEngine = offlineTranslator
                Log.d(TAG, "🔍 [TRANSLATE] Checking offline translator availability...")
                Log.d(TAG, "📊 [TRANSLATE] Offline translator: ${if (offlineEngine != null) "available" else "null"}")
                
                if (offlineEngine == null) {
                    val offlineDuration = System.currentTimeMillis() - offlineStartTime
                    Log.e(TAG, "❌ [TRANSLATE] Offline translator not initialized after ${offlineDuration}ms - cannot translate")
                    val errorResult = TranslationResult.newBuilder()
                        .setSentence("Translation unavailable: Offline engine not ready")
                        .setSource("Error")
                        .build()
                    _translationResult.value = errorResult
                    Log.e(TAG, "💾 [TRANSLATE] Error result set in translationResult state")
                    return@launch
                }
                
                // Determine target language from tone or default to English
                val targetLang = if (tone.contains("filipino", ignoreCase = true) || 
                                    tone.contains("fil", ignoreCase = true)) {
                    "fil"
                } else {
                    "en"
                }
                Log.d(TAG, "🌐 [TRANSLATE] Target language determined: $targetLang")
                Log.d(TAG, "📊 [TRANSLATE] Language decision based on tone: '$tone'")
                
                Log.d(TAG, "🔄 [TRANSLATE] Switching to Default dispatcher for offline translation...")
                val offlineTranslateStartTime = System.currentTimeMillis()
                val offlineResult = withContext(Dispatchers.Default) {
                    Log.d(TAG, "📱 [TRANSLATE] Running offline translation on thread: ${Thread.currentThread().name}")
                    offlineEngine.translate(glosses, targetLang)
                }
                val offlineTranslateDuration = System.currentTimeMillis() - offlineTranslateStartTime
                Log.i(TAG, "✅ [TRANSLATE] Offline translation complete in ${offlineTranslateDuration}ms")
                Log.d(TAG, "📝 [TRANSLATE] Offline translation result: '$offlineResult'")
                
                // Create TranslationResult with offline source
                val resultBuildStartTime = System.currentTimeMillis()
                Log.d(TAG, "🔨 [TRANSLATE] Building TranslationResult...")
                val resultBuilder = TranslationResult.newBuilder()
                    .setSource("Offline (Tiny T5)")
                
                // Set the appropriate sentence field based on target language
                if (targetLang == "fil") {
                    Log.d(TAG, "🌐 [TRANSLATE] Setting Filipino sentence field...")
                    resultBuilder.setSentenceFilipino(offlineResult)
                    resultBuilder.setSentence("")
                    Log.d(TAG, "✅ [TRANSLATE] Filipino sentence set: '$offlineResult'")
                } else {
                    Log.d(TAG, "🌐 [TRANSLATE] Setting English sentence field...")
                    resultBuilder.setSentence(offlineResult)
                    resultBuilder.setSentenceFilipino("")
                    Log.d(TAG, "✅ [TRANSLATE] English sentence set: '$offlineResult'")
                }
                
                val result = resultBuilder.build()
                val resultBuildDuration = System.currentTimeMillis() - resultBuildStartTime
                Log.d(TAG, "✅ [TRANSLATE] TranslationResult built in ${resultBuildDuration}ms")
                
                Log.d(TAG, "📊 [TRANSLATE] Final result:")
                Log.d(TAG, "   - Sentence: '${result.sentence}'")
                Log.d(TAG, "   - Sentence Filipino: '${result.sentenceFilipino}'")
                Log.d(TAG, "   - Source: '${result.source}'")
                Log.d(TAG, "   - Tone: '${result.tone}'")
                
                _translationResult.value = result
                val offlineDuration = System.currentTimeMillis() - offlineStartTime
                Log.i(TAG, "💾 [TRANSLATE] Offline translation result set in state")
                Log.i(TAG, "📱 [TRANSLATE] ========================================")
                
                val totalDuration = System.currentTimeMillis() - translationStartTime
                Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] Total translation time: ${totalDuration}ms")
                Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] ========================================")
                
            } catch (e: Exception) {
                val totalDuration = System.currentTimeMillis() - translationStartTime
                Log.e(TAG, "❌ [TRANSLATE] Translation failed with exception after ${totalDuration}ms", e)
                Log.e(TAG, "❌ [TRANSLATE] Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "❌ [TRANSLATE] Exception message: ${e.message}")
                e.printStackTrace()
                
                // Show error result
                Log.d(TAG, "🔨 [TRANSLATE] Building error TranslationResult...")
                val errorResult = TranslationResult.newBuilder()
                    .setSentence("Translation error: ${e.message}")
                    .setSource("Error")
                    .build()
                _translationResult.value = errorResult
                Log.e(TAG, "💾 [TRANSLATE] Error result set in translationResult state")
                Log.e(TAG, "📊 [TRANSLATE] Error result: sentence='${errorResult.sentence}', source='${errorResult.source}'")
            } finally {
                // Always reset loading state
                val finallyStartTime = System.currentTimeMillis()
                Log.d(TAG, "🔄 [TRANSLATE] Finally block: Cleaning up...")
                _isTranslating.value = false
                Log.d(TAG, "✅ [TRANSLATE] isTranslating state reset: ${_isTranslating.value}")
                
                val totalDuration = System.currentTimeMillis() - confirmStartTime
                val finallyDuration = System.currentTimeMillis() - finallyStartTime
                Log.d(TAG, "✅ [TRANSLATE] Finally block complete in ${finallyDuration}ms")
                Log.i(TAG, "🔄 [CONFIRM_TRANSLATE] Total confirmTranslation() time: ${totalDuration}ms")
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
        if (_useOnlineMode.value && streamer != null) {
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
            Log.w(TAG, "⚠️ Skipping stream reconnection: useOnlineMode=${_useOnlineMode.value}, streamer=${if (streamer == null) "null" else "not null"}")
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
        } else if (_useOnlineMode.value) {
            "gRPC Streaming"
        } else {
            "Unknown"
        }
    }

    fun getClassifierModelVariant(): String {
        return if (engine is TfLiteRecognitionEngine) {
            engine.getModelVariant()
        } else if (_useOnlineMode.value) {
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
            if (_useOnlineMode.value && streamer != null) {
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
                if (_useOnlineMode.value && streamer != null) {
                    streamer.disconnect()
                } else if (engine != null) {
                    engine.stop()
                }
                
                // Clean up offline translation engine
                offlineTranslator?.close()
                offlineTranslator = null
                LogUtils.d(TAG) { "Offline translation engine cleaned up" }
            } catch (e: Exception) {
                LogUtils.w(TAG) { "Error stopping backend in onCleared: ${e.message}" }
            }
        }
        super.onCleared()
    }
}