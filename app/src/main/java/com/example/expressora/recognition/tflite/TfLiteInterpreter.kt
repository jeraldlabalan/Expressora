package com.example.expressora.recognition.tflite

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TfLiteInterpreter(
    context: Context,
    modelAssetName: String,
    private val featureDim: Int,
    private val numClasses: Int
) {
    private val interpreter: Interpreter by lazy {
        val opts = Interpreter.Options().apply { setNumThreads(2) }
        val modelBuffer = runCatching { loadModelMapped(context, modelAssetName) }
            .getOrElse { loadModelDirect(context, modelAssetName) }
        Interpreter(modelBuffer, opts)
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

    fun run(features: FloatArray): FloatArray {
        require(features.size == featureDim) { "Expected $featureDim features, got ${features.size}" }
        val input = ByteBuffer.allocateDirect(4 * featureDim).order(ByteOrder.nativeOrder())
        for (i in 0 until featureDim) {
            input.putFloat(features[i])
        }
        input.rewind()
        val output = Array(1) { FloatArray(numClasses) }
        interpreter.run(input, output)
        return output[0]
    }
}