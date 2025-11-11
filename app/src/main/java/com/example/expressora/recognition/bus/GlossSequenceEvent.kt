package com.example.expressora.recognition.bus

/**
 * Non-manual annotations attached to sequences (face, head pose, etc.)
 */
data class NonManualAnnotation(
    val timestamp: Long,
    val headPose: String? = null,  // "nod", "shake", "neutral"
    val brow: String? = null,       // "raised", "neutral"
    val mouth: String? = null       // "open", "closed"
)

sealed class GlossSequenceEvent {
    /**
     * Contract payload for translator integration (not wired yet).
     * Includes tokens, non-manual annotations, origin, and confidence.
     */
    data class GlossSequenceReady(
        val tokens: List<String>,
        val nonmanuals: List<NonManualAnnotation> = emptyList(),
        val origin: String = "UNKNOWN",  // "ASL", "FSL", "UNKNOWN"
        val confidence: Float = 0.0f,
        val timestamp: Long = System.currentTimeMillis()
    ) : GlossSequenceEvent()
    
    data class AlphabetWordCommitted(
        val word: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : GlossSequenceEvent()
}

