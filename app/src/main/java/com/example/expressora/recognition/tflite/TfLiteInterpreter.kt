package com.example.expressora.recognition.tflite

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.utils.LogUtils
import com.example.expressora.BuildConfig
import com.example.expressora.recognition.model.ModelSignature
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

data class MultiOutputResult(
    val glossLogits: FloatArray,
    val originLogits: FloatArray?
)

class TfLiteInterpreter(
    context: Context,
    modelAssetName: String,
    private val featureDim: Int,
    private val numClasses: Int,
    private val modelSignature: ModelSignature? = null
) {
    private val TAG = "TfLiteInterpreter"
    private val hasMultiHead = modelSignature?.isMultiHead() ?: false
    private var originOutputSize: Int = 2 // Default ASL/FSL
    private var gpuDelegate: GpuDelegate? = null
    
    // Inference tracking for debugging
    private var inferenceCallCount = 0
    private var lastInputHash: Int = 0
    private var lastOutputHash: Int = 0
    private var staticOutputFrameCount = 0
    
    // Frame-by-frame ByteBuffer comparison
    private var previousBufferBytes: ByteArray? = null
    private var consecutiveIdenticalBuffers = 0
    
    // Track which delegate was successfully used
    var activeDelegate: String = "CPU"
        private set
    
    // Input quantization parameters
    private var isInputQuantized: Boolean = false
    private var inputScale: Float = 1.0f
    private var inputZeroPoint: Int = 0
    private var inputDataType: DataType? = null  // Track INT8 vs UINT8 for correct quantization range
    
    // Output quantization parameters
    private var isOutputQuantized: Boolean = false
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var isOriginOutputQuantized: Boolean = false
    private var originOutputScale: Float = 1.0f
    private var originOutputZeroPoint: Int = 0
    
    private val interpreter: Interpreter by lazy {
        // CRITICAL: Detect if model is INT8/UINT8 BEFORE setting up delegates
        // INT8 models MUST use CPU only - GPU and NNAPI do NOT support INT8 inference
        val isQuantizedModel = modelAssetName.contains("int8", ignoreCase = true) ||
                               modelAssetName.contains("uint8", ignoreCase = true)
        
        if (isQuantizedModel) {
            Log.i(TAG, "🔍 INT8/UINT8 quantized model detected: '$modelAssetName'")
            Log.w(TAG, "⚠️ CRITICAL: INT8 models require CPU-only execution. GPU and NNAPI delegates are DISABLED.")
            Log.w(TAG, "⚠️ GPU delegate does NOT support INT8 inference and will cause failures or undefined results.")
            Log.w(TAG, "⚠️ NNAPI delegate does NOT support INT8 inference reliably.")
            Log.i(TAG, "✅ Forcing CPU-only execution for quantized model (XNNPACK will be used if available)")
        }
        
        val opts = Interpreter.Options().apply { 
            val threads = PerformanceConfig.INTERPRETER_THREADS
            setNumThreads(threads)
            Log.i(TAG, "Interpreter threads: $threads")
            
            // For INT8 models, ONLY use CPU/XNNPACK - skip GPU and NNAPI
            // For FP16 models, prioritize GPU for optimal performance
            val isFp16Model = modelAssetName.contains("fp16", ignoreCase = true)
            val probeOrder = if (isQuantizedModel) {
                // Force CPU-only for quantized models
                val filtered = PerformanceConfig.DELEGATE_PROBE_ORDER.filter { 
                    it.uppercase() !in listOf("GPU", "NNAPI") 
                }
                if (filtered.isEmpty() || !filtered.contains("XNNPACK") && !filtered.contains("CPU")) {
                    listOf("XNNPACK", "CPU") // Fallback to safe options
                } else {
                    filtered
                }
            } else if (isFp16Model) {
                // For FP16 models, prioritize GPU delegate for best performance
                val configured = PerformanceConfig.DELEGATE_PROBE_ORDER.toMutableList()
                if (!configured.contains("GPU")) {
                    configured.add(0, "GPU") // Add GPU at the front if not present
                }
                // Ensure GPU is first
                listOf("GPU") + configured.filter { it.uppercase() != "GPU" }
            } else {
                // For FP32 models, use configured order
                PerformanceConfig.DELEGATE_PROBE_ORDER
            }
            
            Log.i(TAG, "Delegate probe order: ${probeOrder.joinToString(" → ")} " +
                    if (isQuantizedModel) "(GPU/NNAPI disabled for INT8 model)" else "")
            
            for (delegateName in probeOrder) {
                if (activeDelegate != "CPU") break // Already found a working delegate
                
                when (delegateName.uppercase()) {
                    "GPU" -> {
                        // CRITICAL: Skip GPU for INT8 models
                        if (isQuantizedModel) {
                            Log.w(TAG, "⛔ Skipping GPU delegate: not supported for INT8 models")
                            continue
                        }
                        
                        if (PerformanceConfig.USE_GPU) {
                            try {
                                val compatList = CompatibilityList()
                                if (compatList.isDelegateSupportedOnThisDevice) {
                                    val delegate = GpuDelegate()
                                    addDelegate(delegate)
                                    gpuDelegate = delegate
                                    activeDelegate = "GPU"
                                    Log.i(TAG, "✓ GPU delegate enabled")
                                } else {
                                    Log.i(TAG, "GPU delegate not supported on this device")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "GPU delegate failed: ${e.message}")
                            }
                        }
                    }
                    "NNAPI" -> {
                        // CRITICAL: Skip NNAPI for INT8 models
                        if (isQuantizedModel) {
                            Log.w(TAG, "⛔ Skipping NNAPI delegate: not reliably supported for INT8 models")
                            continue
                        }
                        
                        if (PerformanceConfig.USE_NNAPI && activeDelegate == "CPU") {
                            try {
                                setUseNNAPI(true)
                                activeDelegate = "NNAPI"
                                Log.i(TAG, "✓ NNAPI delegate enabled")
                            } catch (e: Exception) {
                                Log.w(TAG, "NNAPI not available: ${e.message}")
                            }
                        }
                    }
                    "CPU", "XNNPACK" -> {
                        if (PerformanceConfig.USE_XNNPACK && activeDelegate == "CPU") {
                            try {
                                setUseXNNPACK(true)
                                activeDelegate = "XNNPACK"
                                Log.i(TAG, "✓ XNNPACK delegate enabled " +
                                        if (isQuantizedModel) "(optimized for INT8)" else "")
                            } catch (e: Exception) {
                                Log.w(TAG, "XNNPACK not available: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            if (activeDelegate == "CPU") {
                if (isQuantizedModel) {
                    Log.i(TAG, "✅ Using CPU for INT8 model (correct configuration)")
                } else {
                Log.i(TAG, "Using CPU fallback (no hardware acceleration)")
                }
            }
        }
        
        val modelBuffer = runCatching { loadModelMapped(context, modelAssetName) }
            .getOrElse { loadModelDirect(context, modelAssetName) }
        
        val interp = Interpreter(modelBuffer, opts)
        
        // Inspect input tensor for quantization
        val inputTensor = interp.getInputTensor(0)
        val detectedDataType = inputTensor.dataType()
        val inputShape = inputTensor.shape()
        
        // Store data type for quantization function
        inputDataType = detectedDataType
        
        isInputQuantized = when (detectedDataType) {
            DataType.UINT8, DataType.INT8 -> {
                // Get quantization parameters
                inputScale = inputTensor.quantizationParams().scale
                inputZeroPoint = inputTensor.quantizationParams().zeroPoint
                Log.i(TAG, "✅ Quantized input confirmed: type=${detectedDataType}, scale=${inputScale}, zeroPoint=${inputZeroPoint}")
                
                // Verify delegate compatibility
                if (activeDelegate == "GPU") {
                    Log.e(TAG, "❌ CRITICAL ERROR: GPU delegate is active but model is INT8! " +
                            "This will cause failures or undefined results. Model should use CPU/XNNPACK only.")
                } else if (activeDelegate == "NNAPI") {
                    Log.w(TAG, "⚠️ WARNING: NNAPI delegate is active with INT8 model. " +
                            "This may cause issues. Consider using CPU/XNNPACK instead.")
                } else {
                    Log.i(TAG, "✅ Delegate compatibility verified: $activeDelegate supports INT8 models")
                }
                
                true
            }
            DataType.FLOAT32 -> {
                Log.i(TAG, "Float32 input detected")
                false
            }
            else -> {
                Log.w(TAG, "Unknown input data type: ${detectedDataType}, assuming float32")
                false
            }
        }
        
        Log.i(TAG, "Input shape: ${inputShape.contentToString()}, expected features: ${featureDim}")
        
        // Inspect output tensors for quantization
        val glossOutputTensor = interp.getOutputTensor(0)
        val glossOutputDataType = glossOutputTensor.dataType()
        isOutputQuantized = when (glossOutputDataType) {
            DataType.UINT8, DataType.INT8 -> {
                outputScale = glossOutputTensor.quantizationParams().scale
                outputZeroPoint = glossOutputTensor.quantizationParams().zeroPoint
                Log.i(TAG, "Quantized gloss output detected: type=${glossOutputDataType}, scale=${outputScale}, zeroPoint=${outputZeroPoint}")
                true
            }
            DataType.FLOAT32 -> {
                Log.i(TAG, "Float32 gloss output detected")
                false
            }
            else -> {
                Log.w(TAG, "Unknown gloss output data type: ${glossOutputDataType}, assuming float32")
                false
            }
        }
        
        // Inspect origin output if multi-head
        if (hasMultiHead && interp.outputTensorCount > 1) {
            val originTensor = interp.getOutputTensor(1)
            val originShape = originTensor.shape()
            originOutputSize = originShape.lastOrNull() ?: 2
            
            val originOutputDataType = originTensor.dataType()
            isOriginOutputQuantized = when (originOutputDataType) {
                DataType.UINT8, DataType.INT8 -> {
                    originOutputScale = originTensor.quantizationParams().scale
                    originOutputZeroPoint = originTensor.quantizationParams().zeroPoint
                    Log.i(TAG, "Quantized origin output detected: type=${originOutputDataType}, scale=${originOutputScale}, zeroPoint=${originOutputZeroPoint}")
                    true
                }
                DataType.FLOAT32 -> {
                    Log.i(TAG, "Float32 origin output detected")
                    false
                }
                else -> {
                    Log.w(TAG, "Unknown origin output data type: ${originOutputDataType}, assuming float32")
                    false
                }
            }
            
            Log.i(TAG, "Multi-head model detected: gloss=${numClasses}, origin=${originOutputSize}")
        } else {
            Log.i(TAG, "Single-head model detected: gloss=${numClasses}")
        }
        
        interp
    }

    private fun loadModelMapped(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            val channel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun loadModelDirect(context: Context, assetName: String): ByteBuffer {
        val bytes = context.assets.open(assetName).readBytes()
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    /**
     * Quantize float value to INT8 or UINT8 using scale and zero-point
     */
    private fun quantizeFloat(value: Float): Byte {
        val quantized = (value / inputScale).roundToInt() + inputZeroPoint
        
        // Use correct range based on data type
        val clamped = when (inputDataType) {
            DataType.INT8 -> quantized.coerceIn(-128, 127)
            DataType.UINT8 -> quantized.coerceIn(0, 255)
            else -> {
                // Fallback: assume INT8 if not set (shouldn't happen, but safer)
                Log.w(TAG, "Input data type not set, assuming INT8 for quantization")
                quantized.coerceIn(-128, 127)
            }
        }
        
        return clamped.toByte()
    }
    
    /**
     * Analyze quantization effectiveness - check if values are collapsing
     */
    private fun analyzeQuantizationEffectiveness(features: FloatArray): QuantizationAnalysis {
        if (!isInputQuantized || features.isEmpty()) {
            return QuantizationAnalysis(0, 0, emptySet(), 0f, 0f)
        }
        
        val quantizedValues = mutableSetOf<Byte>()
        var minQuantized = Byte.MAX_VALUE
        var maxQuantized = Byte.MIN_VALUE
        
        for (value in features) {
            val quantized = quantizeFloat(value)
            quantizedValues.add(quantized)
            if (quantized < minQuantized) minQuantized = quantized
            if (quantized > maxQuantized) maxQuantized = quantized
        }
        
        val featureMin = features.minOrNull() ?: 0f
        val featureMax = features.maxOrNull() ?: 0f
        val featureRange = featureMax - featureMin
        val quantizedRange = (maxQuantized.toInt() - minQuantized.toInt()).toFloat()
        
        return QuantizationAnalysis(
            distinctValues = quantizedValues.size,
            totalValues = features.size,
            sampleValues = quantizedValues.take(10).toSet(),
            featureRange = featureRange,
            quantizedRange = quantizedRange
        )
    }
    
    private data class QuantizationAnalysis(
        val distinctValues: Int,
        val totalValues: Int,
        val sampleValues: Set<Byte>,
        val featureRange: Float,
        val quantizedRange: Float
    )
    
    /**
     * Dequantize INT8/UINT8 value to float using scale and zero-point
     */
    private fun dequantizeByte(value: Byte, scale: Float, zeroPoint: Int): Float {
        val intValue = value.toInt() and 0xFF  // Convert to unsigned
        return (intValue - zeroPoint) * scale
    }
    
    /**
     * Create input buffer based on model's expected data type
     */
    private fun createInputBuffer(features: FloatArray): ByteBuffer {
        // CRITICAL: Always create a fresh buffer - never reuse
        // Validation: Ensure quantization state is properly initialized
        if (interpreter == null) {
            throw IllegalStateException("Interpreter not initialized! Cannot create input buffer.")
        }
        
        // Log feature vector statistics BEFORE quantization (only in debug builds)
        val featureMin = features.minOrNull() ?: 0f
        val featureMax = features.maxOrNull() ?: 0f
        val featureMean = features.average().toFloat()
        val featureStdDev = kotlin.math.sqrt(
            features.map { (it - featureMean) * (it - featureMean) }.average()
        ).toFloat()
        val featureNonZero = features.count { it != 0f }
        
        LogUtils.debugIfVerbose(TAG) { "📊 Feature vector stats BEFORE quantization: " +
                "min=$featureMin, max=$featureMax, mean=$featureMean, stdDev=$featureStdDev, " +
                "nonZero=$featureNonZero/${features.size}" }
        
        // Calculate expected quantized range based on scale/zeroPoint
        if (isInputQuantized) {
            val expectedQuantizedMin = quantizeFloat(featureMin)
            val expectedQuantizedMax = quantizeFloat(featureMax)
            LogUtils.debugIfVerbose(TAG) { "📊 Expected quantized range: min=$expectedQuantizedMin, max=$expectedQuantizedMax " +
                    "(scale=$inputScale, zeroPoint=$inputZeroPoint, dataType=$inputDataType)" }
        }
        
        // CRITICAL: Always allocate a NEW buffer - never reuse
        val buffer = if (isInputQuantized) {
            // For quantized models, allocate featureDim bytes (1 byte per feature)
            val buf = ByteBuffer.allocateDirect(featureDim).order(ByteOrder.nativeOrder())
            
            // CRITICAL: Clear buffer and ensure position is at start
            buf.clear()
            
            var nonZeroCount = 0
            var sampleQuantizedValues = mutableListOf<Pair<Float, Byte>>()
            val quantizedBytes = mutableListOf<Byte>()
            
            // Log first few quantizations in detail (only in debug builds)
            val logFirstN = minOf(5, featureDim)
            LogUtils.verboseIfVerbose(TAG) { "Quantizing first $logFirstN values:" }
            
            for (i in 0 until featureDim) {
                val quantized = quantizeFloat(features[i])
                buf.put(quantized)
                quantizedBytes.add(quantized)
                
                if (quantized != 0.toByte()) {
                    nonZeroCount++
                }
                
                if (i < logFirstN) {
                    LogUtils.verboseIfVerbose(TAG) { "  [$i] float=${features[i]} -> quantized=$quantized " +
                            "(formula: round(${features[i]}/$inputScale)+$inputZeroPoint)" }
                }
                
                if (sampleQuantizedValues.size < 5 && quantized != 0.toByte()) {
                    sampleQuantizedValues.add(features[i] to quantized)
                }
            }
            
            // Ensure position is 0 and limit is set correctly
            buf.rewind()
            buf.limit(featureDim)
            
            // Analyze quantization effectiveness
            val quantAnalysis = analyzeQuantizationEffectiveness(features)
            LogUtils.debugIfVerbose(TAG) { "📊 Quantization analysis: ${quantAnalysis.distinctValues} distinct values from ${quantAnalysis.totalValues} features, " +
                    "featureRange=${quantAnalysis.featureRange}, quantizedRange=${quantAnalysis.quantizedRange}, " +
                    "sampleValues=[${quantAnalysis.sampleValues.take(5).joinToString()}]" }
            
            // CRITICAL: Check if quantization is collapsing values (always log errors)
            if (quantAnalysis.distinctValues < 10) {
                Log.e(TAG, "❌ CRITICAL: Quantization collapsing values! Only ${quantAnalysis.distinctValues} distinct quantized values from ${features.size} features. " +
                        "This suggests feature scaling mismatch. Feature range: ${quantAnalysis.featureRange}, " +
                        "scale: $inputScale, zeroPoint: $inputZeroPoint")
            }
            
            // Log quantization results (errors always logged)
            if (nonZeroCount == 0) {
                Log.e(TAG, "❌ CRITICAL: All quantized values are ZERO! " +
                        "Data type: $inputDataType, scale: $inputScale, zeroPoint: $inputZeroPoint, " +
                        "sample features: [${features.take(5).joinToString()}]")
            } else {
                LogUtils.debugIfVerbose(TAG) { "✅ Quantization: $nonZeroCount/$featureDim non-zero bytes, " +
                        "dataType=$inputDataType, sample quantized: " +
                        "${sampleQuantizedValues.take(3).joinToString { "(${it.first}->${it.second})" }}" }
            }
            buf
        } else {
            // For float models, allocate 4 * featureDim bytes (4 bytes per float)
            val buf = ByteBuffer.allocateDirect(4 * featureDim).order(ByteOrder.nativeOrder())
            
            // CRITICAL: Clear buffer and ensure position is at start
            buf.clear()
            
            for (i in 0 until featureDim) {
                buf.putFloat(features[i])
            }
            // Ensure position is 0 and limit is set correctly
            buf.rewind()
            buf.limit(4 * featureDim)
            buf
        }
        
        // Verify buffer state before returning
        val expectedSize = if (isInputQuantized) featureDim else (4 * featureDim)
        if (buffer.capacity() != expectedSize) {
            throw IllegalStateException("Buffer size mismatch! Expected $expectedSize bytes, got ${buffer.capacity()}")
        }
        if (buffer.position() != 0) {
            Log.e(TAG, "❌ CRITICAL: Buffer position not at 0! Resetting from ${buffer.position()} to 0")
            buffer.position(0)
        }
        if (buffer.limit() != expectedSize) {
            Log.e(TAG, "❌ CRITICAL: Buffer limit incorrect! Expected $expectedSize, got ${buffer.limit()}. Fixing...")
            buffer.limit(expectedSize)
        }
        
        // DEBUG: Log bytes from right hand portion to verify they change (only in debug builds)
        // For two-hand vectors, left hand (first 63 values) may be zeros, so we check
        // the right hand portion where data actually changes.
        val position = buffer.position()
        buffer.rewind()
        
        // Calculate right hand start byte offset
        val rightHandStartByte = if (isInputQuantized) {
            63 // Right hand starts at feature index 63
        } else {
            252 // 4 bytes per float * 63 features = 252 bytes
        }
        
        // Read bytes from right hand portion (where data actually changes)
        val bytesToCheck = minOf(20, buffer.remaining() - rightHandStartByte)
        val rightHandBytes = if (bytesToCheck > 0 && buffer.remaining() > rightHandStartByte) {
            buffer.position(rightHandStartByte)
            val bytes = ByteArray(bytesToCheck)
            buffer.get(bytes)
            bytes
        } else {
            // Fallback: read first 20 bytes if buffer is too small
            buffer.rewind()
            ByteArray(minOf(20, buffer.remaining())).also { buffer.get(it) }
        }
        
        buffer.position(position)
        LogUtils.debugIfVerbose(TAG) { "📦 Input buffer: size=${buffer.capacity()}, position=${buffer.position()}, " +
                "limit=${buffer.limit()}, rightHandBytes[${rightHandStartByte}-${rightHandStartByte + rightHandBytes.size - 1}]=[" +
                "${rightHandBytes.joinToString { "%02x".format(it) }}]" }
        
        // CRITICAL: Frame-by-frame comparison to detect unchanged buffers (always log errors)
        // Compare right hand portion bytes where data actually changes
        if (previousBufferBytes != null && previousBufferBytes!!.size == rightHandBytes.size) {
            val isIdentical = rightHandBytes.contentEquals(previousBufferBytes!!)
            if (isIdentical) {
                consecutiveIdenticalBuffers++
                Log.e(TAG, "❌ CRITICAL: ByteBuffer IDENTICAL to previous frame! " +
                        "Consecutive identical buffers: $consecutiveIdenticalBuffers. " +
                        "This means the buffer is NOT being updated with new data!")
            } else {
                if (consecutiveIdenticalBuffers > 0) {
                    LogUtils.w(TAG) { "✅ ByteBuffer changed after $consecutiveIdenticalBuffers identical frames" }
                }
                consecutiveIdenticalBuffers = 0
            }
        } else {
            consecutiveIdenticalBuffers = 0
        }
        previousBufferBytes = rightHandBytes.copyOf()
        
        // CRITICAL: Verify ByteBuffer contains correct feature vector data
        // Read back from ByteBuffer and compare with input feature vector
        val verifyPosition = buffer.position()
        buffer.rewind()
        
        try {
            if (isInputQuantized) {
                // For quantized: read back quantized bytes and dequantize
                val rightHandStartIdx = 63
                val verifyIndices = listOf(63, 64, 65, 66, 67) // First 5 right hand features
                var mismatchCount = 0
                
                for (idx in verifyIndices) {
                    if (idx < featureDim) {
                        buffer.position(idx)
                        val quantizedByte = buffer.get()
                        val expectedQuantized = quantizeFloat(features[idx])
                        if (quantizedByte != expectedQuantized) {
                            mismatchCount++
                            if (mismatchCount <= 3) { // Log first 3 mismatches
                                Log.e(TAG, "❌ ByteBuffer verification FAILED at index $idx: " +
                                        "expected quantized=$expectedQuantized, got=$quantizedByte, " +
                                        "original float=${features[idx]}")
                            }
                        }
                    }
                }
                
                if (mismatchCount == 0) {
                    LogUtils.debugIfVerbose(TAG) { "✅ ByteBuffer verification PASSED: right hand portion matches input" }
                } else {
                    Log.e(TAG, "❌ CRITICAL: ByteBuffer verification FAILED: $mismatchCount/${verifyIndices.size} values mismatch! " +
                            "ByteBuffer may not contain correct feature vector data!")
                }
            } else {
                // For float: read back floats directly
                val rightHandStartIdx = 63
                val verifyIndices = listOf(63, 64, 65, 66, 67) // First 5 right hand features
                var mismatchCount = 0
                val tolerance = 0.0001f
                
                for (idx in verifyIndices) {
                    if (idx < featureDim) {
                        buffer.position(idx * 4) // 4 bytes per float
                        val bufferFloat = buffer.getFloat()
                        val expectedFloat = features[idx]
                        val diff = kotlin.math.abs(bufferFloat - expectedFloat)
                        
                        if (diff > tolerance) {
                            mismatchCount++
                            if (mismatchCount <= 3) { // Log first 3 mismatches
                                Log.e(TAG, "❌ ByteBuffer verification FAILED at index $idx: " +
                                        "expected=$expectedFloat, got=$bufferFloat, diff=$diff")
                            }
                        }
                    }
                }
                
                if (mismatchCount == 0) {
                    LogUtils.debugIfVerbose(TAG) { "✅ ByteBuffer verification PASSED: right hand portion matches input" }
                } else {
                    Log.e(TAG, "❌ CRITICAL: ByteBuffer verification FAILED: $mismatchCount/${verifyIndices.size} values mismatch! " +
                            "ByteBuffer may not contain correct feature vector data!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during ByteBuffer verification: ${e.message}", e)
        } finally {
            buffer.position(verifyPosition)
        }
        
        // DEBUG: Calculate input hash to verify it changes (only in debug builds)
        val inputHash = calculateBufferHash(buffer)
        if (inputHash != lastInputHash) {
            LogUtils.debugIfVerbose(TAG) { "✅ Input buffer changed: hash=$inputHash (was=$lastInputHash), " +
                    "rightHandFeatures[63-67]=[${features.slice(63..67).joinToString()}]" }
            lastInputHash = inputHash
        } else {
            Log.e(TAG, "❌ CRITICAL: Input buffer UNCHANGED: hash=$inputHash (same as last frame!) " +
                    "This indicates the buffer content is not changing between frames!")
        }
        
        return buffer
    }
    
    /**
     * Calculate a simple hash of buffer contents for change detection
     * For two-hand vectors, left hand (first 63 values) may be zeros, so we check
     * the right hand portion where data actually changes.
     */
    private fun calculateBufferHash(buffer: ByteBuffer): Int {
        val position = buffer.position()
        buffer.rewind()
        
        var hash = 0
        
        // For two-hand vectors (126 features), right hand starts at:
        // - Quantized: byte 63 (feature index 63)
        // - Float: byte 252 (4 bytes * 63 features)
        val rightHandStartByte = if (isInputQuantized) {
            63 // Right hand starts at feature index 63
        } else {
            252 // 4 bytes per float * 63 features = 252 bytes
        }
        
        // Check bytes from right hand portion (where data actually changes)
        val bytesToCheck = minOf(20, buffer.remaining() - rightHandStartByte)
        
        if (bytesToCheck > 0 && buffer.remaining() > rightHandStartByte) {
            // Skip to right hand portion
            buffer.position(rightHandStartByte)
            repeat(bytesToCheck) {
                hash = 31 * hash + (buffer.get().toInt() and 0xFF)
            }
        } else {
            // Fallback: check first 20 bytes if buffer is too small
            buffer.rewind()
            val fallbackBytes = minOf(20, buffer.remaining())
            repeat(fallbackBytes) {
                hash = 31 * hash + (buffer.get().toInt() and 0xFF)
            }
        }
        
        buffer.position(position)
        return hash
    }
    
    /**
     * Write input data directly to the input tensor buffer.
     * This ensures the data is actually in the tensor before inference.
     * Returns true if successful, false if fallback to ByteBuffer is needed.
     */
    private fun writeToInputTensor(features: FloatArray): Boolean {
        require(interpreter != null) { "Interpreter not initialized!" }
        require(features.size == featureDim) { "Expected $featureDim features, got ${features.size}" }
        
        val inputTensor: Tensor = interpreter!!.getInputTensor(0)
        
        // Try to get tensor buffer - use reflection-safe approach
        val tensorBuffer = try {
            // Try asReadWriteBuffer() first (newer API)
            inputTensor.javaClass.getMethod("asReadWriteBuffer").invoke(inputTensor) as? ByteBuffer
        } catch (e: Exception) {
            try {
                // Fallback to buffer() method (older API)
                inputTensor.javaClass.getMethod("buffer").invoke(inputTensor) as? ByteBuffer
            } catch (e2: Exception) {
                null
            }
        }
        
        if (tensorBuffer == null) {
            // If direct tensor buffer access fails, log and return false
            // The ByteBuffer approach will be used instead
            Log.w(TAG, "⚠️ Cannot access tensor buffer directly, using ByteBuffer approach")
            return false
        }
        
        // Save current position to restore later
        val originalPosition = tensorBuffer.position()
        tensorBuffer.rewind()
        
        try {
            if (isInputQuantized) {
                // For quantized inputs, write bytes directly
                var nonZeroCount = 0
                val sampleQuantizedValues = mutableListOf<Pair<Float, Byte>>()
                
                for (i in 0 until featureDim) {
                    val quantized = quantizeFloat(features[i])
                    tensorBuffer.put(quantized)
                    if (quantized != 0.toByte()) {
                        nonZeroCount++
                        if (sampleQuantizedValues.size < 5) {
                            sampleQuantizedValues.add(features[i] to quantized)
                        }
                    }
                }
                
                // Log quantization results (errors always logged)
                if (nonZeroCount == 0) {
                    Log.e(TAG, "❌ CRITICAL: All quantized values are ZERO in tensor buffer! " +
                            "Data type: $inputDataType, scale: $inputScale, zeroPoint: $inputZeroPoint")
                } else {
                    LogUtils.debugIfVerbose(TAG) { "Tensor buffer quantization: $nonZeroCount/$featureDim non-zero bytes, " +
                            "dataType=$inputDataType, sample: " +
                            "${sampleQuantizedValues.take(3).joinToString { "(${it.first}->${it.second})" }}" }
                }
                
                // Log first 10 bytes from tensor buffer for verification (only in debug builds)
                val position = tensorBuffer.position()
                tensorBuffer.rewind()
                val firstBytes = ByteArray(minOf(10, tensorBuffer.remaining()))
                tensorBuffer.get(firstBytes)
                tensorBuffer.position(position)
                LogUtils.debugIfVerbose(TAG) { "Tensor buffer written: first10bytes=[${firstBytes.joinToString(separator = ", ", transform = { "%02x".format(it) })}]" }
                
            } else {
                // For float inputs, write floats directly
                for (i in 0 until featureDim) {
                    tensorBuffer.putFloat(features[i])
                }
                
                // Log first 3 values from tensor buffer for verification (only in debug builds)
                val position = tensorBuffer.position()
                tensorBuffer.rewind()
                val firstValues = FloatArray(minOf(3, featureDim))
                for (idx in firstValues.indices) {
                    firstValues[idx] = tensorBuffer.getFloat()
                }
                tensorBuffer.position(position)
                LogUtils.debugIfVerbose(TAG) { "Tensor buffer written: first3values=[${firstValues.joinToString()}]" }
            }
            
            // Calculate hash of tensor buffer contents for change detection
            tensorBuffer.rewind()
            var tensorHash = 0
            val bytesToCheck = minOf(20, tensorBuffer.remaining())
            repeat(bytesToCheck) {
                tensorHash = 31 * tensorHash + (tensorBuffer.get().toInt() and 0xFF)
            }
            
            if (tensorHash != lastInputHash) {
                LogUtils.debugIfVerbose(TAG) { "✅ Tensor buffer changed: hash=$tensorHash (was=$lastInputHash)" }
                lastInputHash = tensorHash
            } else {
                LogUtils.w(TAG) { "⚠️ Tensor buffer UNCHANGED: hash=$tensorHash (same as last frame!)" }
            }
            
            return true
            
        } finally {
            // Restore original position
            tensorBuffer.position(originalPosition)
        }
    }

    fun run(features: FloatArray): FloatArray {
        require(features.size == featureDim) { "Expected $featureDim features, got ${features.size}" }
        val input = createInputBuffer(features)
        val output = Array(1) { FloatArray(numClasses) }
        interpreter.run(input, output)
        return output[0]
    }
    
    fun runMultiOutput(features: FloatArray): MultiOutputResult {
        require(features.size == featureDim) { "Expected $featureDim features, got ${features.size}" }
        
        inferenceCallCount++
        
        // Validate input tensor (only log errors in release, full logs in debug)
        try {
            val inputTensor = interpreter.getInputTensor(0)
            val tensorShape = inputTensor.shape()
            val tensorDataType = inputTensor.dataType()
            
            LogUtils.debugIfVerbose(TAG) { "Input tensor: shape=${tensorShape.contentToString()}, " +
                    "dataType=$tensorDataType, expectedFeatures=$featureDim" }
            
            // Verify tensor shape matches expected (always log errors)
            if (tensorShape.size == 2 && tensorShape[1] != featureDim) {
                Log.e(TAG, "❌ CRITICAL: Input tensor shape mismatch! " +
                        "Expected [1, $featureDim], got ${tensorShape.contentToString()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error validating input tensor: ${e.message}", e)
        }
        
        // CRITICAL: Create ByteBuffer with fresh data FIRST
        // This ensures we have a properly formatted buffer with current frame's data
        // IMPORTANT: createInputBuffer() always creates a NEW buffer, never reuses old ones
        val inputBuffer = createInputBuffer(features)
        
        // CRITICAL: Calculate expected size first (used in multiple places)
        val expectedSize = if (isInputQuantized) featureDim else (4 * featureDim)
        
        // CRITICAL: Try to write directly to input tensor buffer as well
        // This ensures the data is actually in the tensor before inference
        // If this fails, we'll rely on the ByteBuffer being passed to interpreter.run()
        val tensorBufferWritten = writeToInputTensor(features)
        
        // CRITICAL: If tensor buffer write failed, try to explicitly copy ByteBuffer to input tensor
        // This ensures the interpreter uses the fresh data from ByteBuffer
        if (!tensorBufferWritten) {
            Log.d(TAG, "⚠️ Tensor buffer write failed, attempting to copy ByteBuffer to input tensor")
            
            try {
                val inputTensor = interpreter.getInputTensor(0)
                // Try to get tensor buffer to copy ByteBuffer into it
                val tensorBuffer = try {
                    inputTensor.javaClass.getMethod("asReadWriteBuffer").invoke(inputTensor) as? ByteBuffer
                } catch (e: Exception) {
                    try {
                        inputTensor.javaClass.getMethod("buffer").invoke(inputTensor) as? ByteBuffer
                    } catch (e2: Exception) {
                        null
                    }
                }
                
                if (tensorBuffer != null) {
                    // Copy ByteBuffer content to tensor buffer
                    val savedPosition = inputBuffer.position()
                    val savedLimit = inputBuffer.limit()
                    inputBuffer.rewind()
                    inputBuffer.limit(expectedSize)
                    
                    val tensorPosition = tensorBuffer.position()
                    tensorBuffer.rewind()
                    tensorBuffer.put(inputBuffer)
                    tensorBuffer.position(tensorPosition)
                    
                    inputBuffer.position(savedPosition)
                    inputBuffer.limit(savedLimit)
                    
                    Log.d(TAG, "✅ Successfully copied ByteBuffer to input tensor buffer")
                } else {
                    Log.w(TAG, "⚠️ Could not access tensor buffer, will rely on interpreter.run() using ByteBuffer")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error copying ByteBuffer to tensor: ${e.message}, will rely on interpreter.run()")
            }
            
            // Ensure ByteBuffer is at position 0 and ready for interpreter.run()
            inputBuffer.rewind()
            inputBuffer.limit(expectedSize)
            
            // Log that we're using ByteBuffer approach
            Log.d(TAG, "✅ Using ByteBuffer for inference: position=${inputBuffer.position()}, " +
                    "limit=${inputBuffer.limit()}, remaining=${inputBuffer.remaining()}")
        }
        
        // CRITICAL: Reset ByteBuffer position and limit to ensure interpreter reads from start
        inputBuffer.rewind()
        inputBuffer.limit(expectedSize)
        
        // Verify ByteBuffer state before inference
        if (inputBuffer.position() != 0) {
            Log.e(TAG, "❌ CRITICAL: ByteBuffer position is ${inputBuffer.position()}, expected 0! Resetting...")
            inputBuffer.position(0)
        }
        if (inputBuffer.remaining() != expectedSize) {
            Log.e(TAG, "❌ CRITICAL: ByteBuffer remaining is ${inputBuffer.remaining()}, expected $expectedSize! Fixing...")
            inputBuffer.limit(inputBuffer.position() + expectedSize)
        }
        
        // CRITICAL: Verify ByteBuffer contains expected data from right hand portion (only in debug builds)
        // Check right hand portion where data actually changes
        val verifyPosition = inputBuffer.position()
        val rightHandStartByte = if (isInputQuantized) {
            63 // Right hand starts at feature index 63
        } else {
            252 // 4 bytes per float * 63 features = 252 bytes
        }
        
        inputBuffer.rewind()
        if (isInputQuantized) {
            // Check right hand portion bytes
            if (inputBuffer.remaining() > rightHandStartByte) {
                inputBuffer.position(rightHandStartByte)
                val verifyBytes = ByteArray(minOf(10, inputBuffer.remaining()))
                inputBuffer.get(verifyBytes)
                val nonZeroVerify = verifyBytes.count { it != 0.toByte() }
                LogUtils.debugIfVerbose(TAG) { "ByteBuffer verification (right hand): bytes[${rightHandStartByte}-${rightHandStartByte + verifyBytes.size - 1}]=[" +
                        "${verifyBytes.joinToString(separator = ", ", transform = { "%02x".format(it) })}], " +
                        "nonZero=$nonZeroVerify/${verifyBytes.size}" }
                if (nonZeroVerify == 0) {
                    Log.e(TAG, "❌ CRITICAL: ByteBuffer right hand portion bytes are ALL ZERO! Input data may not be written correctly!")
                }
            }
        } else {
            // Check right hand portion floats (indices 63-67)
            if (inputBuffer.remaining() > rightHandStartByte) {
                inputBuffer.position(rightHandStartByte)
                val verifyValues = FloatArray(minOf(5, (inputBuffer.remaining() / 4).toInt()))
                for (idx in verifyValues.indices) {
                    verifyValues[idx] = inputBuffer.getFloat()
                }
                LogUtils.debugIfVerbose(TAG) { "ByteBuffer verification (right hand): floats[63-${63 + verifyValues.size - 1}]=[${verifyValues.joinToString()}]" }
            }
        }
        inputBuffer.position(verifyPosition)
        
        // DEBUG: Log before inference (only in debug builds)
        LogUtils.debugIfVerbose(TAG) { "🔄 Running inference #$inferenceCallCount: inputHash=$lastInputHash, " +
                "tensorBufferWritten=$tensorBufferWritten, bufferPos=${inputBuffer.position()}, " +
                "bufferRemaining=${inputBuffer.remaining()}, bufferLimit=${inputBuffer.limit()}" }
        
        // Create output buffers based on quantization
        val glossLogits = if (isOutputQuantized) {
            // For quantized output, use ByteArray then dequantize
            val quantizedOutput = Array(1) { ByteArray(numClasses) }
            
            if (hasMultiHead && interpreter.outputTensorCount > 1) {
                val originQuantizedOutput = if (isOriginOutputQuantized) {
                    Array(1) { ByteArray(originOutputSize) }
                } else {
                    null
                }
                val originFloatOutput = if (!isOriginOutputQuantized) {
                    Array(1) { FloatArray(originOutputSize) }
                } else {
                    null
                }
                
                val outputs = mutableMapOf<Int, Any>(
                    0 to quantizedOutput
                )
                if (originQuantizedOutput != null) {
                    outputs[1] = originQuantizedOutput
                } else if (originFloatOutput != null) {
                    outputs[1] = originFloatOutput
                }
                
                // DEBUG: Initialize output to known value to verify model writes to it (only in debug builds)
                quantizedOutput[0].fill(0)
                val outputHashBefore = calculateOutputHash(quantizedOutput[0])
                val firstByteBefore = quantizedOutput[0][0]
                LogUtils.debugIfVerbose(TAG) { "Before runForMultipleInputsOutputs: outputHash=$outputHashBefore, firstByte=$firstByteBefore" }
                
                // CRITICAL: Ensure ByteBuffer is at position 0 before inference
                inputBuffer.rewind()
                inputBuffer.limit(expectedSize)
                interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
                
                // DEBUG: Log after model execution (only in debug builds)
                val outputHashAfter = calculateOutputHash(quantizedOutput[0])
                val firstByteAfter = quantizedOutput[0][0]
                val outputChanged = outputHashAfter != outputHashBefore
                LogUtils.debugIfVerbose(TAG) { "After runForMultipleInputsOutputs: outputHash=$outputHashAfter " +
                        "(changed=$outputChanged), firstByte=$firstByteAfter" }
                
                if (!outputChanged) {
                    Log.e(TAG, "❌ CRITICAL ERROR: Model output did NOT change after inference! " +
                            "Model may not be executing. firstByte: $firstByteBefore -> $firstByteAfter")
                }
                
                if (outputHashAfter == lastOutputHash && inferenceCallCount > 1) {
                    staticOutputFrameCount++
                    if (staticOutputFrameCount > 3) {
                        Log.e(TAG, "❌ ERROR: Model output is STATIC! Same output for $staticOutputFrameCount frames. " +
                                "Model may not be running inference correctly!")
                    }
                } else {
                    staticOutputFrameCount = 0
                }
                lastOutputHash = outputHashAfter
                
                // Dequantize gloss output
                val glossFloat = FloatArray(numClasses) { i ->
                    dequantizeByte(quantizedOutput[0][i], outputScale, outputZeroPoint)
                }
                
                // Dequantize or use origin output
                val originFloat = if (originQuantizedOutput != null) {
                    FloatArray(originOutputSize) { i ->
                        dequantizeByte(originQuantizedOutput[0][i], originOutputScale, originOutputZeroPoint)
                    }
                } else {
                    originFloatOutput?.get(0)
                }
                
                MultiOutputResult(glossFloat, originFloat)
            } else {
                // DEBUG: Initialize output to known value to verify model writes to it (only in debug builds)
                quantizedOutput[0].fill(0)
                val outputHashBefore = calculateOutputHash(quantizedOutput[0])
                val firstByteBefore = quantizedOutput[0][0]
                LogUtils.debugIfVerbose(TAG) { "Before interpreter.run: outputHash=$outputHashBefore, firstByte=$firstByteBefore" }
                
                // CRITICAL: Ensure ByteBuffer is at position 0 before inference
                inputBuffer.rewind()
                inputBuffer.limit(expectedSize)
                interpreter.run(inputBuffer, quantizedOutput)
                
                // DEBUG: Log after model execution (only in debug builds)
                val outputHashAfter = calculateOutputHash(quantizedOutput[0])
                val firstByteAfter = quantizedOutput[0][0]
                val outputChanged = outputHashAfter != outputHashBefore
                LogUtils.debugIfVerbose(TAG) { "After interpreter.run: outputHash=$outputHashAfter " +
                        "(changed=$outputChanged), firstByte=$firstByteAfter" }
                
                if (!outputChanged) {
                    Log.e(TAG, "❌ CRITICAL ERROR: Model output did NOT change after inference! " +
                            "Model may not be executing. firstByte: $firstByteBefore -> $firstByteAfter")
                }
                
                if (outputHashAfter == lastOutputHash && inferenceCallCount > 1) {
                    staticOutputFrameCount++
                    if (staticOutputFrameCount > 3) {
                        Log.e(TAG, "❌ ERROR: Model output is STATIC! Same output for $staticOutputFrameCount frames. " +
                                "Model may not be running inference correctly!")
                    }
                } else {
                    staticOutputFrameCount = 0
                }
                lastOutputHash = outputHashAfter
                
                // Dequantize
                val glossFloat = FloatArray(numClasses) { i ->
                    dequantizeByte(quantizedOutput[0][i], outputScale, outputZeroPoint)
                }
                
                MultiOutputResult(glossFloat, null)
            }
        } else {
            // For float output, use FloatArray directly
            val glossOutput = Array(1) { FloatArray(numClasses) }
            
            if (hasMultiHead && interpreter.outputTensorCount > 1) {
                val originOutput = Array(1) { FloatArray(originOutputSize) }
                val outputs = mutableMapOf<Int, Any>(
                    0 to glossOutput,
                    1 to originOutput
                )
                // DEBUG: Initialize output to known value to verify model writes to it (only in debug builds)
                glossOutput[0].fill(0f)
                val outputHashBefore = calculateOutputHash(glossOutput[0])
                val firstValueBefore = glossOutput[0][0]
                LogUtils.debugIfVerbose(TAG) { "Before runForMultipleInputsOutputs (float): outputHash=$outputHashBefore, firstValue=$firstValueBefore" }
                
                // CRITICAL: Ensure ByteBuffer is at position 0 before inference
                inputBuffer.rewind()
                inputBuffer.limit(expectedSize)
                interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
                
                // DEBUG: Log after model execution (only in debug builds)
                val outputHashAfter = calculateOutputHash(glossOutput[0])
                val firstValueAfter = glossOutput[0][0]
                val outputChanged = outputHashAfter != outputHashBefore
                LogUtils.debugIfVerbose(TAG) { "After runForMultipleInputsOutputs (float): outputHash=$outputHashAfter " +
                        "(changed=$outputChanged), firstValue=$firstValueAfter" }
                
                if (!outputChanged) {
                    Log.e(TAG, "❌ CRITICAL ERROR: Model output did NOT change after inference! " +
                            "Model may not be executing. firstValue: $firstValueBefore -> $firstValueAfter")
                }
                
                if (outputHashAfter == lastOutputHash && inferenceCallCount > 1) {
                    staticOutputFrameCount++
                    if (staticOutputFrameCount > 3) {
                        Log.e(TAG, "❌ ERROR: Model output is STATIC! Same output for $staticOutputFrameCount frames. " +
                                "Model may not be running inference correctly!")
                    }
                } else {
                    staticOutputFrameCount = 0
                }
                lastOutputHash = outputHashAfter
                
                MultiOutputResult(glossOutput[0], originOutput[0])
            } else {
                // DEBUG: Initialize output to known value to verify model writes to it (only in debug builds)
                glossOutput[0].fill(0f)
                val outputHashBefore = calculateOutputHash(glossOutput[0])
                val firstValueBefore = glossOutput[0][0]
                LogUtils.debugIfVerbose(TAG) { "Before interpreter.run (float): outputHash=$outputHashBefore, firstValue=$firstValueBefore" }
                
                // CRITICAL: Ensure ByteBuffer is at position 0 before inference
                inputBuffer.rewind()
                inputBuffer.limit(expectedSize)
                interpreter.run(inputBuffer, glossOutput)
                
                // DEBUG: Log after model execution (only in debug builds)
                val outputHashAfter = calculateOutputHash(glossOutput[0])
                val firstValueAfter = glossOutput[0][0]
                val outputChanged = outputHashAfter != outputHashBefore
                LogUtils.debugIfVerbose(TAG) { "After interpreter.run (float): outputHash=$outputHashAfter " +
                        "(changed=$outputChanged), firstValue=$firstValueAfter" }
                
                if (!outputChanged) {
                    Log.e(TAG, "❌ CRITICAL ERROR: Model output did NOT change after inference! " +
                            "Model may not be executing. firstValue: $firstValueBefore -> $firstValueAfter")
                }
                
                if (outputHashAfter == lastOutputHash && inferenceCallCount > 1) {
                    staticOutputFrameCount++
                    if (staticOutputFrameCount > 3) {
                        Log.e(TAG, "❌ ERROR: Model output is STATIC! Same output for $staticOutputFrameCount frames. " +
                                "Model may not be running inference correctly!")
                    }
                } else {
                    staticOutputFrameCount = 0
                }
                lastOutputHash = outputHashAfter
                
                MultiOutputResult(glossOutput[0], null)
            }
        }
        
        return glossLogits
    }
    
    /**
     * Calculate a simple hash of output array for change detection
     */
    private fun calculateOutputHash(output: ByteArray): Int {
        var hash = 0
        val bytesToCheck = minOf(10, output.size) // Check first 10 bytes
        for (i in 0 until bytesToCheck) {
            hash = 31 * hash + output[i].toInt()
        }
        return hash
    }
    
    private fun calculateOutputHash(output: FloatArray): Int {
        var hash = 0
        val valuesToCheck = minOf(5, output.size) // Check first 5 values
        for (i in 0 until valuesToCheck) {
            hash = 31 * hash + output[i].toBits()
        }
        return hash
    }
    
    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
