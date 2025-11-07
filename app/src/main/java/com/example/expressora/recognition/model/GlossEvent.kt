package com.example.expressora.recognition.model

sealed class GlossEvent {
    data object Idle : GlossEvent()
    data class InProgress(val tokens: List<String>) : GlossEvent()
    data class StableChunk(val tokens: List<String>, val confidence: Float = 1f) : GlossEvent()
    data class Error(val message: String) : GlossEvent()
}