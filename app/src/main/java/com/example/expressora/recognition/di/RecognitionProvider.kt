package com.example.expressora.recognition.di

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.ModelSignature
import com.example.expressora.recognition.grpc.LandmarkStreamer
import com.example.expressora.recognition.pipeline.RecognitionViewModelFactory
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.utils.NetworkUtils
import org.json.JSONObject
import android.content.SharedPreferences

object RecognitionProvider {
    private const val TAG = "RecognitionProvider"
    
    // FP32 model is forced - no other models are used
    private const val FP32_MODEL = "recognition/expressora_unified_v3.tflite"
    
    private const val SIGNATURE_ASSET = "recognition/model_signature.json"
    private const val LABELS_ASSET = "recognition/labels.json"
    private const val LABELS_MAPPED_ASSET = "recognition/expressora_labels_mapped.json"
    private const val HAND_TASK_ASSET = "recognition/hand_landmarker.task"
    const val HOLISTIC_TASK_ASSET = "recognition/holistic_landmarker.task"
    
    // Feature scaling files (required for retrained model) - Version 2
    const val FEATURE_MEAN_ASSET = "recognition/feature_mean_v2.npy"
    const val FEATURE_STD_ASSET = "recognition/feature_std_v2.npy"

    // Holistic LSTM model operates on sequences: 30 frames × 237 features per frame
    // Features per frame: Left Hand (63) + Right Hand (63) + Face (111) = 237
    // Total input size: 30 frames × 237 features = 7110 (flattened) or [1, 30, 237] (3D tensor)
    const val TWO_HANDS: Boolean = true
    const val FEATURE_DIM: Int = 7110 // 30 frames × 237 features per frame
    const val FEATURES_PER_FRAME: Int = 237 // Left Hand (63) + Right Hand (63) + Face (111)
    const val SEQUENCE_LENGTH: Int = 30 // Number of frames in sequence
    
    // Thresholds
    const val GLOSS_CONFIDENCE_THRESHOLD = 0.65f
    const val ORIGIN_CONFIDENCE_THRESHOLD = 0.70f

    // Removed selectedModel caching - always return FP32 model directly
    private var modelSignature: ModelSignature? = null

    fun selectModel(context: Context): String {
        // FORCE FP32 MODEL - No device detection, no fallback, no caching, no other options
        // This model expects Float32 inputs (28440 bytes) which matches what we generate
        LogUtils.i(TAG) { "✅ FORCING FP32 model: $FP32_MODEL (Float32 inputs = 28440 bytes, LSTM compatible)" }
        return FP32_MODEL
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
                
                // Validate input shape matches expected LSTM model shape [1, 30, 237] or flattened [1, 7110]
                val inputShape = inputs.firstOrNull()?.shape
                if (inputShape != null && inputShape.isNotEmpty()) {
                    // Check if it's a 3D tensor [Batch, Time, Features] or flattened [Batch, Features]
                    when (inputShape.size) {
                        3 -> {
                            // 3D tensor: [Batch, Time, Features] = [1, 30, 237]
                            val batch = inputShape[0]
                            val time = inputShape[1]
                            val features = inputShape[2]
                            if (batch == 1 && time == SEQUENCE_LENGTH && features == FEATURES_PER_FRAME) {
                                Log.i(TAG, "✅ Model input shape validated: $inputShape (matches expected [1, $SEQUENCE_LENGTH, $FEATURES_PER_FRAME])")
                            } else {
                                Log.w(TAG, "⚠️ Model input shape mismatch: expected [1, $SEQUENCE_LENGTH, $FEATURES_PER_FRAME], got $inputShape")
                            }
                        }
                        2 -> {
                            // Flattened: [Batch, Features] = [1, 7110]
                            val batch = inputShape[0]
                            val features = inputShape[1]
                            if (batch == 1 && features == FEATURE_DIM) {
                                Log.i(TAG, "✅ Model input shape validated: $inputShape (matches expected [1, $FEATURE_DIM] flattened)")
                            } else {
                                Log.w(TAG, "⚠️ Model input shape mismatch: expected [1, $FEATURE_DIM], got $inputShape")
                            }
                        }
                        else -> {
                            Log.w(TAG, "⚠️ Unexpected input shape dimensions: $inputShape (expected 2D or 3D tensor)")
                        }
                    }
                }
                
                // Validate output size (should be 250 classes for new LSTM model)
                val outputShape = outputs.firstOrNull()?.shape
                if (outputShape != null && outputShape.isNotEmpty()) {
                    val outputSize = outputShape.last() // Last dimension is class count
                    Log.i(TAG, "Model output shape: $outputShape ($outputSize classes)")
                    // Note: 250 is expected for new LSTM model, but we don't enforce it strictly
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
        
        // CRITICAL: Verify model is FP32 before creating engine
        if (model != FP32_MODEL) {
            Log.e(TAG, "❌ CRITICAL ERROR: Wrong model being loaded! Expected '$FP32_MODEL' (FP32), got: '$model'")
        } else {
            Log.i(TAG, "✅ Model verified: FP32 model '$model' will be used (v3)")
        }
        
        return TfLiteRecognitionEngine(
            context = context.applicationContext,
            modelAsset = model,
            labelAsset = LABELS_ASSET,
            labelMappedAsset = LABELS_MAPPED_ASSET,
            featureDim = FEATURE_DIM,
            modelSignature = signature
        ).also {
            Log.i(TAG, "✅ RecognitionEngine created successfully with model: '$model'")
        }
    }

    /**
     * Provide ViewModel factory with hybrid mode support (Tweak 3).
     * Always provides BOTH engine and streamer to enable runtime switching.
     */
    fun provideViewModelFactory(context: Context): RecognitionViewModelFactory {
        // CRITICAL: Always provide both engine AND streamer for runtime switching
        // This ensures that toggling between online/offline modes works instantly
        val streamer = provideStreamer(context)
        val engine = provideEngine(context)
        
        Log.d(TAG, "Creating ViewModelFactory with both engine and streamer (runtime switching enabled)")
        
        return RecognitionViewModelFactory(
            engine = engine,  // Always provide TFLite engine
            streamer = streamer,  // Always provide gRPC streamer
            context = context,
            useOnlineMode = true  // Default start state (Online Mode)
        )
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
        // Always FP32 - no other models are used
        val modelVariant = "FP32"
        
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