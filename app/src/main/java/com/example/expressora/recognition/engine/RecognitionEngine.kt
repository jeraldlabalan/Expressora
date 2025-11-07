package com.example.expressora.recognition.engine

import com.example.expressora.recognition.model.GlossEvent

interface RecognitionEngine {
    suspend fun start()
    suspend fun stop()
    val events: kotlinx.coroutines.flow.Flow<GlossEvent>
    suspend fun onLandmarks(feature: FloatArray) // e.g., 63 or 126 dims
}