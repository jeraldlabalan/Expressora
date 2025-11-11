package com.example.expressora.recognition.memory

import android.graphics.Bitmap
import android.util.Log
import com.example.expressora.recognition.config.PerformanceConfig
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Simple bitmap pool to reduce GC pressure by reusing Bitmap objects.
 * This significantly improves performance by avoiding frequent allocations/deallocations.
 */
object BitmapPool {
    private const val TAG = "BitmapPool"
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private var createdCount = 0
    private var reusedCount = 0
    
    /**
     * Get a bitmap from the pool or create a new one
     */
    fun obtain(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        if (!PerformanceConfig.ENABLE_BITMAP_POOLING) {
            return Bitmap.createBitmap(width, height, config)
        }
        
        // Try to find a suitable bitmap in the pool
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val bitmap = iterator.next()
            if (bitmap.width == width && 
                bitmap.height == height && 
                bitmap.config == config &&
                !bitmap.isRecycled) {
                iterator.remove()
                bitmap.eraseColor(0) // Clear the bitmap
                reusedCount++
                if (PerformanceConfig.VERBOSE_LOGGING && reusedCount % 100 == 0) {
                    Log.d(TAG, "Reused: $reusedCount, Created: $createdCount, Pool size: ${pool.size}")
                }
                return bitmap
            }
        }
        
        // No suitable bitmap found, create new one
        createdCount++
        return Bitmap.createBitmap(width, height, config)
    }
    
    /**
     * Return a bitmap to the pool for reuse
     */
    fun recycle(bitmap: Bitmap?) {
        if (bitmap == null || 
            !PerformanceConfig.ENABLE_BITMAP_POOLING || 
            bitmap.isRecycled) {
            return
        }
        
        // Only keep pool at max size
        if (pool.size < PerformanceConfig.BITMAP_POOL_SIZE) {
            pool.offer(bitmap)
        } else {
            // Pool is full, actually recycle the bitmap
            bitmap.recycle()
        }
    }
    
    /**
     * Clear the pool and recycle all bitmaps
     */
    fun clear() {
        while (pool.isNotEmpty()) {
            pool.poll()?.recycle()
        }
        createdCount = 0
        reusedCount = 0
        
        if (PerformanceConfig.VERBOSE_LOGGING) {
            Log.d(TAG, "Pool cleared")
        }
    }
    
    /**
     * Get pool statistics
     */
    fun getStats(): PoolStats {
        return PoolStats(
            poolSize = pool.size,
            createdCount = createdCount,
            reusedCount = reusedCount,
            reuseRate = if (createdCount + reusedCount > 0) {
                (reusedCount.toFloat() / (createdCount + reusedCount)) * 100f
            } else {
                0f
            }
        )
    }
    
    data class PoolStats(
        val poolSize: Int,
        val createdCount: Int,
        val reusedCount: Int,
        val reuseRate: Float
    )
}

