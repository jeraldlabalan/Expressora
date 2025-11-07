package com.example.expressora.recognition.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.GlossEvent
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecognitionViewModel(private val engine: RecognitionEngine) : ViewModel() {
    private val _state = MutableStateFlow<GlossEvent>(GlossEvent.Idle)
    val state: StateFlow<GlossEvent> = _state

    init {
        viewModelScope.launch {
            engine.start()
            engine.events
                .debounce(500L) // stabilize
                .map { ev ->
                    if (ev is GlossEvent.InProgress) {
                        val capped = ev.tokens.take(7) // ≤7
                        GlossEvent.StableChunk(capped, 0.9f)
                    } else ev
                }
                .collect { _state.value = it }
        }
    }

    fun onFeatures(vec: FloatArray) = viewModelScope.launch { engine.onLandmarks(vec) }

    override fun onCleared() {
        viewModelScope.launch { engine.stop() }
        super.onCleared()
    }
}