package com.example.expressora.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field

@RunWith(AndroidJUnit4::class)
class OfflineTranslationTest {

    @Test
    fun testTranslationEngineInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("OfflineTranslationTest", "🧪 [TEST] Starting translation engine initialization test...")
        
        try {
            val initStartTime = System.currentTimeMillis()
            val engine = OfflineTranslationEngine(context)
            val initDuration = System.currentTimeMillis() - initStartTime
            
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Engine initialized in ${initDuration}ms")
            
            // Verify sessions are initialized using reflection
            val encoderSessionField: Field = engine.javaClass.getDeclaredField("encoderSession")
            encoderSessionField.isAccessible = true
            val encoderSession = encoderSessionField.get(engine)
            assertNotNull("Encoder session should be initialized", encoderSession)
            
            val decoderSessionField: Field = engine.javaClass.getDeclaredField("decoderSession")
            decoderSessionField.isAccessible = true
            val decoderSession = decoderSessionField.get(engine)
            assertNotNull("Decoder session should be initialized", decoderSession)
            
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Both encoder and decoder sessions are initialized")
            
        } catch (e: Exception) {
            android.util.Log.e("OfflineTranslationTest", "❌ [TEST] Initialization failed: ${e.message}", e)
            fail("Translation engine initialization should not throw exception: ${e.message}")
        }
    }

    @Test
    fun testTranslationLogic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("OfflineTranslationTest", "🧪 [TEST] Starting translation logic test...")
        
        try {
            val initStartTime = System.currentTimeMillis()
            val engine = OfflineTranslationEngine(context)
            val initDuration = System.currentTimeMillis() - initStartTime
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Engine initialized in ${initDuration}ms")
            
            // Test translation
            val input = listOf("MEET", "GO")
            android.util.Log.d("OfflineTranslationTest", "📝 [TEST] Input glosses: ${input.joinToString(", ")}")
            
            val translateStartTime = System.currentTimeMillis()
            val result = engine.translate(input, "en")
            val translateDuration = System.currentTimeMillis() - translateStartTime
            
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Translation completed in ${translateDuration}ms")
            android.util.Log.d("OfflineTranslationTest", "📝 [TEST] Translation result: '$result'")
            
            // Assertions
            assertNotNull("Translation result should not be null", result)
            assertTrue("Translation result should not be empty", result.isNotEmpty())
            assertTrue("Translation result should have reasonable length (> 5)", result.length > 5)
            
            // Check that result is not an error message
            assertFalse("Translation result should not be an error message", 
                result.startsWith("Translation error:", ignoreCase = true))
            assertFalse("Translation result should not be an error message", 
                result.startsWith("Error:", ignoreCase = true))
            
            // Optional: Check if result contains expected words (case insensitive)
            val resultLower = result.lowercase()
            val containsMeet = resultLower.contains("meet") || resultLower.contains("meeting")
            val containsGo = resultLower.contains("go") || resultLower.contains("going")
            
            android.util.Log.d("OfflineTranslationTest", 
                "📊 [TEST] Result analysis: contains 'meet'=$containsMeet, contains 'go'=$containsGo")
            
            // Note: We don't assert exact match as translation may vary
            // Just verify it's a valid translation
            
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Translation logic test passed")
            
        } catch (e: Exception) {
            android.util.Log.e("OfflineTranslationTest", "❌ [TEST] Translation failed: ${e.message}", e)
            fail("Translation should not throw exception: ${e.message}")
        }
    }

    @Test
    fun testTranslationToFilipino() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("OfflineTranslationTest", "🧪 [TEST] Starting Filipino translation test...")
        
        try {
            val engine = OfflineTranslationEngine(context)
            
            // Test translation to Filipino
            val input = listOf("MEET", "GO")
            android.util.Log.d("OfflineTranslationTest", "📝 [TEST] Input glosses: ${input.joinToString(", ")}")
            android.util.Log.d("OfflineTranslationTest", "🌐 [TEST] Target language: fil")
            
            val translateStartTime = System.currentTimeMillis()
            val result = engine.translate(input, "fil")
            val translateDuration = System.currentTimeMillis() - translateStartTime
            
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Filipino translation completed in ${translateDuration}ms")
            android.util.Log.d("OfflineTranslationTest", "📝 [TEST] Translation result: '$result'")
            
            // Assertions
            assertNotNull("Filipino translation result should not be null", result)
            assertTrue("Filipino translation result should not be empty", result.isNotEmpty())
            assertFalse("Filipino translation result should not be an error message", 
                result.startsWith("Translation error:", ignoreCase = true))
            
            android.util.Log.i("OfflineTranslationTest", "✅ [TEST] Filipino translation test passed")
            
        } catch (e: Exception) {
            android.util.Log.e("OfflineTranslationTest", "❌ [TEST] Filipino translation failed: ${e.message}", e)
            fail("Filipino translation should not throw exception: ${e.message}")
        }
    }
}

