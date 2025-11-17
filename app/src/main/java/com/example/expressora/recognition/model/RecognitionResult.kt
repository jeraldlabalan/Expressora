package com.example.expressora.recognition.model

data class RecognitionResult(
    val glossLabel: String,
    val glossConf: Float,
    val glossIndex: Int,
    val originLabel: String? = null,
    val originConf: Float? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val debugInfo: DebugInfo? = null
)

data class DebugInfo(
    val featureVectorStats: FeatureVectorStats,
    val rawLogits: List<TopPrediction>,
    val softmaxProbs: List<TopPrediction>,
    val averagedLogits: List<TopPrediction>? = null
)

data class FeatureVectorStats(
    val size: Int,
    val expectedSize: Int,
    val nonZeroCount: Int,
    val min: Float,
    val max: Float,
    val mean: Float,
    val isAllZeros: Boolean,
    val hasNaN: Boolean
)

data class TopPrediction(
    val index: Int,
    val label: String,
    val value: Float
)

