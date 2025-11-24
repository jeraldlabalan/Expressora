package com.example.expressora.recognition.utils

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NpyFileReader {
    private const val TAG = "NpyFileReader"
    private const val MAGIC_NUMBER = "\u0093NUMPY"

    fun loadFloatArray(context: Context, assetPath: String, expectedSize: Int): FloatArray? {
        return try {
            context.assets.open(assetPath).use { inputStream ->
                parseNpyFile(inputStream, expectedSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load $assetPath", e)
            null
        }
    }

    private fun parseNpyFile(inputStream: InputStream, expectedSize: Int): FloatArray? {
        try {
            // 1. Magic Number
            val magic = ByteArray(6).apply { inputStream.read(this) }
            if (String(magic, Charsets.ISO_8859_1) != MAGIC_NUMBER) return null

            // 2. Version
            val majorVersion = inputStream.read()
            inputStream.read() // minor version

            // 3. Header Length
            val headerLenBytes = if (majorVersion == 1) ByteArray(2) else ByteArray(4)
            inputStream.read(headerLenBytes)
            val headerLen = ByteBuffer.wrap(headerLenBytes).order(ByteOrder.LITTLE_ENDIAN).let {
                if (majorVersion == 1) it.short.toInt() else it.int
            }

            // 4. Header
            val headerBytes = ByteArray(headerLen).apply { inputStream.read(this) }
            val header = String(headerBytes, Charsets.ISO_8859_1)
            
            // 5. Detect Format (The Fix!)
            // Check for Float64 (<f8) or Float32 (<f4)
            val isFloat64 = header.contains("<f8") || header.contains("'f8'")
            val isFloat32 = header.contains("<f4") || header.contains("'f4'")

            if (!isFloat64 && !isFloat32) {
                Log.e(TAG, "❌ Unsupported dtype. Header: $header")
                return null
            }

            // 6. Calculate Size
            val bytesPerElement = if (isFloat64) 8 else 4
            val totalBytes = expectedSize * bytesPerElement
            
            // 7. Read Data
            val dataBytes = ByteArray(totalBytes)
            var bytesRead = 0
            while (bytesRead < totalBytes) {
                val count = inputStream.read(dataBytes, bytesRead, totalBytes - bytesRead)
                if (count == -1) break
                bytesRead += count
            }

            // 8. Convert to FloatArray
            val buffer = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            val result = FloatArray(expectedSize)

            for (i in 0 until expectedSize) {
                // AUTOMATIC CONVERSION: Double -> Float
                if (isFloat64) {
                    result[i] = buffer.double.toFloat()
                } else {
                    result[i] = buffer.float
                }
            }
            
            Log.i(TAG, "✅ Loaded ${if(isFloat64) "Float64 (Converted)" else "Float32"} file. Size: ${result.size}")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NPY", e)
            return null
        }
    }
}
