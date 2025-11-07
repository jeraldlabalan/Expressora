package com.example.expressora.recognition.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.expressora.recognition.engine.RecognitionEngine

class RecognitionViewModelFactory(private val engine: RecognitionEngine) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecognitionViewModel::class.java)) {
            return RecognitionViewModel(engine) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}