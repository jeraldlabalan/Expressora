package com.example.expressora.translation

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.InputStream

/**
 * Simple tokenizer for T5-based translation models.
 * Loads tokenizer.json from assets and provides encode/decode functionality.
 */
class SimpleTokenizer(context: Context, assetPath: String = "translation/tokenizer.json") {
    private val TAG = "SimpleTokenizer"
    
    // Token ID mappings
    private val tokenToId: MutableMap<String, Int> = mutableMapOf()
    private val idToToken: MutableMap<Int, String> = mutableMapOf()
    
    // Special token IDs
    private val PAD_ID = 0
    private val EOS_ID = 1
    private val UNK_ID = 2
    
    // Special token strings
    private val PAD_TOKEN = "[PAD]"
    private val EOS_TOKEN = "[EOS]"
    private val UNK_TOKEN = "[UNK]"
    
    init {
        val initStartTime = System.currentTimeMillis()
        Log.i(TAG, "🔧 [INIT] Starting SimpleTokenizer initialization...")
        Log.d(TAG, "🔧 [INIT] Asset path: $assetPath")
        try {
            loadTokenizer(context, assetPath)
            val initDuration = System.currentTimeMillis() - initStartTime
            Log.i(TAG, "✅ [INIT] Tokenizer loaded successfully: ${tokenToId.size} tokens in ${initDuration}ms")
            Log.d(TAG, "📊 [INIT] Vocab size: ${tokenToId.size}, Reverse vocab size: ${idToToken.size}")
        } catch (e: Exception) {
            val initDuration = System.currentTimeMillis() - initStartTime
            Log.e(TAG, "❌ [INIT] Failed to load tokenizer after ${initDuration}ms: ${e.message}", e)
            Log.e(TAG, "❌ [INIT] Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * Load tokenizer.json from assets and parse vocab.
     */
    private fun loadTokenizer(context: Context, assetPath: String) {
        val loadStartTime = System.currentTimeMillis()
        Log.d(TAG, "📂 [LOAD] Opening asset file: $assetPath")
        
        val inputStream: InputStream = context.assets.open(assetPath)
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        // Note: bufferedReader().use{} automatically closes the stream, no need for explicit close()
        
        val readDuration = System.currentTimeMillis() - loadStartTime
        Log.d(TAG, "📂 [LOAD] Asset file read: ${jsonString.length} characters in ${readDuration}ms")
        
        val parseStartTime = System.currentTimeMillis()
        Log.d(TAG, "🔍 [PARSE] Parsing JSON...")
        val gson = Gson()
        val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
        val parseDuration = System.currentTimeMillis() - parseStartTime
        Log.d(TAG, "🔍 [PARSE] JSON parsed in ${parseDuration}ms")
        
        // Parse vocab from model.vocab array
        Log.d(TAG, "📚 [VOCAB] Extracting vocab from model.vocab...")
        val model = jsonObject.getAsJsonObject("model")
        val vocab = model.getAsJsonArray("vocab")
        Log.d(TAG, "📚 [VOCAB] Vocab array size: ${vocab.size()}")
        
        val vocabStartTime = System.currentTimeMillis()
        // Vocab is an array of [token_string, score] pairs
        // The index in the array is the token ID
        vocab.forEachIndexed { index, element ->
            val tokenArray = element.asJsonArray
            val tokenString = tokenArray[0].asString
            val tokenId = index
            
            tokenToId[tokenString] = tokenId
            idToToken[tokenId] = tokenString
        }
        val vocabDuration = System.currentTimeMillis() - vocabStartTime
        Log.d(TAG, "📚 [VOCAB] Vocab processed: ${vocab.size()} tokens in ${vocabDuration}ms")
        
        Log.d(TAG, "📊 [VOCAB] Special tokens: PAD=$PAD_ID ($PAD_TOKEN), EOS=$EOS_ID ($EOS_TOKEN), UNK=$UNK_ID ($UNK_TOKEN)")
        Log.d(TAG, "📊 [VOCAB] Sample tokens (first 10): ${tokenToId.entries.take(10).joinToString { "${it.key}=${it.value}" }}")
        Log.d(TAG, "📊 [VOCAB] Sample tokens (last 10): ${tokenToId.entries.toList().takeLast(10).joinToString { "${it.key}=${it.value}" }}")
    }
    
    /**
     * Encode text into token IDs.
     * 
     * @param text Input text to encode
     * @return Array of token IDs, ending with EOS token (1)
     */
    fun encode(text: String): IntArray {
        val encodeStartTime = System.currentTimeMillis()
        Log.d(TAG, "🔤 [ENCODE] Starting encoding...")
        Log.d(TAG, "🔤 [ENCODE] Input text: '$text' (length=${text.length})")
        
        // Split by whitespace
        val tokens = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        Log.d(TAG, "🔤 [ENCODE] Split into ${tokens.size} tokens: ${tokens.take(10).joinToString(", ")}${if (tokens.size > 10) "..." else ""}")
        
        val tokenIds = mutableListOf<Int>()
        var unkCount = 0
        
        tokens.forEachIndexed { index, token ->
            val originalTokenId = tokenToId[token]
            val lowerTokenId = tokenToId[token.lowercase()]
            val tokenId = originalTokenId ?: lowerTokenId ?: UNK_ID
            
            tokenIds.add(tokenId)
            
            if (tokenId == UNK_ID) {
                unkCount++
                Log.v(TAG, "⚠️ [ENCODE] Unknown token at index $index: '$token' -> UNK ($UNK_ID)")
            } else {
                Log.v(TAG, "✅ [ENCODE] Token[$index]: '$token' -> ID=$tokenId")
            }
        }
        
        // Append EOS token
        tokenIds.add(EOS_ID)
        Log.d(TAG, "🔤 [ENCODE] Added EOS token (ID=$EOS_ID)")
        
        val encodeDuration = System.currentTimeMillis() - encodeStartTime
        Log.d(TAG, "✅ [ENCODE] Encoding complete: ${tokenIds.size} token IDs in ${encodeDuration}ms")
        Log.d(TAG, "📊 [ENCODE] Token IDs: ${tokenIds.take(20).joinToString(", ")}${if (tokenIds.size > 20) "..." else ""}")
        Log.d(TAG, "📊 [ENCODE] Unknown tokens: $unkCount/${tokens.size} (${if (tokens.isNotEmpty()) (unkCount * 100 / tokens.size) else 0}%)")
        
        return tokenIds.toIntArray()
    }
    
    /**
     * Decode token IDs back to text.
     * 
     * @param ids Array of token IDs to decode
     * @return Decoded text string with special tokens removed
     */
    fun decode(ids: IntArray): String {
        val decodeStartTime = System.currentTimeMillis()
        Log.d(TAG, "🔤 [DECODE] Starting decoding...")
        Log.d(TAG, "🔤 [DECODE] Input: ${ids.size} token IDs")
        Log.d(TAG, "🔤 [DECODE] Token IDs: ${ids.take(20).joinToString(", ")}${if (ids.size > 20) "..." else ""}")
        
        val tokens = mutableListOf<String>()
        var skippedCount = 0
        var eosFound = false
        
        for ((index, id) in ids.withIndex()) {
            val token = idToToken[id] ?: UNK_TOKEN
            
            // Check for EOS token first - stop decoding if found
            if (id == EOS_ID) {
                eosFound = true
                skippedCount++
                Log.d(TAG, "🛑 [DECODE] EOS token found at index $index, stopping decode")
                break
            }
            
            // Skip special tokens (PAD, UNK)
            if (id != PAD_ID && id != UNK_ID) {
                // Also skip other special tokens like <pad>, </s>, <unk>
                if (!token.startsWith("<") || token == "<2en>" || token == "<2fil>") {
                    tokens.add(token)
                    Log.v(TAG, "✅ [DECODE] Token[$index]: ID=$id -> '$token'")
                } else {
                    skippedCount++
                    Log.v(TAG, "⏭️ [DECODE] Skipped special token[$index]: ID=$id -> '$token'")
                }
            } else {
                skippedCount++
                when (id) {
                    PAD_ID -> Log.v(TAG, "⏭️ [DECODE] Skipped PAD token[$index]: ID=$id")
                    UNK_ID -> Log.v(TAG, "⏭️ [DECODE] Skipped UNK token[$index]: ID=$id")
                }
            }
        }
        
        val decodedText = tokens.joinToString(" ").trim()
        val decodeDuration = System.currentTimeMillis() - decodeStartTime
        Log.d(TAG, "✅ [DECODE] Decoding complete: '${decodedText}' in ${decodeDuration}ms")
        Log.d(TAG, "📊 [DECODE] Output: ${tokens.size} tokens, ${skippedCount} skipped, EOS found: $eosFound")
        
        return decodedText
    }
    
    /**
     * Get token ID for a given token string.
     */
    fun getTokenId(token: String): Int {
        return tokenToId[token] ?: tokenToId[token.lowercase()] ?: UNK_ID
    }
    
    /**
     * Get token string for a given token ID.
     */
    fun getToken(id: Int): String {
        return idToToken[id] ?: UNK_TOKEN
    }
}

