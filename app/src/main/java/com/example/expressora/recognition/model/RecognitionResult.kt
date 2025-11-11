package com.example.expressora.recognition.model

data class RecognitionResult(
    val glossLabel: String,
    val glossConf: Float,
    val glossIndex: Int,
    val originLabel: String? = null,
    val originConf: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

