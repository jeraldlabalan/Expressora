package com.example.expressora.recognition.di

import android.content.Context
import android.util.Log
import com.example.expressora.BuildConfig
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.engine.RecognitionEngine
import com.example.expressora.recognition.model.ModelSignature
import com.example.expressora.recognition.grpc.LandmarkStreamer
import com.example.expressora.recognition.pipeline.RecognitionViewModelFactory
import com.example.expressora.recognition.tflite.TfLiteRecognitionEngine
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.utils.NetworkUtils
import org.json.JSONObject

object RecognitionProvider {
    private const val TAG = "RecognitionProvider"
    
    // FP32 model is forced - no other models are used
    private const val FP32_MODEL = "recognition/expressora_unified_v19.tflite"
    
    private const val SIGNATURE_ASSET = "recognition/model_signature.json"
    private const val LABELS_ASSET = "recognition/labels_v11.json"
    private const val LABELS_MAPPED_ASSET = "recognition/expressora_labels_mapped.json"
    private const val HAND_TASK_ASSET = "recognition/hand_landmarker.task"
    const val HOLISTIC_TASK_ASSET = "recognition/holistic_landmarker.task"
    

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
        // Check if holistic model exists first (for holistic-only mode)
        val holisticModelExists = runCatching { 
            context.assets.open(HOLISTIC_TASK_ASSET).close()
            true
        }.getOrElse { false }
        
        // If holistic model exists, TFLite model is optional (for holistic-only mode)
        // If holistic model doesn't exist, TFLite model is required
        if (holisticModelExists) {
            // Holistic mode: TFLite model is optional
            runCatching {
                val model = selectModel(context)
                context.assets.open(model).close()
            }.onFailure { e ->
                Log.d(TAG, "TFLite model not found (non-fatal for holistic mode): ${e.message}")
            }
        } else {
            // Non-holistic mode: TFLite model is required
            val model = selectModel(context)
            context.assets.open(model).close()
        }
        
        // Hand landmarker is only required if holistic model doesn't exist
        if (!holisticModelExists) {
            context.assets.open(HAND_TASK_ASSET).close()
        } else {
            Log.d(TAG, "Holistic model found: $HOLISTIC_TASK_ASSET. Hand landmarker not required for holistic mode.")
        }
        
        // Optional assets (wrapped in runCatching)
        runCatching { context.assets.open(LABELS_ASSET).close() }
            .onFailure { Log.w(TAG, "Labels JSON not found; runtime will synthesize CLASS_i.") }
        
        runCatching { context.assets.open(LABELS_MAPPED_ASSET).close() }
            .onFailure { Log.w(TAG, "Mapped labels not found; will use raw labels.") }
        
        // NOTE: Feature scaling files (mean/std) are no longer required
        // FeatureScaler now uses hardcoded robust Min-Max scaling: (x - 0.5) * 2.0
        // Mean/std files are deprecated and not used
        
        // Model signature is optional
        runCatching { 
            getModelSignature(context)
        }.onFailure { e ->
            Log.w(TAG, "Model signature parsing failed (non-fatal): ${e.message}")
        }
        
