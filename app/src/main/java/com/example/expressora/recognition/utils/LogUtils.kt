package com.example.expressora.recognition.utils

import android.util.Log
import com.example.expressora.BuildConfig

/**
 * Utility functions for conditional logging based on BuildConfig.DEBUG.
 * Reduces verbose logging in release builds while keeping critical error logs.
 */
object LogUtils {
    
    /**
     * Log debug messages only in debug builds
     */
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }
    
    /**
     * Log verbose messages only in debug builds
     */
    inline fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, message())
        }
    }
    
    /**
     * Log info messages (always logged, but can be filtered)
     */
    inline fun i(tag: String, message: () -> String) {
        Log.i(tag, message())
    }
    
    /**
     * Log warning messages (always logged)
     */
    inline fun w(tag: String, message: () -> String) {
        Log.w(tag, message())
    }
    
    /**
     * Log warning messages with throwable (always logged)
     */
    fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }
    
    /**
     * Log error messages (always logged)
     */
    inline fun e(tag: String, message: () -> String) {
        Log.e(tag, message())
    }
    
    /**
     * Log error messages with throwable (always logged)
     */
    fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
    
    /**
     * Log debug messages conditionally based on PerformanceConfig.VERBOSE_LOGGING
     */
    inline fun debugIfVerbose(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG && com.example.expressora.recognition.config.PerformanceConfig.VERBOSE_LOGGING) {
            Log.d(tag, message())
        }
    }
    
    /**
     * Log verbose messages conditionally based on PerformanceConfig.VERBOSE_LOGGING
     */
    inline fun verboseIfVerbose(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG && com.example.expressora.recognition.config.PerformanceConfig.VERBOSE_LOGGING) {
            Log.v(tag, message())
        }
    }
}

