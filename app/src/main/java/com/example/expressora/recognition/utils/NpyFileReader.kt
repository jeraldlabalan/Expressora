package com.example.expressora.recognition.utils

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility to read NumPy .npy binary files and extract float arrays.
 * Supports .npy format version 1.0 and 2.0+ with float32 dtype.
 */
object NpyFileReader {
    private const val TAG = "NpyFileReader"
    private const val MAGIC_NUMBER = "\u0093NUMPY" // \x93NUMPY
    
    /**
     * Load a .npy file from assets and extract float array.
     * 
     * @param context Android context
     * @param assetPath Path to .npy file in assets (e.g., "feature_mean.npy")
     * @param expectedSize Expected array size (e.g., 126 for feature vectors)
     * @return FloatArray extracted from .npy file, or null if error
     */
    fun loadFloatArray(context: Context, assetPath: String, expectedSize: Int): FloatArray? {
        Log.d(TAG, "📂 Loading .npy file from assets: $assetPath (expected size: $expectedSize)")
        return try {
            val result = context.assets.open(assetPath).use { inputStream ->
                parseNpyFile(inputStream, expectedSize)
            }
            if (result != null) {
                Log.i(TAG, "✅ Successfully loaded $assetPath: ${result.size} elements")
                // Log first few values for verification
                val sampleSize = minOf(5, result.size)
                Log.d(TAG, "📊 Sample values [0-${sampleSize - 1}]: [${result.slice(0 until sampleSize).joinToString()}]")
                if (result.size > sampleSize) {
                    val midStart = result.size / 2
                    val midEnd = minOf(midStart + 3, result.size)
                    Log.d(TAG, "📊 Sample values [${midStart}-${midEnd - 1}]: [${result.slice(midStart until midEnd).joinToString()}]")
                }
            } else {
                Log.e(TAG, "❌ Failed to parse $assetPath")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load .npy file from assets: $assetPath", e)
            null
        }
    }
    
    /**
     * Parse .npy file format and extract float array.
     * 
     * Format:
     * - Magic number: \x93NUMPY (6 bytes)
     * - Version: 1 byte (0x01 for v1.0, 0x02 for v2.0+)
     * - Header length: 2 bytes (v1.0) or 4 bytes (v2.0+)
     * - Header: Python dict string with shape, dtype, etc.
     * - Data: Binary float32 data
     */
    private fun parseNpyFile(inputStream: InputStream, expectedSize: Int): FloatArray? {
        try {
            Log.d(TAG, "🔍 Parsing .npy file format (expected size: $expectedSize)")
            
            // Read magic number (6 bytes)
            val magic = ByteArray(6)
            val magicBytesRead = inputStream.read(magic)
            if (magicBytesRead != 6) {
                Log.e(TAG, "❌ Failed to read magic number: read $magicBytesRead bytes, expected 6")
                return null
            }
            
            val magicString = String(magic, Charsets.ISO_8859_1)
            Log.d(TAG, "🔍 Magic number: '$magicString' (hex: ${magic.joinToString(" ") { "%02x".format(it) }})")
            if (magicString != MAGIC_NUMBER) {
                Log.e(TAG, "❌ Invalid magic number: expected '$MAGIC_NUMBER', got '$magicString'")
                return null
            }
            Log.d(TAG, "✅ Magic number verified")
            
            // Read version (1 byte)
            val version = inputStream.read()
            if (version == -1) {
                Log.e(TAG, "❌ Failed to read version byte")
                return null
            }
            Log.d(TAG, "🔍 NPY version: ${if (version == 1) "1.0" else "2.0+"} (byte: 0x${"%02x".format(version)})")
            
            // Read header length
            val headerLength = if (version == 1) {
                // Version 1.0: 2 bytes little-endian
                val lengthBytes = ByteArray(2)
                if (inputStream.read(lengthBytes) != 2) {
                    Log.e(TAG, "❌ Failed to read header length (v1.0)")
                    return null
                }
                val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                Log.d(TAG, "🔍 Header length (v1.0): $length bytes")
                length
            } else {
                // Version 2.0+: 4 bytes little-endian
                val lengthBytes = ByteArray(4)
                if (inputStream.read(lengthBytes) != 4) {
                    Log.e(TAG, "❌ Failed to read header length (v2.0+)")
                    return null
                }
                val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
                Log.d(TAG, "🔍 Header length (v2.0+): $length bytes")
                length
            }
            
            // Read header (Python dict string)
            val headerBytes = ByteArray(headerLength)
            val headerBytesRead = inputStream.read(headerBytes)
            if (headerBytesRead != headerLength) {
                Log.e(TAG, "❌ Failed to read header: read $headerBytesRead bytes, expected $headerLength")
                return null
            }
            
            val header = String(headerBytes, Charsets.ISO_8859_1)
            Log.d(TAG, "📋 NPY header (${header.length} chars): $header")
            
            // Parse header to extract shape and dtype
            Log.d(TAG, "🔍 Parsing header to extract shape and dtype...")
            val shape = parseShape(header)
            val dtype = parseDtype(header)
            
            if (shape == null) {
                Log.e(TAG, "❌ Failed to parse shape from header: $header")
                return null
            }
            if (dtype == null) {
                Log.e(TAG, "❌ Failed to parse dtype from header: $header")
                return null
            }
            
            Log.d(TAG, "✅ Parsed shape: $shape, dtype: $dtype")
            
            // Verify dtype is float32
            if (dtype != "<f4" && dtype != "f4" && dtype != "float32") {
                Log.e(TAG, "❌ Unsupported dtype: $dtype (expected float32, <f4, or f4)")
                return null
            }
            Log.d(TAG, "✅ Dtype verified: $dtype (float32)")
            
            // Calculate expected data size
            val totalElements = shape.fold(1) { acc, dim -> acc * dim }
            val dataSizeBytes = totalElements * 4 // 4 bytes per float32
            Log.d(TAG, "📊 Calculated data size: $totalElements elements = $dataSizeBytes bytes (4 bytes per float32)")
            
            // Verify expected size matches
            if (totalElements != expectedSize) {
                Log.w(TAG, "⚠️ Shape mismatch: expected $expectedSize elements, got $totalElements from shape $shape")
                // Continue anyway - might be (1, 126) which we'll flatten
            } else {
                Log.d(TAG, "✅ Element count matches expected size: $totalElements")
            }
            
            // Read data
            Log.d(TAG, "📖 Reading $dataSizeBytes bytes of data...")
            val dataBytes = ByteArray(dataSizeBytes)
            var bytesRead = 0
            while (bytesRead < dataSizeBytes) {
                val read = inputStream.read(dataBytes, bytesRead, dataSizeBytes - bytesRead)
                if (read == -1) {
                    Log.e(TAG, "❌ Unexpected end of file while reading data: read $bytesRead/$dataSizeBytes bytes")
                    return null
                }
                bytesRead += read
            }
            Log.d(TAG, "✅ Read all $bytesRead bytes of data")
            
            // Convert bytes to float array
            Log.d(TAG, "🔄 Converting $totalElements bytes to float array...")
            val buffer = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            val result = FloatArray(totalElements)
            var minValue = Float.MAX_VALUE
            var maxValue = Float.MIN_VALUE
            var sum = 0.0
            for (i in 0 until totalElements) {
                result[i] = buffer.float
                if (result[i] < minValue) minValue = result[i]
                if (result[i] > maxValue) maxValue = result[i]
                sum += result[i]
            }
            val meanValue = sum / totalElements
            Log.d(TAG, "✅ Converted to float array: min=$minValue, max=$maxValue, mean=$meanValue")
            
            // If shape is (1, 126), flatten to (126,)
            val finalResult = if (shape.size == 2 && shape[0] == 1 && shape[1] == expectedSize) {
                Log.d(TAG, "✅ Shape is (1, $expectedSize), using array as-is")
                result // Already correct
            } else if (totalElements == expectedSize) {
                Log.d(TAG, "✅ Flattening shape $shape to size $expectedSize")
                result // Flatten if needed
            } else {
                Log.w(TAG, "⚠️ Shape $shape doesn't match expected size $expectedSize, returning full array of $totalElements elements")
                result
            }
            
            Log.i(TAG, "✅ Successfully loaded .npy file: shape=$shape, dtype=$dtype, elements=${finalResult.size}, " +
                    "valueRange=[$minValue, $maxValue], mean=$meanValue")
            return finalResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing .npy file", e)
            return null
        }
    }
    
    /**
     * Parse shape tuple from header string.
     * Example: "{'descr': '<f4', 'fortran_order': False, 'shape': (1, 126), }"
     */
    private fun parseShape(header: String): List<Int>? {
        val shapePattern = Regex("'shape':\\s*\\(([^)]+)\\)")
        val match = shapePattern.find(header)
        if (match == null) {
            Log.e(TAG, "❌ Shape pattern not found in header")
            return null
        }
        
        val shapeStr = match.groupValues[1]
        Log.d(TAG, "🔍 Extracted shape string: '$shapeStr'")
        return try {
            val parsed = shapeStr.split(',').map { it.trim().toInt() }
            Log.d(TAG, "✅ Parsed shape: $parsed")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse shape: '$shapeStr'", e)
            null
        }
    }
    
    /**
     * Parse dtype from header string.
     * Example: "{'descr': '<f4', 'fortran_order': False, 'shape': (1, 126), }"
     */
    private fun parseDtype(header: String): String? {
        val dtypePattern = Regex("'descr':\\s*'([^']+)'")
        val match = dtypePattern.find(header)
        if (match == null) {
            Log.e(TAG, "❌ Dtype pattern not found in header")
            return null
        }
        val dtype = match.groupValues[1]
        Log.d(TAG, "✅ Extracted dtype: '$dtype'")
        return dtype
    }
}

