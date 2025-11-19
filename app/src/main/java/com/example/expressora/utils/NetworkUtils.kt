package com.example.expressora.utils

import android.os.Build
import com.example.expressora.BuildConfig

/**
 * Network utility functions for detecting emulator and getting gRPC server host.
 * Uses BuildConfig.HOST_IP which is automatically injected at build time by Gradle.
 */
object NetworkUtils {
    /**
     * Detects if the app is running on an Android emulator.
     * 
     * @return true if running on emulator, false if on physical device
     */
    fun isEmulator(): Boolean {
        return (Build.MANUFACTURER.contains("Genymotion") ||
                Build.MODEL.contains("sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu"))
    }
    
    /**
     * Gets the gRPC server host address.
     * 
     * - Returns "10.0.2.2" (emulator magic IP) if running on emulator
     * - Returns BuildConfig.HOST_IP (auto-detected at build time) if on physical device
     * 
     * @return The gRPC server host IP address
     */
    fun getGrpcHost(): String {
        return if (isEmulator()) {
            "10.0.2.2" // Emulator Loopback
        } else {
            BuildConfig.HOST_IP // Dynamically injected from Gradle
        }
    }
}

