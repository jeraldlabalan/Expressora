package com.example.expressora.recognition.tflite

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.model.ModelSignature
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
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
    
    // Track which delegate was successfully used
    var activeDelegate: String = "CPU"
        private set
    
    // Input quantization parameters
    private var isInputQuantized: Boolean = false
    private var inputScale: Float = 1.0f
    private var inputZeroPoint: Int = 0
    
    // Output quantization parameters
    private var isOutputQuantized: Boolean = false
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var isOriginOutputQuantized: Boolean = false
    private var originOutputScale: Float = 1.0f
    private var originOutputZeroPoint: Int = 0
    
    private val interpreter: Interpreter by lazy {
        val opts = Interpreter.Options().apply { 
            val threads = PerformanceConfig.INTERPRETER_THREADS
            setNumThreads(threads)
            Log.i(TAG, "Interpreter threads: $threads")
            
            // Probe delegates in configured priority order
            val probeOrder = PerformanceConfig.DELEGATE_PROBE_ORDER
            Log.i(TAG, "Delegate probe order: ${probeOrder.joinToString(" → ")}")
            
            for (delegateName in probeOrder) {
                if (activeDelegate != "CPU") break // Already found a working delegate
                
                when (delegateName.uppercase()) {
                    "GPU" -> {
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
                                Log.i(TAG, "✓ XNNPACK delegate enabled")
                            } catch (e: Exception) {
                                Log.w(TAG, "XNNPACK not available: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            if (activeDelegate == "CPU") {
                Log.i(TAG, "Using CPU fallback (no hardware acceleration)")
            }
        }
        
        val modelBuffer = runCatching { loadModelMapped(context, modelAssetName) }
            .getOrElse { loadModelDirect(context, modelAssetName) }
        
        val interp = Interpreter(modelBuffer, opts)
        
        // Inspect input tensor for quantization
        val inputTensor = interp.getInputTensor(0)
        val inputDataType = inputTensor.dataType()
        val inputShape = inputTensor.shape()
        
        isInputQuantized = when (inputDataType) {
            DataType.UINT8, DataType.INT8 -> {
                // Get quantization parameters
                inputScale = inputTensor.quantizationParams().scale
                inputZeroPoint = inputTensor.quantizationParams().zeroPoint
                Log.i(TAG, "Quantized input detected: type=${inputDataType}, scale=${inputScale}, zeroPoint=${inputZeroPoint}")
                true
            }
            DataType.FLOAT32 -> {
                Log.i(TAG, "Float32 input detected")
                false
            }
            else -> {
                Log.w(TAG, "Unknown input data type: ${inputDataType}, assuming float32")
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
     * Quantize float value to UINT8 using scale and zero-point
     */
    private fun quantizeFloat(value: Float): Byte {
        val quantized = (value / inputScale).roundToInt() + inputZeroPoint
        return quantized.coerceIn(0, 255).toByte()
    }
    
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
        return if (isInputQuantized) {
            // For quantized models, allocate featureDim bytes (1 byte per feature)
            val buffer = ByteBuffer.allocateDirect(featureDim).order(ByteOrder.nativeOrder())
            for (i in 0 until featureDim) {
                buffer.put(quantizeFloat(features[i]))
            }
            buffer.rewind()
            buffer
        } else {
            // For float models, allocate 4 * featureDim bytes (4 bytes per float)
            val buffer = ByteBuffer.allocateDirect(4 * featureDim).order(ByteOrder.nativeOrder())
            for (i in 0 until featureDim) {
                buffer.putFloat(features[i])
            }
            buffer.rewind()
            buffer
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
        
        val input = createInputBuffer(features)
        
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
                
                interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
                
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
                interpreter.run(input, quantizedOutput)
                
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
                interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
                MultiOutputResult(glossOutput[0], originOutput[0])
            } else {
                interpreter.run(input, glossOutput)
                MultiOutputResult(glossOutput[0], null)
            }
        }
        
        return glossLogits
    }
    
    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
