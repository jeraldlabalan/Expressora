package com.example.expressora.recognition.bus

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-scoped singleton event bus for gloss sequences.
 * Provides a seam for the translator (not wired yet).
 */
object GlossSequenceBus {
    private const val TAG = "GlossSequenceBus"
    
    private val _events = MutableSharedFlow<GlossSequenceEvent>(
        replay = 1,
        extraBufferCapacity = 10
    )
    
    val events: SharedFlow<GlossSequenceEvent> = _events.asSharedFlow()
    
    /**
     * Publish a gloss sequence ready event with full contract payload.
     * 
     * @param tokens The gloss tokens (max 7)
     * @param nonmanuals Non-manual annotations (head pose, facial expressions)
     * @param origin Sign language origin ("ASL", "FSL", "UNKNOWN")
     * @param confidence Average confidence of the sequence
     */
    suspend fun publishSequence(
        tokens: List<String>,
        nonmanuals: List<NonManualAnnotation> = emptyList(),
        origin: String = "UNKNOWN",
        confidence: Float = 0.0f
    ) {
        if (tokens.isEmpty()) {
            Log.w(TAG, "Attempted to publish empty sequence")
            return
        }
        
        val event = GlossSequenceEvent.GlossSequenceReady(
            tokens = tokens,
            nonmanuals = nonmanuals,
            origin = origin,
            confidence = confidence
        )
        _events.emit(event)
        Log.i(TAG, "Published sequence: tokens=$tokens, origin=$origin, conf=${"%.2f".format(confidence)}, nonmanuals=${nonmanuals.size}")
    }
    
    /**
     * Publish an alphabet word committed event
     */
    suspend fun publishAlphabetWord(word: String) {
        if (word.isEmpty()) {
            Log.w(TAG, "Attempted to publish empty word")
            return
        }
        
        val event = GlossSequenceEvent.AlphabetWordCommitted(word)
        _events.emit(event)
        Log.i(TAG, "Published alphabet word: $word")
    }
    
    /**
     * Get the last published event (if any)
     */
    fun getLastEvent(): GlossSequenceEvent? {
        return _events.replayCache.lastOrNull()
    }
}

