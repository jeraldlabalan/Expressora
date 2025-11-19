package com.example.expressora.recognition.di

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.ModelSignature
import com.example.expressora.recognition.grpc.LandmarkStreamer
import com.example.expressora.recognition.pipeline.RecognitionViewModelFactory
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine
import com.example.expressora.recognition.utils.DeviceCapabilityDetector
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.utils.NetworkUtils
import org.json.JSONObject
import android.content.SharedPreferences

object RecognitionProvider {
    private const val TAG = "RecognitionProvider"
    
    // Model selection order: FP32 (default) → FP16 → INT8
    // FP32 is recommended for best accuracy and simplicity (no quantization needed)
    private val MODEL_CANDIDATES = listOf(
        "expressora_unified.tflite",
        "expressora_unified_fp16.tflite",
        "expressora_unified_int8.tflite"
    )
    
    private const val SIGNATURE_ASSET = "model_signature.json"
    private const val LABELS_ASSET = "expressora_labels.json"
    private const val LABELS_MAPPED_ASSET = "expressora_labels_mapped.json"
    private const val HAND_TASK_ASSET = "hand_landmarker.task"
    const val HOLISTIC_TASK_ASSET = "holistic_landmarker.task"
    
    // Feature scaling files (required for retrained model)
    const val FEATURE_MEAN_ASSET = "feature_mean.npy"
    const val FEATURE_STD_ASSET = "feature_std.npy"

    // Unified model operates on two hands (21 landmarks * 3 coordinates * 2 hands)
    const val TWO_HANDS: Boolean = true
    const val FEATURE_DIM: Int = 126
    
    // Thresholds
    const val GLOSS_CONFIDENCE_THRESHOLD = 0.65f
    const val ORIGIN_CONFIDENCE_THRESHOLD = 0.70f

    private var selectedModel: String? = null
    private var modelSignature: ModelSignature? = null

    fun selectModel(context: Context): String {
        if (selectedModel != null) {
            LogUtils.d(TAG) { "Using cached model selection: $selectedModel" }
            return selectedModel!!
        }
        
        // Check device capabilities for optimal model selection
        val capabilities = DeviceCapabilityDetector.checkCapabilities(context)
        val recommendedModelType = capabilities.recommendedModelType
        val recommendedFilename = DeviceCapabilityDetector.getModelFilename(recommendedModelType)
        
        LogUtils.d(TAG) {
            "Device capabilities: GPU=${capabilities.hasGpu}, NNAPI=${capabilities.hasNnapi}, " +
            "Recommended: ${recommendedModelType.name} model"
        }
        
        // Try recommended model first, then fall back to available models
        val preferredOrder = listOf(recommendedFilename) + MODEL_CANDIDATES.filter { it != recommendedFilename }
        
        LogUtils.d(TAG) { "Selecting model from candidates: ${preferredOrder.joinToString()}" }
        val chosen = preferredOrder.firstOrNull { candidate ->
            val exists = runCatching {
                context.assets.open(candidate).close()
                true
            }.getOrElse { false }
            LogUtils.verboseIfVerbose(TAG) { "Checking model '$candidate': exists=$exists" }
            exists
        } ?: MODEL_CANDIDATES.last()
        
        selectedModel = chosen
        val actualModelType = when {
            chosen.contains("int8", ignoreCase = true) -> DeviceCapabilityDetector.ModelType.INT8
            chosen.contains("fp16", ignoreCase = true) -> DeviceCapabilityDetector.ModelType.FP16
            else -> DeviceCapabilityDetector.ModelType.FP32
        }
        
        if (actualModelType != recommendedModelType) {
            LogUtils.w(TAG) {
                "Selected model ($chosen) differs from recommended (${recommendedModelType.name}). " +
                "Recommended model may not be available in assets."
            }
        } else {
            LogUtils.i(TAG) { "✅ Selected optimal model: $chosen (${actualModelType.name})" }
        }
        
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
                
                // Validate input shape matches expected (126 features)
                val inputShape = inputs.firstOrNull()?.shape
                if (inputShape != null && inputShape.isNotEmpty()) {
                    val inputSize = inputShape.last() // Last dimension is feature count
                    if (inputSize != FEATURE_DIM) {
                        Log.w(TAG, "⚠️ Model input size mismatch: expected $FEATURE_DIM, got $inputSize from shape $inputShape")
                    } else {
                        Log.i(TAG, "✅ Model input shape validated: $inputShape (matches expected $FEATURE_DIM features)")
                    }
                }
                
                // Validate output size (should be 197 classes for retrained model)
                val outputShape = outputs.firstOrNull()?.shape
                if (outputShape != null && outputShape.isNotEmpty()) {
                    val outputSize = outputShape.last() // Last dimension is class count
                    Log.i(TAG, "Model output shape: $outputShape ($outputSize classes)")
                    // Note: 197 is expected for retrained model, but we don't enforce it strictly
                    // to allow for different model versions
                }
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
        
        // Verify holistic model exists (required for online mode)
        runCatching { context.assets.open(HOLISTIC_TASK_ASSET).close() }
            .onFailure { 
                Log.w(TAG, "Holistic model not found: $HOLISTIC_TASK_ASSET. Online mode will not work. " +
                        "Offline mode can still use $HAND_TASK_ASSET.")
            }
        
        runCatching { context.assets.open(LABELS_ASSET).close() }
            .onFailure { Log.w(TAG, "Labels JSON not found; runtime will synthesize CLASS_i.") }
        
        runCatching { context.assets.open(LABELS_MAPPED_ASSET).close() }
            .onFailure { Log.w(TAG, "Mapped labels not found; will use raw labels.") }
        
        // Verify feature scaling files exist (required for retrained model)
        runCatching { context.assets.open(FEATURE_MEAN_ASSET).close() }
            .onFailure { Log.e(TAG, "CRITICAL: Feature scaling file not found: $FEATURE_MEAN_ASSET") }
        
        runCatching { context.assets.open(FEATURE_STD_ASSET).close() }
            .onFailure { Log.e(TAG, "CRITICAL: Feature scaling file not found: $FEATURE_STD_ASSET") }
        
        getModelSignature(context)
        
        true
    }.getOrElse { false }

