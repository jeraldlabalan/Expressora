package com.example.expressora.recognition.memory

import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import java.lang.ref.WeakReference

/**
 * Monitors memory usage and provides GC hints when memory pressure is high
 */
object MemoryMonitor {
    private const val TAG = "MemoryMonitor"
    private const val MB = 1024 * 1024
    
    private var lastGCHintTime = 0L
    private val GC_HINT_COOLDOWN_MS = 5000L
    
    /**
     * Check memory status and provide GC hints if needed
     */
    fun checkMemoryPressure() {
        if (!PerformanceConfig.ENABLE_GC_HINTS) {
            return
        }
        
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / MB
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val availableMemory = maxMemory - usedMemory
        
        // If available memory is less than 20% of max, suggest GC
        if (availableMemory < maxMemory * 0.2f) {
            val now = System.currentTimeMillis()
            if (now - lastGCHintTime > GC_HINT_COOLDOWN_MS) {
                if (PerformanceConfig.VERBOSE_LOGGING) {
                    Log.w(TAG, "Low memory detected. Used: ${usedMemory}MB / ${maxMemory}MB. Suggesting GC.")
                }
                System.gc()
                lastGCHintTime = now
            }
        }
        
        if (PerformanceConfig.VERBOSE_LOGGING) {
            Log.d(TAG, "Memory: ${usedMemory}MB / ${maxMemory}MB (${availableMemory}MB available)")
        }
    }
    
    /**
     * Get current memory usage statistics
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / MB
        val totalMemory = runtime.totalMemory() / MB
        val freeMemory = runtime.freeMemory() / MB
        val usedMemory = totalMemory - freeMemory
        
        return MemoryStats(
            maxMemoryMB = maxMemory,
            usedMemoryMB = usedMemory,
            availableMemoryMB = maxMemory - usedMemory,
            usagePercent = (usedMemory.toFloat() / maxMemory) * 100f
        )
    }
    
    data class MemoryStats(
        val maxMemoryMB: Long,
        val usedMemoryMB: Long,
        val availableMemoryMB: Long,
        val usagePercent: Float
    )
}

