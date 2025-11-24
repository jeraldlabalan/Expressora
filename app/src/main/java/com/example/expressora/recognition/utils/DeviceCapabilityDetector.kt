package com.example.expressora.recognition.utils

import android.content.Context
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.utils.LogUtils
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * Detects device capabilities for optimal model and delegate selection.
 * Checks GPU availability, NNAPI support, and recommends the best model variant.
 */
object DeviceCapabilityDetector {
    private const val TAG = "DeviceCapabilityDetector"
    
    private var gpuAvailable: Boolean? = null
    private var nnapiAvailable: Boolean? = null
    private var capabilitiesChecked = false
    
    /**
     * Device capabilities detected after check.
     */
    data class DeviceCapabilities(
        val hasGpu: Boolean,
        val hasNnapi: Boolean,
        val recommendedModelType: ModelType,
        val recommendedDelegate: DelegateType
    )
    
    enum class ModelType {
        FP32    // Only FP32 model is used now
    }
    
    enum class DelegateType {
        GPU,    // Fastest on supported devices
        NNAPI,  // Good on devices with dedicated AI chips
        XNNPACK,// Optimized CPU execution
        CPU     // Fallback
    }
    
    /**
     * Check device capabilities synchronously.
     * Results are cached for subsequent calls.
     */
    fun checkCapabilities(context: Context): DeviceCapabilities {
        if (capabilitiesChecked && gpuAvailable != null && nnapiAvailable != null) {
            return getCachedCapabilities()
        }
        
        // Check GPU availability
        val hasGpu = checkGpuAvailability()
        
        // Check NNAPI availability
        val hasNnapi = checkNnapiAvailability()
        
        // Only FP32 model is used now
        val recommendedModel = ModelType.FP32
        
        // Determine recommended delegate
        val recommendedDelegate = when {
            hasGpu && PerformanceConfig.USE_GPU -> DelegateType.GPU
            hasNnapi && PerformanceConfig.USE_NNAPI -> DelegateType.NNAPI
            PerformanceConfig.USE_XNNPACK -> DelegateType.XNNPACK
            else -> DelegateType.CPU
        }
        
        gpuAvailable = hasGpu
        nnapiAvailable = hasNnapi
        capabilitiesChecked = true
        
        val capabilities = DeviceCapabilities(
            hasGpu = hasGpu,
            hasNnapi = hasNnapi,
            recommendedModelType = recommendedModel,
            recommendedDelegate = recommendedDelegate
        )
        
        LogUtils.d(TAG) {
            "Device capabilities: GPU=$hasGpu, NNAPI=$hasNnapi, " +
            "Recommended: ${recommendedModel.name} model with ${recommendedDelegate.name} delegate"
        }
        
        return capabilities
    }
    
    /**
     * Get cached capabilities without re-checking.
     */
    private fun getCachedCapabilities(): DeviceCapabilities {
        val hasGpu = gpuAvailable ?: false
        val hasNnapi = nnapiAvailable ?: false
        
        // Only FP32 model is used now
        val recommendedModel = ModelType.FP32
        
        val recommendedDelegate = when {
            hasGpu && PerformanceConfig.USE_GPU -> DelegateType.GPU
            hasNnapi && PerformanceConfig.USE_NNAPI -> DelegateType.NNAPI
            PerformanceConfig.USE_XNNPACK -> DelegateType.XNNPACK
            else -> DelegateType.CPU
        }
        
        return DeviceCapabilities(
            hasGpu = hasGpu,
            hasNnapi = hasNnapi,
            recommendedModelType = recommendedModel,
            recommendedDelegate = recommendedDelegate
        )
    }
    
    /**
     * Check if GPU delegate is available on this device.
     */
    private fun checkGpuAvailability(): Boolean {
        return try {
            val compatList = CompatibilityList()
            val supported = compatList.isDelegateSupportedOnThisDevice
            
            if (supported) {
                // Try to create a delegate to verify it actually works
                try {
                    val testDelegate = GpuDelegate()
                    testDelegate.close()
                    true
                } catch (e: Exception) {
                    LogUtils.w(TAG) { "GPU delegate creation failed: ${e.message}" }
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            LogUtils.w(TAG) { "GPU availability check failed: ${e.message}" }
            false
        }
    }
    
    /**
     * Check if NNAPI is available on this device.
     * Note: NNAPI availability is checked at interpreter creation time,
     * so we use a heuristic based on Android version and known support.
     */
    private fun checkNnapiAvailability(): Boolean {
        return try {
            // NNAPI is generally available on Android 8.1+ (API 27+)
            // But actual support depends on device hardware
            // We'll let the interpreter handle the actual check
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1
        } catch (e: Exception) {
            LogUtils.w(TAG) { "NNAPI availability check failed: ${e.message}" }
            false
        }
    }
    
    /**
     * Reset cached capabilities (useful for testing or re-checking).
     */
    fun reset() {
        gpuAvailable = null
        nnapiAvailable = null
        capabilitiesChecked = false
    }
    
    /**
     * Get recommended model filename based on model type.
     */
    fun getModelFilename(modelType: ModelType): String {
        // Only FP32 model is used now
        return "recognition/expressora_unified_v3.tflite"
    }
}

