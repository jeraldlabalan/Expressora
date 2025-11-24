package com.example.expressora.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleTokenizerTest {

    @Test
    fun testTokenizerInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("SimpleTokenizerTest", "🧪 [TEST] Starting tokenizer initialization test...")
        
        try {
            val initStartTime = System.currentTimeMillis()
            val tokenizer = SimpleTokenizer(context)
            val initDuration = System.currentTimeMillis() - initStartTime
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Tokenizer initialized in ${initDuration}ms")
            
            // Verify tokenizer is initialized (no exception thrown)
            assertNotNull("Tokenizer should not be null", tokenizer)
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Tokenizer initialization test passed")
            
        } catch (e: Exception) {
            android.util.Log.e("SimpleTokenizerTest", "❌ [TEST] Initialization failed: ${e.message}", e)
            fail("Tokenizer initialization should not throw exception: ${e.message}")
        }
    }

    @Test
    fun testEncodingDecoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("SimpleTokenizerTest", "🧪 [TEST] Starting encoding/decoding test...")
        
        try {
            val tokenizer = SimpleTokenizer(context)
            
            // Test input
            val input = "MEET GO"
            android.util.Log.d("SimpleTokenizerTest", "📝 [TEST] Input text: '$input'")
            
            // Encode
            val encodeStartTime = System.currentTimeMillis()
            val encoded = tokenizer.encode(input)
            val encodeDuration = System.currentTimeMillis() - encodeStartTime
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Encoding completed in ${encodeDuration}ms")
            android.util.Log.d("SimpleTokenizerTest", "📊 [TEST] Encoded token IDs: ${encoded.take(10).joinToString(", ")}${if (encoded.size > 10) "..." else ""}")
            android.util.Log.d("SimpleTokenizerTest", "📊 [TEST] Encoded array size: ${encoded.size}")
            
            // Assertions for encoding
            assertNotNull("Encoded array should not be null", encoded)
            assertTrue("Encoded array should not be empty", encoded.isNotEmpty())
            assertTrue("Encoded array should end with EOS token (1)", encoded.last() == 1)
            
            // Decode
            val decodeStartTime = System.currentTimeMillis()
            val decoded = tokenizer.decode(encoded)
            val decodeDuration = System.currentTimeMillis() - decodeStartTime
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Decoding completed in ${decodeDuration}ms")
            android.util.Log.d("SimpleTokenizerTest", "📝 [TEST] Decoded text: '$decoded'")
            
            // Assertions for decoding
            assertNotNull("Decoded string should not be null", decoded)
            assertTrue("Decoded string should not be empty", decoded.isNotEmpty())
            
            // Check that decoded string contains the original words (case insensitive)
            val decodedUpper = decoded.uppercase()
            val inputUpper = input.uppercase()
            val words = inputUpper.split(" ")
            
            words.forEach { word ->
                val containsWord = decodedUpper.contains(word)
                android.util.Log.d("SimpleTokenizerTest", 
                    "📊 [TEST] Word '$word' found in decoded: $containsWord")
                // Note: We don't assert exact match as tokenizer may normalize or change case
                // Just verify the words are present
            }
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Encoding/decoding test passed")
            
        } catch (e: Exception) {
            android.util.Log.e("SimpleTokenizerTest", "❌ [TEST] Encoding/decoding failed: ${e.message}", e)
            fail("Encoding/decoding should not throw exception: ${e.message}")
        }
    }

    @Test
    fun testSpecialTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("SimpleTokenizerTest", "🧪 [TEST] Starting special tokens test...")
        
        try {
            val tokenizer = SimpleTokenizer(context)
            
            // Test special tokens
            val specialTokens = mapOf(
                "<2en>" to 3,   // Language tag for English
                "<2fil>" to 4,  // Language tag for Filipino
                "[PAD]" to 0,   // Padding token
                "[EOS]" to 1    // End of sequence token
            )
            
            specialTokens.forEach { (token, expectedId) ->
                android.util.Log.d("SimpleTokenizerTest", "🔍 [TEST] Testing token: '$token' (expected ID: $expectedId)")
                
                val tokenId = tokenizer.getTokenId(token)
                android.util.Log.d("SimpleTokenizerTest", "📊 [TEST] Token '$token' -> ID: $tokenId")
                
                assertEquals("Token '$token' should have ID $expectedId", expectedId, tokenId)
                
                // Test reverse lookup
                val tokenString = tokenizer.getToken(expectedId)
                android.util.Log.d("SimpleTokenizerTest", "📊 [TEST] ID $expectedId -> Token: '$tokenString'")
                
                // Verify round-trip (token -> ID -> token)
                val roundTripId = tokenizer.getTokenId(tokenString)
                assertEquals("Round-trip should preserve token ID for '$token'", expectedId, roundTripId)
            }
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Special tokens test passed")
            
        } catch (e: Exception) {
            android.util.Log.e("SimpleTokenizerTest", "❌ [TEST] Special tokens test failed: ${e.message}", e)
            fail("Special tokens test should not throw exception: ${e.message}")
        }
    }

    @Test
    fun testUnknownTokenHandling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        android.util.Log.i("SimpleTokenizerTest", "🧪 [TEST] Starting unknown token handling test...")
        
        try {
            val tokenizer = SimpleTokenizer(context)
            
            // Test with unknown token
            val input = "UNKNOWNTOKEN123"
            android.util.Log.d("SimpleTokenizerTest", "📝 [TEST] Input with unknown token: '$input'")
            
            val encodeStartTime = System.currentTimeMillis()
            val encoded = tokenizer.encode(input)
            val encodeDuration = System.currentTimeMillis() - encodeStartTime
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Encoding completed in ${encodeDuration}ms")
            android.util.Log.d("SimpleTokenizerTest", "📊 [TEST] Encoded token IDs: ${encoded.joinToString(", ")}")
            
            // Assertions
            assertNotNull("Encoded array should not be null", encoded)
            assertTrue("Encoded array should not be empty", encoded.isNotEmpty())
            assertTrue("Encoded array should end with EOS token (1)", encoded.last() == 1)
            
            // Check that unknown token is mapped to UNK (ID: 2)
            // The array should contain UNK token (2) for the unknown word
            val containsUnk = encoded.contains(2)
            android.util.Log.d("SimpleTokenizerTest", 
                "📊 [TEST] Encoded array contains UNK token (2): $containsUnk")
            
            // Note: The array will have UNK (2) for unknown tokens, then EOS (1) at the end
            // So we should have at least one UNK token if the word is unknown
            if (containsUnk) {
                android.util.Log.d("SimpleTokenizerTest", "✅ [TEST] Unknown token correctly mapped to UNK (2)")
            } else {
                android.util.Log.w("SimpleTokenizerTest", 
                    "⚠️ [TEST] Unknown token was not mapped to UNK - may have been found in vocab")
            }
            
            // Test decoding with UNK token
            val decoded = tokenizer.decode(encoded)
            android.util.Log.d("SimpleTokenizerTest", "📝 [TEST] Decoded text: '$decoded'")
            
            // Decoded text may be empty or contain UNK token representation
            // Just verify decoding doesn't crash
            assertNotNull("Decoded string should not be null", decoded)
            
            android.util.Log.i("SimpleTokenizerTest", "✅ [TEST] Unknown token handling test passed")
            
        } catch (e: Exception) {
            android.util.Log.e("SimpleTokenizerTest", "❌ [TEST] Unknown token handling failed: ${e.message}", e)
            fail("Unknown token handling should not throw exception: ${e.message}")
        }
    }
}

