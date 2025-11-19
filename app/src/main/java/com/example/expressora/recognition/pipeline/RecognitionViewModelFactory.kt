package com.example.expressora.recognition.pipeline

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.grpc.LandmarkStreamer

class RecognitionViewModelFactory(
    private val engine: RecognitionEngine? = null,
    private val streamer: LandmarkStreamer? = null,
    private val context: Context,
    private val useOnlineMode: Boolean = true
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecognitionViewModel::class.java)) {
            return RecognitionViewModel(engine, streamer, context, useOnlineMode) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}