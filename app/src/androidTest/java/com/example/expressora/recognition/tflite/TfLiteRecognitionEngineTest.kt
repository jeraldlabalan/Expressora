package com.example.expressora.recognition.tflite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expressora.recognition.di.RecognitionProvider
import com.example.expressora.recognition.model.GlossEvent
import com.example.expressora.recognition.model.RecognitionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class TfLiteRecognitionEngineTest {

    @Test
    fun placeholderModelProducesErrorEvent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TfLiteRecognitionEngine(
            context = context,
            modelAsset = "expressora_unified_v2.tflite",
            labelAsset = "labels.json",
            labelMappedAsset = "expressora_labels_mapped.json",
            featureDim = 126
        )

        engine.start()
        engine.onLandmarks(FloatArray(126) { 0f })

        val event = withTimeout(2_000) {
            engine.events.first { it !is GlossEvent.Idle }
        }

        assertTrue(event is GlossEvent.Error || event is GlossEvent.InProgress)
    }

    @Test
    fun testLstmInference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize engine using RecognitionProvider
        val engine = RecognitionProvider.provideEngine(context) as TfLiteRecognitionEngine
        
        // Start the engine
        engine.start()
        
        // Create ByteBuffer for LSTM sequence input
        // Size: 1 batch * 30 frames * 237 features * 4 bytes (float) = 28440 bytes
        val bufferSize = 1 * 30 * 237 * 4 // 28440 bytes
        val buffer = ByteBuffer.allocateDirect(bufferSize)
            .order(ByteOrder.nativeOrder())
        
        // Fill buffer with random float data (for realistic test)
        // Using deterministic seed for reproducible tests
        val random = Random(42)
        while (buffer.hasRemaining()) {
            buffer.putFloat(random.nextFloat() * 2f - 1f) // Range: -1.0 to 1.0
        }
        buffer.rewind() // Reset position to 0
        
        // Verify buffer is ready
        assertEquals(0, buffer.position())
        assertEquals(bufferSize, buffer.capacity())
        assertEquals(bufferSize, buffer.remaining())
        
        // Run inference
        val inferenceStartTime = System.currentTimeMillis()
        try {
            engine.onLandmarksSequence(buffer)
            
            // Wait for result with timeout
            val result = withTimeout(5_000) {
                engine.results.first { it != null }
            }
            
            val inferenceDuration = System.currentTimeMillis() - inferenceStartTime
            
            // Assertions
            assertNotNull("Recognition result should not be null", result)
            assertTrue("Result should be a RecognitionResult", result is RecognitionResult)
            
            val recognitionResult = result as RecognitionResult
            assertNotNull("Gloss label should not be null", recognitionResult.glossLabel)
            assertTrue("Gloss confidence should be between 0 and 1", 
                recognitionResult.glossConf >= 0f && recognitionResult.glossConf <= 1f)
            
            // Log performance metrics
            android.util.Log.i("TfLiteRecognitionEngineTest", 
                "LSTM inference completed in ${inferenceDuration}ms")
            android.util.Log.d("TfLiteRecognitionEngineTest",
                "Result: label='${recognitionResult.glossLabel}', confidence=${recognitionResult.glossConf}")
            
            // Sanity check: inference should complete in reasonable time (< 2 seconds)
            assertTrue("Inference should complete in reasonable time", inferenceDuration < 2000)
            
        } catch (e: Exception) {
            val inferenceDuration = System.currentTimeMillis() - inferenceStartTime
            android.util.Log.e("TfLiteRecognitionEngineTest", 
                "LSTM inference failed after ${inferenceDuration}ms: ${e.message}", e)
            fail("LSTM inference should not throw exception: ${e.message}")
        }
    }
}

