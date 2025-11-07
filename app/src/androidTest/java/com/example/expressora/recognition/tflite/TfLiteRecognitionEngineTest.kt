package com.example.expressora.recognition.tflite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expressora.recognition.model.GlossEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TfLiteRecognitionEngineTest {

    @Test
    fun placeholderModelProducesErrorEvent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TfLiteRecognitionEngine(
            context = context,
            modelAsset = "expressora_unified.tflite",
            labelAsset = "expressora_labels.json",
            featureDim = 126
        )

        engine.start()
        engine.onLandmarks(FloatArray(126) { 0f })

        val event = withTimeout(2_000) {
            engine.events.first { it !is GlossEvent.Idle }
        }

        assertTrue(event is GlossEvent.Error || event is GlossEvent.InProgress)
    }
}

