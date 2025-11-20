package com.example.expressora.recognition.accumulator

import android.util.Log
import com.example.expressora.recognition.di.RecognitionProvider
import com.example.expressora.recognition.model.RecognitionResult
import com.example.expressora.recognition.tflite.LabelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Production-grade Finite State Machine (FSM) for sign language recognition trigger discipline.
 * Prevents spamming the same sign and enforces a "Hold Out" period with neutral return requirement.
 * 
 * State Machine:
 * - IDLE: No hands detected or confidence below threshold
 * - MONITORING: Hands detected, accumulating stable frames
 * - REGISTERED: Sign confirmed (transient state) → immediately transitions to HOLD_OUT
 * - HOLD_OUT: Ignore same sign detections until exit condition met
 */
class SequenceAccumulator(
    private val scope: CoroutineScope,
    private val maxTokens: Int = 7,
    private val confidenceThreshold: Float = RecognitionProvider.GLOSS_CONFIDENCE_THRESHOLD,
    private val dedupWindowMs: Long = 300L,
    private val alphabetIdleMs: Long = 1000L
) {
    private val TAG = "SequenceAccumulator"
    
    // --- FSM Configuration ---
    private val HOLD_FRAMES_REQUIRED = 5      // Frames to hold a sign before registering
    private val HOLD_OUT_EXIT_THRESHOLD = 0.4f // Hand variance threshold to exit HOLD_OUT (Dynamic movement)
    private val FSM_CONFIDENCE_THRESHOLD = 0.75f  // Minimum confidence to enter MONITORING
    
    // --- FSM State Definition ---
    private enum class State {
        IDLE,           // No hands or low confidence
        MONITORING,     // Hands detected, accumulating stable frames
        REGISTERED,     // Sign just confirmed (transient state)
        HOLD_OUT        // Waiting for user to reset/move hands
    }
    
    // --- FSM Runtime Variables ---
    private var currentState = State.IDLE
    private var stableFrameCount = 0
    private var lastRegisteredGloss: String? = null
    private var potentialGloss: String? = null
    private var potentialConfidence: Float = 0f
    
    // Variance Buffer (Last 5 frames of wrist position) - Ring Buffer
    private val wristHistoryX = FloatArray(5)
    private val wristHistoryY = FloatArray(5)
    private var historyIndex = 0
    private var historySize = 0  // Track how many frames we've collected
    
    // --- Token Management State (Preserved from original) ---
    private val _state = MutableStateFlow(AccumulatorState())
    val state: StateFlow<AccumulatorState> = _state.asStateFlow()
    
    // Alphabet mode
    private var alphabetBuffer = StringBuilder()
    private var lastLetterTime: Long = 0L
    private var alphabetCommitJob: Job? = null
    
    // De-duplication (for token acceptance, separate from FSM)
    private var lastAcceptedLabel: String? = null
    private var lastAcceptedTime: Long = 0L
    
    // Committed sequence callback
    var onSequenceCommitted: ((List<String>) -> Unit)? = null
    var onAlphabetWordCommitted: ((String) -> Unit)? = null
    
    /**
     * Process incoming recognition result with FSM trigger discipline.
     * Wrist coordinates are optional - if not provided, variance calculation is skipped.
     */
    fun onRecognitionResult(
        result: RecognitionResult,
        wristX: Float? = null,
        wristY: Float? = null
    ) {
        val label = result.glossLabel
        val conf = result.glossConf
        
        Log.v(TAG, "onRecognitionResult: label='$label', conf=$conf, threshold=$confidenceThreshold, " +
                "wrist=(${wristX?.let { "%.3f" } ?: "null"}, ${wristY?.let { "%.3f" } ?: "null"})")
        
        // Update motion history if wrist coordinates provided
        if (wristX != null && wristY != null) {
            wristHistoryX[historyIndex] = wristX
            wristHistoryY[historyIndex] = wristY
            historyIndex = (historyIndex + 1) % 5
            if (historySize < 5) historySize++
        }
        
        val isHandsDetected = conf >= FSM_CONFIDENCE_THRESHOLD
        
        // --- FSM State Machine Logic ---
        when (currentState) {
            State.IDLE -> {
                if (isHandsDetected) {
                    potentialGloss = label
                    potentialConfidence = conf
                    stableFrameCount = 1
                    currentState = State.MONITORING
                    Log.d(TAG, "FSM Transition: IDLE -> MONITORING ($label)")
                }
            }
            
            State.MONITORING -> {
                if (!isHandsDetected) {
                    // Hands dropped or confidence lost -> Reset
                    currentState = State.IDLE
                    stableFrameCount = 0
                    potentialGloss = null
                    Log.d(TAG, "FSM Transition: MONITORING -> IDLE (Lost confidence)")
                    return
                }
                
                if (label == potentialGloss) {
                    stableFrameCount++
                    potentialConfidence = conf
                    Log.v(TAG, "Same label '$label', stableFrameCount=$stableFrameCount (need $HOLD_FRAMES_REQUIRED)")
                    
                    // RULE: If held long enough, Register it.
                    if (stableFrameCount >= HOLD_FRAMES_REQUIRED) {
                        currentState = State.REGISTERED
                        Log.d(TAG, "FSM Transition: MONITORING -> REGISTERED ($label) after $stableFrameCount frames")
                    }
                } else {
                    // Sign changed mid-stream? Reset accumulation
                    potentialGloss = label
                    potentialConfidence = conf
                    stableFrameCount = 1
                    Log.d(TAG, "Sign changed during monitoring: '$potentialGloss' -> '$label', resetting count")
                }
            }
            
            State.REGISTERED -> {
                // ACTION: Send to token management
                if (potentialGloss != null) {
                    Log.i(TAG, "🎯 FSM: Sign registered - '$potentialGloss' (conf=$potentialConfidence) -> calling tryAcceptToken()")
                    tryAcceptToken(potentialGloss!!, potentialConfidence)
                    lastRegisteredGloss = potentialGloss
                    Log.d(TAG, "✅ Token accepted, current tokens: ${_state.value.tokens}")
                }
                // Immediate transition to HOLD_OUT
                currentState = State.HOLD_OUT
                stableFrameCount = 0
                Log.d(TAG, "FSM Transition: REGISTERED -> HOLD_OUT")
            }
            
            State.HOLD_OUT -> {
                // EXIT A: Hands dropped (Reset)
                if (!isHandsDetected) {
                    currentState = State.IDLE
                    lastRegisteredGloss = null
                    stableFrameCount = 0
                    Log.d(TAG, "FSM Exit A: HOLD_OUT -> IDLE (Hands dropped)")
                    return
                }
                
                // EXIT B: Different Sign detected
                if (label != lastRegisteredGloss) {
                    potentialGloss = label
                    potentialConfidence = conf
                    stableFrameCount = 1
                    currentState = State.MONITORING
                    Log.d(TAG, "FSM Exit B: HOLD_OUT -> MONITORING (New sign detected: '$label')")
                    return
                }
                
                // EXIT C: Dynamic Movement (Variance Check) - only if we have wrist data
                if (wristX != null && wristY != null && historySize >= 5) {
                    val variance = calculateVariance()
                    if (variance > HOLD_OUT_EXIT_THRESHOLD) {
                        // High variance means user is transitioning - allow new sign detection
                        potentialGloss = label
                        potentialConfidence = conf
                        stableFrameCount = 1
                        currentState = State.MONITORING
                        Log.d(TAG, "FSM Exit C: HOLD_OUT -> MONITORING (High variance: $variance > $HOLD_OUT_EXIT_THRESHOLD)")
                        return
                    }
                }
                
                // Still in HOLD_OUT - ignore this detection
                Log.v(TAG, "FSM: HOLD_OUT - ignoring same sign '$label'")
            }
        }
    }
    
    /**
     * Helper: Calculate variance (Mean Squared Error) of wrist position
     */
    private fun calculateVariance(): Float {
        if (historySize < 2) return 0f
        
        val meanX = wristHistoryX.slice(0 until historySize).average().toFloat()
        val meanY = wristHistoryY.slice(0 until historySize).average().toFloat()
        
        var sumSq = 0.0f
        for (i in 0 until historySize) {
            sumSq += (wristHistoryX[i] - meanX).pow(2) + (wristHistoryY[i] - meanY).pow(2)
        }
        
        return sumSq / historySize
    }
    
    /**
     * Accept token into sequence (preserves original token management logic)
     */
    private fun tryAcceptToken(label: String, conf: Float) {
        val now = System.currentTimeMillis()
        
        Log.d(TAG, "tryAcceptToken: label='$label', conf=$conf, lastAccepted='$lastAcceptedLabel', " +
                "timeSince=${now - lastAcceptedTime}ms, dedupWindow=$dedupWindowMs")
        
        // De-duplication check (but allow if confidence is very high)
        if (label == lastAcceptedLabel && (now - lastAcceptedTime) < dedupWindowMs && conf < 0.85f) {
            Log.d(TAG, "Skipping duplicate token: $label (conf=${conf}, timeSince=${now - lastAcceptedTime}ms)")
            return
        }
        
        // Check if alphabet letter
        val isLetter = LabelMap.isAlphabetLetter(label)
        Log.d(TAG, "Token type: isLetter=$isLetter")
        
        if (isLetter) {
            Log.d(TAG, "Handling alphabet letter: '$label'")
            handleAlphabetLetter(label, now)
        } else {
            // Non-letter: commit any pending word first, then add token
            Log.d(TAG, "Handling non-letter token: '$label', committing any pending word first")
            commitAlphabetWord()
            addToken(label, now)
        }
        
        lastAcceptedLabel = label
        lastAcceptedTime = now
    }
    
    private fun handleAlphabetLetter(letter: String, now: Long) {
        // Cancel any pending commit
        alphabetCommitJob?.cancel()
        
        // Add to buffer
        alphabetBuffer.append(letter)
        lastLetterTime = now
        
        // Update state
        _state.value = _state.value.copy(
            currentWord = alphabetBuffer.toString(),
            isAlphabetMode = true,
            lastTokenTime = now
        )
        
        Log.d(TAG, "Alphabet buffer: ${alphabetBuffer}")
        
        // Schedule commit after idle period
        alphabetCommitJob = scope.launch(Dispatchers.Default) {
            delay(alphabetIdleMs)
            commitAlphabetWord()
        }
    }
    
    private fun commitAlphabetWord() {
        if (alphabetBuffer.isEmpty()) return
        
        val word = alphabetBuffer.toString()
        alphabetBuffer.clear()
        
        Log.i(TAG, "Committing alphabet word: $word")
        
        // Add word as a single token
        val currentState = _state.value
        if (currentState.canAddToken()) {
            val newTokens = currentState.tokens + word
            _state.value = currentState.copy(
                tokens = newTokens,
                currentWord = "",
                isAlphabetMode = false,
                lastTokenTime = System.currentTimeMillis()
            )
            
            // Notify callback
            onAlphabetWordCommitted?.invoke(word)
            
            // Auto-commit if we hit max
            if (newTokens.size >= maxTokens) {
                commitSequence()
            }
        }
    }
    
    private fun addToken(token: String, now: Long) {
        val currentState = _state.value
        
        if (!currentState.canAddToken()) {
            Log.w(TAG, "Cannot add token, sequence full")
            return
        }
        
        val newTokens = currentState.tokens + token
        _state.value = currentState.copy(
            tokens = newTokens,
            lastTokenTime = now
        )
        
        Log.i(TAG, "✅✅✅ Added token: $token (total: ${newTokens.size}), tokens=[${newTokens.joinToString(", ")}]")
        
        // Auto-commit if we hit max
        if (newTokens.size >= maxTokens) {
            commitSequence()
        }
    }
    
    /**
     * Fast-track a server response.
     * ACCEPTS instantly (no 5-frame wait).
     * REJECTS duplicates (if we just added this token and are in HOLD_OUT).
     * Used for server responses which are already validated server-side.
     * @param token The gloss label to add immediately
     */
    fun onSingleShotResult(token: String) {
        if (token.isBlank()) {
            Log.v(TAG, "onSingleShotResult: Empty token ignored")
            return
        }
        
        // 1. Debounce: If we just added this token and haven't reset, ignore it
        // Check if we're in HOLD_OUT state and this is the same token we just accepted
        if (currentState == State.HOLD_OUT && lastAcceptedLabel == token) {
            Log.v(TAG, "♻️ Ignoring duplicate server response: '$token' (already in HOLD_OUT)")
            return
        }
        
        // 2. Check if sequence is full
        val currentTokens = _state.value.tokens
        if (!_state.value.canAddToken()) {
            Log.w(TAG, "onSingleShotResult: Cannot add token, sequence full (${currentTokens.size}/$maxTokens)")
            return
        }
        
        // 3. Commit Immediately
        val now = System.currentTimeMillis()
        val newTokens = currentTokens + token
        _state.value = _state.value.copy(
            tokens = newTokens,
            lastTokenTime = now
        )
        
        // 4. Update State to HOLD_OUT (Prevent duplicates until hands drop/change)
        currentState = State.HOLD_OUT
        lastAcceptedLabel = token
        lastAcceptedTime = now
        lastRegisteredGloss = token
        
        Log.i(TAG, "⚡ Single-Shot Accepted: '$token' (total: ${newTokens.size}), tokens=[${newTokens.joinToString(", ")}]")
        
        // Auto-commit if we hit max
        if (newTokens.size >= maxTokens) {
            commitSequence()
        }
    }
    
    /**
     * Remove last token (backspace)
     */
    fun backspace() {
        val currentState = _state.value
        
        // If in alphabet mode with word buffer, remove last letter
        if (currentState.isAlphabetMode && alphabetBuffer.isNotEmpty()) {
            alphabetBuffer.deleteCharAt(alphabetBuffer.length - 1)
            _state.value = currentState.copy(
                currentWord = alphabetBuffer.toString()
            )
            return
        }
        
        // Otherwise remove last token
        if (currentState.tokens.isNotEmpty()) {
            val newTokens = currentState.tokens.dropLast(1)
            _state.value = currentState.copy(
                tokens = newTokens
            )
            Log.i(TAG, "Backspace: ${newTokens.size} tokens remaining")
        }
    }
    
    /**
     * Clear entire sequence
     */
    fun clear() {
        alphabetBuffer.clear()
        alphabetCommitJob?.cancel()
        _state.value = AccumulatorState()
        
        // Reset FSM state
        currentState = State.IDLE
        stableFrameCount = 0
        lastRegisteredGloss = null
        potentialGloss = null
        historyIndex = 0
        historySize = 0
        lastAcceptedLabel = null
        lastAcceptedTime = 0L
        
        Log.i(TAG, "Cleared sequence and reset FSM")
    }
    
    /**
     * Commit current sequence (explicit send)
     */
    fun commitSequence(): List<String> {
        // Commit any pending word first
        commitAlphabetWord()
        
        val currentTokens = _state.value.tokens
        if (currentTokens.isNotEmpty()) {
            Log.i(TAG, "Committing sequence: $currentTokens")
            onSequenceCommitted?.invoke(currentTokens)
            
            // Clear after commit
            clear()
        }
        
        return currentTokens
    }
    
    /**
     * Get current tokens without committing
     */
    fun getCurrentTokens(): List<String> {
        return _state.value.tokens
    }
    
    /**
     * Get current word buffer
     */
    fun getCurrentWord(): String {
        return _state.value.currentWord
    }
}