    fun provideEngine(context: Context): RecognitionEngine {
        Log.d(TAG, "Providing RecognitionEngine")
        val model = selectModel(context)
        val signature = getModelSignature(context)
        
        Log.d(TAG, "Creating TfLiteRecognitionEngine: model='$model', featureDim=$FEATURE_DIM, " +
                "hasMultiHead=${signature?.isMultiHead()}")
        
        return TfLiteRecognitionEngine(
            context = context.applicationContext,
            modelAsset = model,
            labelAsset = LABELS_ASSET,
            labelMappedAsset = LABELS_MAPPED_ASSET,
            featureDim = FEATURE_DIM,
            modelSignature = signature
        ).also {
            Log.i(TAG, "✅ RecognitionEngine created successfully")
        }
    }

    /**
     * Provide ViewModel factory with hybrid mode support (Tweak 3).
     * Checks user preference for online/offline mode.
     */
    fun provideViewModelFactory(context: Context): RecognitionViewModelFactory {
        val prefs = context.getSharedPreferences("expressora_prefs", Context.MODE_PRIVATE)
        val useOnlineMode = prefs.getBoolean("use_online_mode", true) // Default to online
        
        Log.d(TAG, "Creating ViewModelFactory: useOnlineMode=$useOnlineMode")
        
        if (useOnlineMode) {
            // Online mode: Create streamer
            val streamer = provideStreamer(context)
            return RecognitionViewModelFactory(
                engine = null,
                streamer = streamer,
                context = context,
                useOnlineMode = true
            )
        } else {
            // Offline mode: Create TFLite engine (Tweak 3 - Fallback)
            val engine = provideEngine(context)
            return RecognitionViewModelFactory(
                engine = engine,
                streamer = null,
                context = context,
                useOnlineMode = false
            )
        }
    }
    
    /**
     * Provide LandmarkStreamer for online mode.
     * Uses automatic IP detection: emulator uses 10.0.2.2, physical device uses BuildConfig.HOST_IP (detected at build time).
     */
    fun provideStreamer(context: Context): LandmarkStreamer {
        val prefs = context.getSharedPreferences("expressora_prefs", Context.MODE_PRIVATE)
        // Use NetworkUtils to get smart default (emulator vs physical device), but allow manual override via SharedPreferences
        val serverHost = prefs.getString("grpc_server_host", NetworkUtils.getGrpcHost()) 
            ?: NetworkUtils.getGrpcHost()
        val serverPort = prefs.getInt("grpc_server_port", 50051)
        
        Log.d(TAG, "Creating LandmarkStreamer: host=$serverHost, port=$serverPort (auto-detected: ${NetworkUtils.getGrpcHost()})")
        
        return LandmarkStreamer(
            context = context.applicationContext,
            serverHost = serverHost,
            serverPort = serverPort
        )
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