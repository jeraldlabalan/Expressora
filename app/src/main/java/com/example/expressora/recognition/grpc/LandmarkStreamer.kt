package com.example.expressora.recognition.grpc

import android.content.Context
import android.util.Log
import com.example.expressora.grpc.LandmarkFrame
import com.example.expressora.grpc.RecognitionEvent
import com.example.expressora.grpc.TranslationServiceGrpc
import com.example.expressora.grpc.GlossSequence
import com.example.expressora.grpc.TranslationResult
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ConnectivityState
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class LandmarkStreamer(
    private val context: Context,
    private val serverHost: String,
    private val serverPort: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "LandmarkStreamer"
        private const val CONNECTION_TIMEOUT_SECONDS = 10L
        private const val THROTTLE_INTERVAL_MS = 42L // ~24 FPS (1000ms / 24 ≈ 42ms) - OPTIMIZED for better responsiveness
        
        // Exponential backoff constants
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val BASE_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val SUSTAINED_CONNECTION_MS = 10000L // 10 seconds - only reset retry counter after sustained connection
        private const val MIN_TIME_BETWEEN_ATTEMPTS_MS = 5000L // 5 seconds minimum between connection attempts
        
        /**
         * Comprehensive translation instructions for sign language gloss sequences.
         * These instructions inform the backend API about the nature of sign language glosses
         * and how to properly translate them into natural spoken language.
         */
        private val TRANSLATION_INSTRUCTIONS = """
            You are translating a sequence of sign language glosses into natural English and Filipino sentences.
            
            CONTEXT:
            - The input is a sequence of sign language GLOSSES (written representations of signs)
            - These glosses may be from American Sign Language (ASL), Filipino Sign Language (FSL), or a MIXED sequence of both
            - Glosses are NOT regular words - they are written representations of signed gestures
            - The sequence follows sentence patterns similar to sign language grammar
            - The ORDER of glosses MATTERS - they form a temporal sequence that must be preserved
            
            TRANSLATION GUIDELINES:
            1. Recognize that glosses represent signs, not spoken words
            2. Translate the sequence while preserving the intended meaning
            3. Simplify and naturalize the output to read like human spoken language
            4. Maintain the logical flow and sequence of the original signs
            5. Handle ASL topic-comment structure and FSL syntax patterns appropriately
            6. If the sequence is mixed (ASL + FSL), translate accordingly while maintaining coherence
            
            OUTPUT REQUIREMENTS:
            - Provide natural, fluent English translation
            - Provide natural, fluent Filipino translation
            - Both translations should read as if spoken by a native speaker
            - Simplify complex sign language structures into natural language
            - Preserve the semantic meaning and intent of the original sequence
        """.trimIndent()
    }
    
    private var channel: ManagedChannel? = null
    private var stub: TranslationServiceGrpc.TranslationServiceStub? = null
    private var blockingStub: TranslationServiceGrpc.TranslationServiceBlockingStub? = null
    private var requestObserver: StreamObserver<LandmarkFrame>? = null
    
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    
    // Exponential backoff state
    private var retryAttempt = 0
    private var connectionStartTime = 0L
    private var lastConnectionAttemptTime = 0L
    
    // Throttling: track last send time
    private var lastSendTime = 0L
    
    // Flow for recognition events (GLOSS, TONE, HANDS_DOWN)
    private val _recognitionEvents = MutableSharedFlow<RecognitionEvent>(replay = 1)
    val recognitionEvents: SharedFlow<RecognitionEvent> = _recognitionEvents.asSharedFlow()
    
    // Flow for connection state
    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    /**
     * Connect to the gRPC server and establish bidirectional streaming.
     */
    fun connect() {
        Log.i(TAG, "🔌 connect() called")
        Log.i(TAG, "📊 Current state: isConnecting=${isConnecting.get()}, isConnected=${isConnected.get()}, channel=${if (channel == null) "null" else "alive"}")
        
        // Check minimum time between connection attempts
        val now = System.currentTimeMillis()
        val timeSinceLastAttempt = now - lastConnectionAttemptTime
        if (lastConnectionAttemptTime > 0 && timeSinceLastAttempt < MIN_TIME_BETWEEN_ATTEMPTS_MS) {
            val remainingMs = MIN_TIME_BETWEEN_ATTEMPTS_MS - timeSinceLastAttempt
            Log.w(TAG, "⚠️ Too soon to reconnect (${timeSinceLastAttempt}ms < ${MIN_TIME_BETWEEN_ATTEMPTS_MS}ms). Waiting ${remainingMs}ms...")
            scope.launch {
                kotlinx.coroutines.delay(remainingMs)
                connect()
            }
            return
        }
        lastConnectionAttemptTime = now
        
        // Check if already connecting or connected with active stream
        if (isConnecting.get() || (isConnected.get() && requestObserver != null)) {
            Log.w(TAG, "⚠️ Already connecting or connected with active stream - skipping connection")
            return
        }
        
        Log.i(TAG, "⏳ Setting isConnecting = true")
        isConnecting.set(true)
        scope.launch {
            Log.i(TAG, "📡 Emitting CONNECTING state")
            _connectionState.emit(ConnectionState.CONNECTING)
        }
        
        try {
            Log.i(TAG, "Connecting to gRPC server at $serverHost:$serverPort")
            
            // Reuse existing channel if it exists and is not shutdown
            val channelState = channel?.getState(false)
            val isChannelShutdown = channelState == ConnectivityState.SHUTDOWN
            
            if (channel == null || isChannelShutdown) {
                Log.i(TAG, "📡 Creating new channel (existing=${channel != null}, shutdown=$isChannelShutdown)")
                // Create new channel with extended KeepAlive to prevent "Too Many Pings" errors
                // Use 5 minutes (300 seconds) to be safe, or at least 30 seconds minimum
                channel = ManagedChannelBuilder.forAddress(serverHost, serverPort)
                    .usePlaintext() // For development - use TLS in production
                    .keepAliveTime(5, TimeUnit.MINUTES) // Extended to 5 minutes to prevent KeepAlive frame spam
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build()
                
                // Create new stubs
                stub = TranslationServiceGrpc.newStub(channel)
                blockingStub = TranslationServiceGrpc.newBlockingStub(channel)
                Log.i(TAG, "✅ New channel and stubs created")
            } else {
                Log.i(TAG, "♻️ Reusing existing channel (state: $channelState)")
                // Reuse existing channel, but create new stubs if needed
                if (stub == null) {
                    stub = TranslationServiceGrpc.newStub(channel)
                }
                if (blockingStub == null) {
                    blockingStub = TranslationServiceGrpc.newBlockingStub(channel)
                }
                Log.i(TAG, "✅ Reused existing channel and stubs")
            }
            
            // Clean up old requestObserver if it exists
            if (requestObserver != null) {
                Log.i(TAG, "🧹 Cleaning up old requestObserver before creating new stream")
                requestObserver = null
            }
            
            // Create response observer for RecognitionEvent
            val responseObserver = object : StreamObserver<RecognitionEvent> {
                override fun onNext(event: RecognitionEvent) {
                    Log.i(TAG, "📥 Received recognition event from server: type=${event.type}, label='${event.label}', confidence=${event.confidence}")
                    scope.launch {
                        _recognitionEvents.emit(event)
                    }
                }
                
                override fun onError(t: Throwable) {
                    Log.e(TAG, "gRPC stream error: ${t.message}", t)
                    isConnected.set(false)
                    isConnecting.set(false)
                    
                    // Check if we should retry (exponential backoff)
                    if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
                        Log.e(TAG, "❌ Max retry attempts ($MAX_RETRY_ATTEMPTS) reached. Stopping retries.")
                        scope.launch {
                            _connectionState.emit(ConnectionState.ERROR)
                        }
                        return
                    }
                    
                    // Calculate exponential backoff with jitter
                    // Formula: baseDelay * 2^retryAttempt, capped at MAX_BACKOFF_MS
                    val baseDelay = minOf(BASE_BACKOFF_MS * (1 shl retryAttempt), MAX_BACKOFF_MS)
                    val jitter = (baseDelay * 0.2 * Random.nextDouble()).toLong() // 0-20% jitter
                    val delayMs = baseDelay + jitter
                    
                    retryAttempt++
                    Log.w(TAG, "🔄 Retry attempt $retryAttempt/$MAX_RETRY_ATTEMPTS: Will retry after ${delayMs}ms (base: ${baseDelay}ms + jitter: ${jitter}ms)")
                    
                    scope.launch {
                        _connectionState.emit(ConnectionState.ERROR)
                    }
                    
                    // Attempt reconnection after exponential backoff delay
                    scope.launch {
                        kotlinx.coroutines.delay(delayMs)
                        reconnect()
                    }
                }
                
                override fun onCompleted() {
                    Log.i(TAG, "📡 ResponseObserver.onCompleted() called - stream completed by server")
                    Log.i(TAG, "📊 Current state before onCompleted: isConnected=${isConnected.get()}, requestObserver=${if (requestObserver == null) "null" else "not null"}")
                    
                    // If requestObserver is null, we intentionally stopped the stream (e.g., during translation)
                    // Don't emit DISCONNECTED state in this case to avoid triggering activity exit
                    if (requestObserver == null) {
                        Log.i(TAG, "⚠️ onCompleted() called but requestObserver is null - stream was intentionally stopped, ignoring")
                        return
                    }
                    
                    isConnected.set(false)
                    Log.i(TAG, "✅ isConnected set to false")
                    scope.launch {
                        Log.i(TAG, "📡 Emitting DISCONNECTED state from onCompleted()")
                        _connectionState.emit(ConnectionState.DISCONNECTED)
                    }
                    Log.i(TAG, "✅ onCompleted() handling finished")
                }
            }
            
            // Create request observer (bidirectional streaming)
            Log.i(TAG, "📡 Creating request observer for bidirectional streaming...")
            requestObserver = stub!!.streamLandmarks(responseObserver)
            Log.i(TAG, "✅ Request observer created - ready to send landmark frames")
            
            // Log that we're ready to send frames
            Log.i(TAG, "🚀 Stream initialized - client can now send landmark frames to server")
            
            Log.i(TAG, "✅ Step 1/2: Setting isConnected = true, isConnecting = false")
            isConnected.set(true)
            isConnecting.set(false)
            connectionStartTime = System.currentTimeMillis()
            lastConnectionAttemptTime = connectionStartTime
            Log.i(TAG, "✅ State updated: isConnected=${isConnected.get()}, isConnecting=${isConnecting.get()}, connectionStartTime=$connectionStartTime")
            
            scope.launch {
                Log.i(TAG, "📡 Step 2/2: Emitting CONNECTED state")
                _connectionState.emit(ConnectionState.CONNECTED)
            }
            
            // Check if connection has been sustained for >10 seconds, then reset retry counter
            // This prevents "flapping" connections from resetting the backoff counter
            scope.launch {
                kotlinx.coroutines.delay(SUSTAINED_CONNECTION_MS + 1000) // Wait 11 seconds to be safe
                if (isConnected.get() && (System.currentTimeMillis() - connectionStartTime) >= SUSTAINED_CONNECTION_MS) {
                    retryAttempt = 0
                    Log.i(TAG, "✅ Connection sustained for >${SUSTAINED_CONNECTION_MS}ms - resetting retry counter to 0")
                }
            }
            
            Log.i(TAG, "🎉 ✓ Connected to gRPC server successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to gRPC server: ${e.message}", e)
            isConnecting.set(false)
            scope.launch {
                _connectionState.emit(ConnectionState.ERROR)
            }
        }
    }
    
    /**
     * Send landmark frame to server with throttling (~18 FPS).
     */
    fun sendLandmarks(
        result: HolisticLandmarkerResult,
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val connected = isConnected.get()
        if (!connected) {
            Log.w(TAG, "❌ Not connected, cannot send landmarks. isConnected=$connected")
            return
        }
        
        // Throttle to ~24 FPS (THROTTLE_INTERVAL_MS = 42ms)
        val now = System.currentTimeMillis()
        val timeSinceLastSend = now - lastSendTime
        if (timeSinceLastSend < THROTTLE_INTERVAL_MS) {
            // Log throttled frames occasionally for debugging
            if ((now / 1000) % 5 == 0L) { // Log every 5 seconds
                Log.v(TAG, "⏸️ Frame throttled: ${timeSinceLastSend}ms < ${THROTTLE_INTERVAL_MS}ms")
            }
            return // Skip this frame
        }
        lastSendTime = now
        
        val observer = requestObserver
        if (observer == null) {
            Log.e(TAG, "❌ Request observer is null - cannot send landmarks. Stream may not be initialized.")
            return
        }
        
        Log.d(TAG, "✅ Pre-flight checks passed: connected=$connected, observer=${if (observer != null) "not null" else "null"}, timeSinceLastSend=${timeSinceLastSend}ms")
        
        try {
            val landmarkFrame = LandmarkConverter.toLandmarkFrame(
                result = result,
                timestampMs = timestampMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
            
            // Log frame details for debugging
            val leftHandCount = result.leftHandLandmarks()?.size ?: 0
            val rightHandCount = result.rightHandLandmarks()?.size ?: 0
            val handCount = leftHandCount + rightHandCount
            val faceCount = result.faceLandmarks()?.size ?: 0
            val poseCount = result.poseLandmarks()?.size ?: 0
            
            Log.i(TAG, "📤 Sending landmark frame to server: hands=$handCount (L=$leftHandCount, R=$rightHandCount), face=$faceCount, pose=$poseCount, timestamp=$timestampMs")
            
            observer.onNext(landmarkFrame)
            Log.d(TAG, "✅ Landmark frame sent successfully via observer.onNext()")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send landmark frame: ${e.message}", e)
        }
    }
    
    /**
     * Translate a sequence of glosses using the unary TranslateSequence RPC.
     * 
     * **Translation Instructions:**
     * This method automatically includes comprehensive translation instructions in the request
     * that inform the backend API that:
     * - The input is a sequence of sign language GLOSSES (ASL/FSL or mixed)
     * - Glosses are written representations of signs, not regular words
     * - The sequence follows sentence patterns and order matters (temporal sequence)
     * - Output should be simplified and naturalized like human spoken language
     * 
     * The backend server should incorporate these instructions into its system prompt when calling the LLM.
     * 
     * @param glosses List of gloss labels to translate
     * @param tone Dominant tone tag (e.g., "/question", "/neutral")
     * @param origin Origin language tag (e.g., "ASL", "FSL") - optional, for code-switching support
     * @return TranslationResult containing the translated sentence and source
     * @throws Exception if translation fails or not connected
     */
    suspend fun translateSequence(glosses: List<String>, tone: String, origin: String? = null): TranslationResult {
        Log.i(TAG, "📞 translateSequence() called: ${glosses.size} glosses, tone=$tone, origin=$origin")
        Log.i(TAG, "📋 Glosses: ${glosses.joinToString(", ")}")
        
        // Allow blocking calls even if stream is stopped (channel and blockingStub remain alive)
        Log.i(TAG, "🔍 Checking blocking stub availability...")
        val stub = blockingStub
        if (stub == null) {
            Log.e(TAG, "❌ Blocking stub is null - cannot proceed with translation")
            Log.e(TAG, "📊 State check: channel=${if (channel == null) "null" else "alive"}, isConnected=${isConnected.get()}")
            throw IllegalStateException("Blocking stub not initialized. Channel may be disconnected.")
        }
        Log.i(TAG, "✅ Blocking stub available")
        
        return try {
            Log.i(TAG, "📦 Building GlossSequence request...")
            val requestBuilder = GlossSequence.newBuilder()
                .addAllGlosses(glosses)
                .setDominantTone(tone)
                .setTranslationInstructions(TRANSLATION_INSTRUCTIONS)
            
            // Add origin if provided and proto supports it
            // Note: If GlossSequence proto doesn't have an origin field, this will be a no-op
            // Server-side should be updated to include origin field in GlossSequence proto
            origin?.let { originValue ->
                try {
                    // Try to set origin field if it exists in the proto
                    // This uses reflection to safely attempt setting the field
                    val setOriginMethod = requestBuilder.javaClass.getMethod("setOrigin", String::class.java)
                    setOriginMethod.invoke(requestBuilder, originValue)
                    Log.i(TAG, "✅ Origin set in request: $originValue")
                } catch (e: NoSuchMethodException) {
                    Log.w(TAG, "⚠️ GlossSequence proto does not have origin field - server-side update needed for code-switching support")
                    Log.w(TAG, "⚠️ Origin value '$originValue' will not be sent to translation service")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Failed to set origin field: ${e.message}")
                }
            }
            
            val request = requestBuilder.build()
            Log.i(TAG, "✅ Request built: ${glosses.size} glosses, tone=$tone, origin=$origin, instructions=${TRANSLATION_INSTRUCTIONS.length} chars")
            
            Log.i(TAG, "📡 Calling blocking stub.translateSequence()...")
            val startTime = System.currentTimeMillis()
            // Use withContext to ensure blocking call runs on IO dispatcher
            val result = withContext(Dispatchers.IO) {
                stub.translateSequence(request)
            }
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "⏱️ Blocking call completed in ${duration}ms")
            
            Log.i(TAG, "✅ Translation result received: '${result.sentence}' (source: ${result.source})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to translate sequence: ${e.message}", e)
            Log.e(TAG, "📊 Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "📊 Exception details: ${e.stackTraceToString()}")
            throw e
        }
    }
    
    /**
     * Reconnect to server.
     */
    private fun reconnect() {
        Log.i(TAG, "Attempting to reconnect...")
        disconnect()
        connect()
    }
    
    /**
     * Stop streaming but keep channel alive for blocking calls (e.g., TranslateSequence).
     */
    fun stopStreaming() {
        Log.i(TAG, "🛑 stopStreaming() called")
        Log.i(TAG, "📊 Current state: isConnected=${isConnected.get()}, requestObserver=${if (requestObserver == null) "null" else "not null"}, channel=${if (channel == null) "null" else "not null"}, blockingStub=${if (blockingStub == null) "null" else "not null"}")
        
        Log.i(TAG, "⏸️ Step 1/4: Setting isConnected = false")
        isConnected.set(false)
        Log.i(TAG, "✅ isConnected updated: ${isConnected.get()}")
        
        Log.i(TAG, "⏸️ Step 2/4: Setting isConnecting = false (allow reconnection)")
        isConnecting.set(false)
        Log.i(TAG, "✅ isConnecting updated: ${isConnecting.get()}")
        
        // Close the request observer without calling onCompleted()
        // Calling onCompleted() signals normal completion to the server and triggers
        // the response observer's onCompleted() callback, which may cause side effects.
        // Instead, we just null the observer - the server will handle stream closure
        // when it stops receiving requests.
        Log.i(TAG, "🔌 Step 3/4: Nulling requestObserver (NOT calling onCompleted() to avoid side effects)")
        requestObserver = null
        Log.i(TAG, "✅ requestObserver nulled: ${if (requestObserver == null) "null" else "not null"}")
        
        // Keep channel, stub, and blockingStub alive for blocking calls
        Log.i(TAG, "📡 Step 4/4: Emitting DISCONNECTED connection state (channel/stubs remain alive)")
        scope.launch {
            _connectionState.emit(ConnectionState.DISCONNECTED)
        }
        
        Log.i(TAG, "✅ stopStreaming() completed - channel and blockingStub kept alive for blocking calls")
        Log.i(TAG, "📊 Final state: channel=${if (channel == null) "null" else "alive"}, blockingStub=${if (blockingStub == null) "null" else "alive"}")
    }
    
    /**
     * Disconnect from server completely (closes channel and all stubs).
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from gRPC server")
        
        isConnected.set(false)
        isConnecting.set(false)
        
        requestObserver?.onCompleted()
        requestObserver = null
        
        channel?.shutdown()
        try {
            channel?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Channel termination interrupted", e)
        }
        
        channel = null
        stub = null
        blockingStub = null
        
        scope.launch {
            _connectionState.emit(ConnectionState.DISCONNECTED)
        }
    }
    
    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = isConnected.get()
}
