package com.example.expressora.recognition.di

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.ModelSignature
import com.example.expressora.recognition.pipeline.RecognitionViewModelFactory
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine
import org.json.JSONObject

object RecognitionProvider {
    private const val TAG = "RecognitionProvider"
    
    // Model selection order: INT8 → FP16 → FP32
    private val MODEL_CANDIDATES = listOf(
        "expressora_unified_int8.tflite",
        "expressora_unified_fp16.tflite",
        "expressora_unified.tflite"
    )
    
    private const val SIGNATURE_ASSET = "model_signature.json"
    private const val LABELS_ASSET = "expressora_labels.json"
    private const val LABELS_MAPPED_ASSET = "expressora_labels_mapped.json"
    private const val HAND_TASK_ASSET = "hand_landmarker.task"

    // Unified model operates on two hands (21 landmarks * 3 coordinates * 2 hands)
    const val TWO_HANDS: Boolean = true
    const val FEATURE_DIM: Int = 126
    
    // Thresholds
    const val GLOSS_CONFIDENCE_THRESHOLD = 0.65f
    const val ORIGIN_CONFIDENCE_THRESHOLD = 0.70f

    private var selectedModel: String? = null
    private var modelSignature: ModelSignature? = null

    fun selectModel(context: Context): String {
        if (selectedModel != null) return selectedModel!!
        
        val chosen = MODEL_CANDIDATES.firstOrNull { candidate ->
            runCatching {
                context.assets.open(candidate).close()
                true
            }.getOrElse { false }
        } ?: MODEL_CANDIDATES.last()
        
        selectedModel = chosen
        Log.i(TAG, "Selected model: $chosen")
        return chosen
    }
    
    fun getModelSignature(context: Context): ModelSignature? {
        if (modelSignature != null) return modelSignature
        
        return runCatching {
            val json = context.assets.open(SIGNATURE_ASSET).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            
            val inputs = obj.getJSONArray("inputs").let { arr ->
                (0 until arr.length()).map { i ->
                    val inp = arr.getJSONObject(i)
                    com.example.expressora.recognition.model.InputSpec(
                        name = inp.getString("name"),
                        index = inp.getInt("index"),
                        shape = inp.getJSONArray("shape").let { shapeArr ->
                            (0 until shapeArr.length()).map { shapeArr.getInt(it) }
                        },
                        dtype = inp.getString("dtype")
                    )
                }
            }
            
            val outputs = obj.getJSONArray("outputs").let { arr ->
                (0 until arr.length()).map { i ->
                    val out = arr.getJSONObject(i)
                    com.example.expressora.recognition.model.OutputSpec(
                        name = out.getString("name"),
                        index = out.getInt("index"),
                        shape = out.getJSONArray("shape").let { shapeArr ->
                            (0 until shapeArr.length()).map { shapeArr.getInt(it) }
                        },
                        dtype = out.getString("dtype")
                    )
                }
            }
            
            ModelSignature(
                model_type = obj.getString("model_type"),
                inputs = inputs,
                outputs = outputs
            ).also {
                modelSignature = it
                val mode = if (it.isMultiHead()) "multi-head (gloss + origin)" else "single-head (gloss only)"
                Log.i(TAG, "Model signature: $mode, ${outputs.size} output(s)")
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to parse model_signature.json: ${error.message}")
            null
        }
    }

    fun ensureAssets(context: Context): Boolean = runCatching {
        val model = selectModel(context)
        context.assets.open(model).close()
        context.assets.open(HAND_TASK_ASSET).close()
        
        runCatching { context.assets.open(LABELS_ASSET).close() }
            .onFailure { Log.w(TAG, "Labels JSON not found; runtime will synthesize CLASS_i.") }
        
        runCatching { context.assets.open(LABELS_MAPPED_ASSET).close() }
            .onFailure { Log.w(TAG, "Mapped labels not found; will use raw labels.") }
        
        getModelSignature(context)
        
        true
    }.getOrElse { false }

    fun provideEngine(context: Context): RecognitionEngine {
        val model = selectModel(context)
        val signature = getModelSignature(context)
        
        return TfLiteRecognitionEngine(
            context = context.applicationContext,
            modelAsset = model,
            labelAsset = LABELS_ASSET,
            labelMappedAsset = LABELS_MAPPED_ASSET,
            featureDim = FEATURE_DIM,
            modelSignature = signature
        )
    }

    fun provideViewModelFactory(context: Context): RecognitionViewModelFactory {
        return RecognitionViewModelFactory(provideEngine(context), context)
    }

    fun zeroFeatureVector(): FloatArray = FloatArray(FEATURE_DIM) { 0f }
    
    fun logDiagnostics(context: Context) {
        val model = selectModel(context)
        val modelVariant = when {
            model.contains("int8", ignoreCase = true) -> "INT8"
            model.contains("fp16", ignoreCase = true) -> "FP16"
            else -> "FP32"
        }
        
        val signature = getModelSignature(context)
        val outputMode = if (signature?.isMultiHead() == true) {
            "Multi-head (gloss + origin)"
        } else {
            "Single-head (gloss only)"
        }
        
        // Load labels to get count
        val labels = runCatching {
            context.assets.open(LABELS_ASSET).bufferedReader().use { 
                org.json.JSONArray(it.readText()).length()
            }
        }.getOrElse { 0 }
        
        RecognitionDiagnostics.logInitialization(
            modelVariant = modelVariant,
            outputMode = outputMode,
            delegate = "XNNPACK",
            threads = 2,
            labelCount = labels
        )
    }
}