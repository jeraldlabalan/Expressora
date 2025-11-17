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

class SequenceAccumulator(
    private val scope: CoroutineScope,
    private val maxTokens: Int = 7,
    private val confidenceThreshold: Float = RecognitionProvider.GLOSS_CONFIDENCE_THRESHOLD,
    private val holdFrames: Int = 1,  // Reduced from 3 to 1 for more responsive detection
    private val dedupWindowMs: Long = 300L,  // Reduced from 1000ms to 300ms
    private val alphabetIdleMs: Long = 1000L
) {
    private val TAG = "SequenceAccumulator"
    
    private val _state = MutableStateFlow(AccumulatorState())
    val state: StateFlow<AccumulatorState> = _state.asStateFlow()
    
    // Frame counting for debouncing
    private var currentLabel: String? = null
    private var currentConf: Float = 0f
    private var frameCount: Int = 0
    
    // De-duplication
    private var lastAcceptedLabel: String? = null
    private var lastAcceptedTime: Long = 0L
    
    // Alphabet mode
    private var alphabetBuffer = StringBuilder()
    private var lastLetterTime: Long = 0L
    private var alphabetCommitJob: Job? = null
    
    // Committed sequence callback
    var onSequenceCommitted: ((List<String>) -> Unit)? = null
    var onAlphabetWordCommitted: ((String) -> Unit)? = null
    
    /**
     * Process incoming recognition result
     */
    fun onRecognitionResult(result: RecognitionResult) {
        val label = result.glossLabel
        val conf = result.glossConf
        
        Log.v(TAG, "onRecognitionResult: label='$label', conf=$conf, threshold=$confidenceThreshold")
        
        // Check confidence threshold
        if (conf < confidenceThreshold) {
            Log.v(TAG, "Confidence below threshold ($conf < $confidenceThreshold), resetting frame count")
            resetFrameCount()
            return
        }
        
        // Check if same label as currently held
        if (label == currentLabel) {
            frameCount++
            currentConf = conf
            Log.v(TAG, "Same label '$label', frameCount=$frameCount (need $holdFrames)")
            
            // Check if we've held long enough
            if (frameCount >= holdFrames) {
                Log.d(TAG, "Label '$label' held for $frameCount frames, attempting to accept token")
                tryAcceptToken(label, conf)
                resetFrameCount()
            }
        } else {
            // New label, reset count
            Log.d(TAG, "Label changed: '${currentLabel}' -> '$label', resetting frame count")
            currentLabel = label
            currentConf = conf
            frameCount = 1
        }
    }
    
    private fun resetFrameCount() {
        currentLabel = null
        currentConf = 0f
        frameCount = 0
    }
    
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
        
        Log.i(TAG, "Added token: $token (total: ${newTokens.size})")
        
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
        resetFrameCount()
        lastAcceptedLabel = null
        lastAcceptedTime = 0L
        Log.i(TAG, "Cleared sequence")
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

