package com.example.expressora.translation

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Offline translation engine using ONNX models.
 * Implements encoder-decoder architecture for translating sign language glosses to text.
 */
class OfflineTranslationEngine(
    context: Context,
    encoderPath: String = "translation/encoder_model.onnx",
    decoderPath: String = "translation/decoder_model.onnx"
) {
    private val TAG = "OfflineTranslationEngine"
    
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var encoderSession: OrtSession
    private lateinit var decoderSession: OrtSession
    private val tokenizer: SimpleTokenizer
    
    // Special token IDs
    private val PAD_ID = 0
    private val EOS_ID = 1
    private val UNK_ID = 2
    
    // Language tag IDs
    private val TAG_EN_ID = 3  // <2en>
    private val TAG_FIL_ID = 4 // <2fil>
    
    init {
        val initStartTime = System.currentTimeMillis()
        Log.i(TAG, "🔧 [INIT] ========================================")
        Log.i(TAG, "🔧 [INIT] Starting Offline Translation Engine initialization...")
        Log.d(TAG, "🔧 [INIT] Encoder path: $encoderPath")
        Log.d(TAG, "🔧 [INIT] Decoder path: $decoderPath")
        
        try {
            // Load tokenizer
            val tokenizerStartTime = System.currentTimeMillis()
            Log.d(TAG, "📚 [INIT] Loading tokenizer...")
            tokenizer = SimpleTokenizer(context)
            val tokenizerDuration = System.currentTimeMillis() - tokenizerStartTime
            Log.i(TAG, "✅ [INIT] Tokenizer loaded in ${tokenizerDuration}ms")
            
            // Load encoder model
            val encoderStartTime = System.currentTimeMillis()
            Log.d(TAG, "📦 [INIT] Loading encoder model from assets...")
            val encoderStream = context.assets.open(encoderPath)
            val encoderBytes = encoderStream.readBytes()
            encoderStream.close()
            val encoderLoadDuration = System.currentTimeMillis() - encoderStartTime
            Log.d(TAG, "📦 [INIT] Encoder model file read: ${encoderBytes.size} bytes (${encoderBytes.size / 1024}KB) in ${encoderLoadDuration}ms")
            
            val encoderSessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔨 [INIT] Creating encoder ONNX session...")
            encoderSession = ortEnvironment.createSession(encoderBytes)
            val encoderSessionDuration = System.currentTimeMillis() - encoderSessionStartTime
            Log.i(TAG, "✅ [INIT] Encoder model loaded and session created in ${encoderSessionDuration}ms")
            
            // Load decoder model
            val decoderStartTime = System.currentTimeMillis()
            Log.d(TAG, "📦 [INIT] Loading decoder model from assets...")
            val decoderStream = context.assets.open(decoderPath)
            val decoderBytes = decoderStream.readBytes()
            decoderStream.close()
            val decoderLoadDuration = System.currentTimeMillis() - decoderStartTime
            Log.d(TAG, "📦 [INIT] Decoder model file read: ${decoderBytes.size} bytes (${decoderBytes.size / 1024}KB) in ${decoderLoadDuration}ms")
            
            val decoderSessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔨 [INIT] Creating decoder ONNX session...")
            decoderSession = ortEnvironment.createSession(decoderBytes)
            val decoderSessionDuration = System.currentTimeMillis() - decoderSessionStartTime
            Log.i(TAG, "✅ [INIT] Decoder model loaded and session created in ${decoderSessionDuration}ms")
            
            val totalDuration = System.currentTimeMillis() - initStartTime
            Log.i(TAG, "🎉 [INIT] Offline Translation Engine initialized successfully in ${totalDuration}ms")
            Log.i(TAG, "🔧 [INIT] ========================================")
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - initStartTime
            Log.e(TAG, "❌ [INIT] Failed to initialize Offline Translation Engine after ${totalDuration}ms", e)
            Log.e(TAG, "❌ [INIT] Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "❌ [INIT] Exception message: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * Translate a sequence of glosses to text.
     * 
     * @param glosses List of gloss labels (e.g., ["MEET", "GO"])
     * @param targetLang Target language: "en" or "fil" (default: "en")
     * @return Translated sentence
     */
    fun translate(glosses: List<String>, targetLang: String = "en"): String {
        val translateStartTime = System.currentTimeMillis()
        Log.i(TAG, "🌐 [TRANSLATE] ========================================")
        Log.i(TAG, "🌐 [TRANSLATE] Starting offline translation...")
        Log.d(TAG, "🌐 [TRANSLATE] Input glosses: ${glosses.size} items")
        Log.d(TAG, "🌐 [TRANSLATE] Glosses: ${glosses.joinToString(", ")}")
        Log.d(TAG, "🌐 [TRANSLATE] Target language: $targetLang")
        
        return try {
            // Step 1: Preprocess
            val preprocessStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔤 [STEP1] Preprocessing input...")
            val inputText = glosses.joinToString(" ")
            val languageTag = if (targetLang == "fil") "<2fil>" else "<2en>"
            val taggedInput = "$languageTag $inputText"
            val preprocessDuration = System.currentTimeMillis() - preprocessStartTime
            Log.d(TAG, "✅ [STEP1] Preprocessing complete in ${preprocessDuration}ms")
            Log.d(TAG, "📝 [STEP1] Input text: '$inputText'")
            Log.d(TAG, "📝 [STEP1] Language tag: '$languageTag'")
            Log.d(TAG, "📝 [STEP1] Tagged input: '$taggedInput'")
            
            // Tokenize
            val tokenizeStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔤 [STEP1] Tokenizing input...")
            val inputIds = tokenizer.encode(taggedInput)
            val inputLength = inputIds.size
            val tokenizeDuration = System.currentTimeMillis() - tokenizeStartTime
            Log.d(TAG, "✅ [STEP1] Tokenization complete in ${tokenizeDuration}ms")
            Log.d(TAG, "📊 [STEP1] Token count: $inputLength")
            Log.d(TAG, "📊 [STEP1] Token IDs (first 20): ${inputIds.take(20).joinToString(", ")}${if (inputIds.size > 20) "..." else ""}")
            
            // Create attention mask (all 1s for real tokens)
            val attentionMask = LongArray(inputLength) { 1L }
            Log.d(TAG, "📊 [STEP1] Attention mask: ${attentionMask.size} elements, all set to 1")
            
            // Step 2: Encoder
            val encoderStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔨 [STEP2] Running encoder...")
            val encoderOutputs = runEncoder(inputIds, attentionMask)
            val encoderDuration = System.currentTimeMillis() - encoderStartTime
            Log.d(TAG, "✅ [STEP2] Encoder complete in ${encoderDuration}ms")
            Log.d(TAG, "📊 [STEP2] Encoder output shape: ${encoderOutputs.info.shape.contentToString()}")
            Log.d(TAG, "📊 [STEP2] Encoder output tensor info: ${encoderOutputs.info}")
            
            // Step 3: Decoder loop
            val decoderStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔄 [STEP3] Running decoder loop...")
            val decodedIds = runDecoderLoop(encoderOutputs, attentionMask)
            val decoderDuration = System.currentTimeMillis() - decoderStartTime
            Log.d(TAG, "✅ [STEP3] Decoder loop complete in ${decoderDuration}ms")
            Log.d(TAG, "📊 [STEP3] Generated ${decodedIds.size} tokens")
            Log.d(TAG, "📊 [STEP3] Generated token IDs: ${decodedIds.take(20).joinToString(", ")}${if (decodedIds.size > 20) "..." else ""}")
            
            // Step 4: Decode to text
            val decodeStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔤 [STEP4] Decoding tokens to text...")
            val translatedText = tokenizer.decode(decodedIds.toIntArray())
            val decodeDuration = System.currentTimeMillis() - decodeStartTime
            Log.d(TAG, "✅ [STEP4] Decoding complete in ${decodeDuration}ms")
            
            val totalDuration = System.currentTimeMillis() - translateStartTime
            Log.i(TAG, "🎉 [TRANSLATE] Translation complete in ${totalDuration}ms")
            Log.i(TAG, "📊 [TRANSLATE] Timing breakdown:")
            Log.i(TAG, "   - Preprocessing: ${preprocessDuration}ms")
            Log.i(TAG, "   - Tokenization: ${tokenizeDuration}ms")
            Log.i(TAG, "   - Encoder: ${encoderDuration}ms")
            Log.i(TAG, "   - Decoder: ${decoderDuration}ms")
            Log.i(TAG, "   - Decoding: ${decodeDuration}ms")
            Log.i(TAG, "📝 [TRANSLATE] Final translation: '$translatedText'")
            Log.i(TAG, "🌐 [TRANSLATE] ========================================")
            
            translatedText
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - translateStartTime
            Log.e(TAG, "❌ [TRANSLATE] Translation failed after ${totalDuration}ms", e)
            Log.e(TAG, "❌ [TRANSLATE] Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "❌ [TRANSLATE] Exception message: ${e.message}")
            e.printStackTrace()
            "Translation error: ${e.message}"
        }
    }
    
    /**
     * Run encoder model.
     * 
     * @param inputIds Token IDs
     * @param attentionMask Attention mask
     * @return Encoder hidden states [batch, seq_len, hidden_dim]
     */
    private fun runEncoder(inputIds: IntArray, attentionMask: LongArray): OnnxTensor {
        val encoderStartTime = System.currentTimeMillis()
        Log.d(TAG, "🔨 [ENCODER] Starting encoder inference...")
        Log.d(TAG, "📊 [ENCODER] Input IDs: ${inputIds.size} tokens")
        Log.d(TAG, "📊 [ENCODER] Attention mask: ${attentionMask.size} elements")
        
        try {
            // Prepare inputs
            val prepareStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔧 [ENCODER] Preparing input tensors...")
            
            // Input shape: [1, seq_len]
            val inputShape = longArrayOf(1, inputIds.size.toLong())
            Log.d(TAG, "🔧 [ENCODER] Input shape: ${inputShape.contentToString()}")
            // Convert IntArray to LongArray for ONNX Int64 requirement
            val inputIdsLong = inputIds.map { it.toLong() }.toLongArray()
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                LongBuffer.wrap(inputIdsLong),
                inputShape
            )
            Log.d(TAG, "✅ [ENCODER] Input tensor created")
            
            // Attention mask shape: [1, seq_len]
            val maskShape = longArrayOf(1, attentionMask.size.toLong())
            Log.d(TAG, "🔧 [ENCODER] Mask shape: ${maskShape.contentToString()}")
            val maskTensor = OnnxTensor.createTensor(
                ortEnvironment,
                LongBuffer.wrap(attentionMask),
                maskShape
            )
            Log.d(TAG, "✅ [ENCODER] Mask tensor created")
            val prepareDuration = System.currentTimeMillis() - prepareStartTime
            Log.d(TAG, "✅ [ENCODER] Tensor preparation complete in ${prepareDuration}ms")
            
            // Run encoder
            val inferenceStartTime = System.currentTimeMillis()
            Log.d(TAG, "🚀 [ENCODER] Running encoder inference...")
            val inputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to maskTensor
            )
            
            val outputs = encoderSession.run(inputs)
            val inferenceDuration = System.currentTimeMillis() - inferenceStartTime
            Log.d(TAG, "✅ [ENCODER] Encoder inference complete in ${inferenceDuration}ms")
            Log.d(TAG, "📊 [ENCODER] Output count: ${outputs.count()}")
            
            // Get last_hidden_state (encoder outputs)
            val extractStartTime = System.currentTimeMillis()
            Log.d(TAG, "🔍 [ENCODER] Extracting encoder output...")
            val encoderOutputValue = outputs.get(0)
            val encoderOutputTensor = encoderOutputValue as OnnxTensor
            val encoderData = encoderOutputTensor.floatBuffer
            val encoderShape = encoderOutputTensor.info.shape
            Log.d(TAG, "📊 [ENCODER] Encoder output shape: ${encoderShape.contentToString()}")
            Log.d(TAG, "📊 [ENCODER] Encoder output buffer capacity: ${encoderData.capacity()}")
            
            // Duplicate the buffer and ensure position is at 0 for the new tensor
            val duplicatedBuffer = encoderData.duplicate()
            duplicatedBuffer.rewind() // Reset position to 0
            Log.d(TAG, "✅ [ENCODER] Buffer duplicated and rewound")
            
            // Create a new tensor with the same data for the decoder
            val encoderTensor = OnnxTensor.createTensor(
                ortEnvironment,
                duplicatedBuffer,
                encoderShape
            )
            val extractDuration = System.currentTimeMillis() - extractStartTime
            Log.d(TAG, "✅ [ENCODER] New tensor created for decoder in ${extractDuration}ms")
            
            // Clean up input tensors and outputs
            // Note: Don't close encoderOutputTensor explicitly - outputs.close() will handle it
            Log.d(TAG, "🧹 [ENCODER] Cleaning up tensors...")
            inputTensor.close()
            maskTensor.close()
            outputs.close() // This will close encoderOutputTensor and all other output tensors
            Log.d(TAG, "✅ [ENCODER] Cleanup complete")
            
            val totalDuration = System.currentTimeMillis() - encoderStartTime
            Log.d(TAG, "✅ [ENCODER] Encoder complete in ${totalDuration}ms (prep: ${prepareDuration}ms, inference: ${inferenceDuration}ms, extract: ${extractDuration}ms)")
            
            // Return the new tensor for decoder
            return encoderTensor
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - encoderStartTime
            Log.e(TAG, "❌ [ENCODER] Encoder failed after ${totalDuration}ms: ${e.message}", e)
            Log.e(TAG, "❌ [ENCODER] Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * Run decoder loop to generate tokens.
     * 
     * @param encoderHiddenStates Encoder output [batch, seq_len, hidden_dim]
     * @param encoderAttentionMask Encoder attention mask [seq_len]
     * @return List of generated token IDs
     */
    private fun runDecoderLoop(encoderHiddenStates: OnnxTensor, encoderAttentionMask: LongArray): List<Int> {
        val decoderLoopStartTime = System.currentTimeMillis()
        val maxLength = 30
        val generatedIds = mutableListOf<Int>()
        
        Log.d(TAG, "🔄 [DECODER] Starting decoder loop...")
        Log.d(TAG, "📊 [DECODER] Max length: $maxLength")
        Log.d(TAG, "📊 [DECODER] Encoder hidden states shape: ${encoderHiddenStates.info.shape.contentToString()}")
        
        // Start with PAD token (0)
        var decoderInputIds: LongArray = longArrayOf(PAD_ID.toLong())
        Log.d(TAG, "🔧 [DECODER] Initial decoder input: [${decoderInputIds.joinToString()}]")
        
        // Create encoder attention mask tensor (reused for all decoder steps)
        val encoderMaskShape = longArrayOf(1, encoderAttentionMask.size.toLong())
        val encoderAttentionMaskTensor = OnnxTensor.createTensor(
            ortEnvironment,
            LongBuffer.wrap(encoderAttentionMask),
            encoderMaskShape
        )
        Log.d(TAG, "🔧 [DECODER] Encoder attention mask tensor created with shape ${encoderMaskShape.contentToString()}")
        
        try {
            for (step in 0 until maxLength) {
                val stepStartTime = System.currentTimeMillis()
                Log.d(TAG, "🔄 [DECODER] Step $step/${maxLength - 1}...")
                Log.d(TAG, "📊 [DECODER] Current decoder input (${decoderInputIds.size} tokens): ${decoderInputIds.take(10).joinToString(", ")}${if (decoderInputIds.size > 10) "..." else ""}")
                
                // Prepare decoder inputs
                val prepareStartTime = System.currentTimeMillis()
                val decoderShape = longArrayOf(1, decoderInputIds.size.toLong())
                Log.d(TAG, "🔧 [DECODER] Step $step: Creating decoder input tensor with shape ${decoderShape.contentToString()}...")
                val decoderInputTensor = OnnxTensor.createTensor(
                    ortEnvironment,
                    LongBuffer.wrap(decoderInputIds),
                    decoderShape
                )
                val prepareDuration = System.currentTimeMillis() - prepareStartTime
                Log.d(TAG, "✅ [DECODER] Step $step: Input tensor created in ${prepareDuration}ms")
                
                // Run decoder
                val inferenceStartTime = System.currentTimeMillis()
                Log.d(TAG, "🚀 [DECODER] Step $step: Running decoder inference...")
                val decoderInputs = mapOf(
                    "input_ids" to decoderInputTensor,
                    "encoder_hidden_states" to encoderHiddenStates,
                    "encoder_attention_mask" to encoderAttentionMaskTensor
                )
                
                val decoderOutputs = decoderSession.run(decoderInputs)
                val inferenceDuration = System.currentTimeMillis() - inferenceStartTime
                Log.d(TAG, "✅ [DECODER] Step $step: Decoder inference complete in ${inferenceDuration}ms")
                
                // Get logits [batch, seq_len, vocab_size]
                val extractStartTime = System.currentTimeMillis()
                val logitsTensor = decoderOutputs.get(0) as OnnxTensor
                val logits = logitsTensor.value as Array<Array<FloatArray>>
                Log.d(TAG, "📊 [DECODER] Step $step: Logits shape: [${logits.size}, ${logits[0].size}, ${logits[0][0].size}]")
                
                // Get the logits for the last token (most recent prediction)
                val lastTokenLogits = logits[0][logits[0].size - 1]
                Log.d(TAG, "📊 [DECODER] Step $step: Last token logits: ${lastTokenLogits.size} values")
                Log.v(TAG, "📊 [DECODER] Step $step: Top 5 logits: ${lastTokenLogits.mapIndexed { idx, value -> Pair(idx, value) }.sortedByDescending { it.second }.take(5).joinToString { "ID${it.first}=${String.format("%.4f", it.second)}" }}")
                
                // Find argmax (most likely next token)
                var maxIdx = 0
                var maxValue = lastTokenLogits[0]
                for (i in 1 until lastTokenLogits.size) {
                    if (lastTokenLogits[i] > maxValue) {
                        maxValue = lastTokenLogits[i]
                        maxIdx = i
                    }
                }
                
                val nextTokenId = maxIdx
                generatedIds.add(nextTokenId)
                val extractDuration = System.currentTimeMillis() - extractStartTime
                val stepDuration = System.currentTimeMillis() - stepStartTime
                Log.d(TAG, "✅ [DECODER] Step $step: Selected token ID=$nextTokenId (logit=${String.format("%.4f", maxValue)}) in ${extractDuration}ms")
                Log.d(TAG, "📊 [DECODER] Step $step: Total step time: ${stepDuration}ms")
                
                // Clean up decoder outputs (but keep encoder hidden states)
                // Note: Don't close logitsTensor explicitly - decoderOutputs.close() will handle it
                decoderInputTensor.close()
                decoderOutputs.close() // This will close logitsTensor and all other output tensors
                
                // Break if EOS token
                if (nextTokenId == EOS_ID) {
                    Log.d(TAG, "🛑 [DECODER] EOS token (ID=$EOS_ID) generated at step $step, stopping loop")
                    break
                }
                
                // Append to decoder input for next iteration
                decoderInputIds = decoderInputIds + nextTokenId.toLong()
                Log.v(TAG, "🔄 [DECODER] Step $step: Updated decoder input for next step: ${decoderInputIds.size} tokens")
            }
            
            val totalDuration = System.currentTimeMillis() - decoderLoopStartTime
            Log.d(TAG, "✅ [DECODER] Decoder loop complete in ${totalDuration}ms")
            Log.d(TAG, "📊 [DECODER] Generated ${generatedIds.size} tokens in ${generatedIds.size} steps")
            Log.d(TAG, "📊 [DECODER] Average time per step: ${if (generatedIds.isNotEmpty()) totalDuration / generatedIds.size else 0}ms")
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - decoderLoopStartTime
            Log.e(TAG, "❌ [DECODER] Decoder loop failed after ${totalDuration}ms at step ${generatedIds.size}", e)
            Log.e(TAG, "❌ [DECODER] Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "❌ [DECODER] Exception message: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            Log.d(TAG, "🧹 [DECODER] Cleaning up encoder hidden states and attention mask tensors...")
            encoderHiddenStates.close()
            encoderAttentionMaskTensor.close()
            Log.d(TAG, "✅ [DECODER] Cleanup complete")
        }
        
        return generatedIds
    }
    
    /**
     * Clean up resources.
     */
    fun close() {
        val closeStartTime = System.currentTimeMillis()
        Log.i(TAG, "🧹 [CLOSE] ========================================")
        Log.i(TAG, "🧹 [CLOSE] Closing Offline Translation Engine...")
        try {
            Log.d(TAG, "🧹 [CLOSE] Closing encoder session...")
            encoderSession.close()
            Log.d(TAG, "✅ [CLOSE] Encoder session closed")
            
            Log.d(TAG, "🧹 [CLOSE] Closing decoder session...")
            decoderSession.close()
            Log.d(TAG, "✅ [CLOSE] Decoder session closed")
            
            val closeDuration = System.currentTimeMillis() - closeStartTime
            Log.i(TAG, "✅ [CLOSE] Offline Translation Engine closed successfully in ${closeDuration}ms")
        } catch (e: Exception) {
            val closeDuration = System.currentTimeMillis() - closeStartTime
            Log.e(TAG, "❌ [CLOSE] Error closing engine after ${closeDuration}ms: ${e.message}", e)
            Log.e(TAG, "❌ [CLOSE] Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
        }
        Log.i(TAG, "🧹 [CLOSE] ========================================")
    }
}

