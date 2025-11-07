package com.example.expressora.recognition.di

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.pipeline.RecognitionViewModelFactory
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine

object RecognitionProvider {
    private const val MODEL_ASSET = "expressora_unified.tflite"
    private const val LABELS_ASSET = "expressora_labels.json"
    private const val HAND_TASK_ASSET = "hand_landmarker.task"

    // Unified model operates on two hands (21 landmarks * 3 coordinates * 2 hands)
    const val TWO_HANDS: Boolean = true
    const val FEATURE_DIM: Int = 126

    fun ensureAssets(context: Context): Boolean = runCatching {
        context.assets.open(MODEL_ASSET).close()
        context.assets.open(HAND_TASK_ASSET).close()
        runCatching { context.assets.open(LABELS_ASSET).close() }
            .onFailure { Log.w("RecognitionProvider", "Labels JSON not found; runtime will synthesize CLASS_i.") }
        true
    }.getOrElse { false }

    fun provideEngine(context: Context): RecognitionEngine {
        return TfLiteRecognitionEngine(
            context = context.applicationContext,
            modelAsset = MODEL_ASSET,
            labelAsset = LABELS_ASSET,
            featureDim = FEATURE_DIM
        )
    }

    fun provideViewModelFactory(context: Context): RecognitionViewModelFactory {
        return RecognitionViewModelFactory(provideEngine(context))
    }

    fun zeroFeatureVector(): FloatArray = FloatArray(FEATURE_DIM) { 0f }
}