        // Return true if holistic model exists OR all required assets exist
        true
    }.getOrElse { false }

    fun provideEngine(context: Context): RecognitionEngine? {
        Log.i(TAG, "🔍 ========== PROVIDING RECOGNITION ENGINE ==========")
        val model = selectModel(context)
        Log.i(TAG, "📁 Selected model path: '$model'")
        
        // Check if model file exists with detailed error reporting
        val modelExists = runCatching {
            Log.d(TAG, "🔍 Attempting to open model file: '$model'")
            val assetFileDescriptor = context.assets.openFd(model)
            val fileSize = assetFileDescriptor.length
            assetFileDescriptor.close()
            Log.i(TAG, "✅ Model file found: '$model' (size: $fileSize bytes)")
            true
        }.getOrElse { error ->
            Log.e(TAG, "❌ Model file check FAILED: '$model'")
            Log.e(TAG, "   Error type: ${error.javaClass.simpleName}")
            Log.e(TAG, "   Error message: ${error.message}")
            Log.e(TAG, "   Stack trace: ${error.stackTrace.take(5).joinToString("\n   ")}")
            
            // Try to list available assets in recognition folder for debugging
            try {
                val assetList = context.assets.list("recognition")
                Log.e(TAG, "   Available files in 'recognition' folder: ${assetList?.joinToString(", ") ?: "null"}")
            } catch (e: Exception) {
                Log.e(TAG, "   Could not list recognition assets: ${e.message}")
            }
            false
        }
        
        if (!modelExists) {
            Log.e(TAG, "⚠️ TFLite model not found: $model. RecognitionEngine will not be created (holistic-only mode)")
            Log.e(TAG, "   This means offline recognition will not work. App will fall back to online mode.")
            return null
        }
        
        // Log model file details
        try {
            val afd = context.assets.openFd(model)
            val fileSize = afd.length
            afd.close()
            Log.i(TAG, "📦 Model file details:")
            Log.i(TAG, "   - Path: $model")
            Log.i(TAG, "   - Size: $fileSize bytes (${fileSize / 1024 / 1024} MB)")
            Log.i(TAG, "   - Expected: expressora_unified_v19.tflite (FP32)")
            if (model.contains("v19")) {
                Log.i(TAG, "   ✅ Model version confirmed: v19")
            } else {
                Log.w(TAG, "   ⚠️ Model version mismatch: expected v19, got: $model")
            }
        } catch (e: Exception) {
            Log.w(TAG, "   Could not read model file details: ${e.message}")
        }
        
        Log.i(TAG, "📋 Loading model signature...")
        val signature = getModelSignature(context)
        if (signature != null) {
            Log.i(TAG, "✅ Model signature loaded: ${if (signature.isMultiHead()) "multi-head" else "single-head"}")
        } else {
            Log.w(TAG, "⚠️ Model signature not available (will use defaults)")
        }
        
        Log.i(TAG, "🔧 Creating TfLiteRecognitionEngine with parameters:")
        Log.i(TAG, "   - model: '$model'")
        Log.i(TAG, "   - featureDim: $FEATURE_DIM")
        Log.i(TAG, "   - hasMultiHead: ${signature?.isMultiHead() ?: false}")
        Log.i(TAG, "   - labelAsset: '$LABELS_ASSET'")
        Log.i(TAG, "   - labelMappedAsset: '$LABELS_MAPPED_ASSET'")
        
        // CRITICAL: Verify model is FP32 before creating engine
        if (model != FP32_MODEL) {
            Log.e(TAG, "❌ CRITICAL ERROR: Wrong model being loaded! Expected '$FP32_MODEL' (FP32), got: '$model'")
        } else {
            Log.i(TAG, "✅ Model verified: FP32 model '$model' will be used (v19)")
        }
        
        return try {
            TfLiteRecognitionEngine(
                context = context.applicationContext,
                modelAsset = model,
                labelAsset = LABELS_ASSET,
                labelMappedAsset = LABELS_MAPPED_ASSET,
                featureDim = FEATURE_DIM,
                modelSignature = signature
            ).also {
                Log.i(TAG, "✅ RecognitionEngine created successfully with model: '$model'")
                Log.i(TAG, "🔍 ========== ENGINE PROVISION COMPLETE ==========")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Failed to create TfLiteRecognitionEngine!", e)
            Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "   Error message: ${e.message}")
            Log.e(TAG, "   Stack trace: ${e.stackTrace.take(10).joinToString("\n   ")}")
            Log.e(TAG, "🔍 ========== ENGINE PROVISION FAILED ==========")
            null
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
        
        if (engine == null) {
            Log.w(TAG, "⚠️ RecognitionEngine is null (holistic-only mode). Recognition will not work, but holistic detection will.")
        }
        
        Log.d(TAG, "Creating ViewModelFactory with both engine and streamer (runtime switching enabled)")
        
        // Default to offline mode for TFLite model testing
        // User can toggle to online mode via UI if needed
        val defaultMode = false  // Always start in offline mode
        
        return RecognitionViewModelFactory(
            engine = engine,  // May be null if TFLite model is missing (holistic-only mode)
            streamer = streamer,  // Always provide gRPC streamer
            context = context,
            useOnlineMode = defaultMode  // Default: Offline (for TFLite testing)
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