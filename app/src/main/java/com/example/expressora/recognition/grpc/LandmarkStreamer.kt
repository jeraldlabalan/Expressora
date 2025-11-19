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

class LandmarkStreamer(
    private val context: Context,
    private val serverHost: String,
    private val serverPort: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "LandmarkStreamer"
        private const val CONNECTION_TIMEOUT_SECONDS = 10L
        private const val THROTTLE_INTERVAL_MS = 55L // ~18 FPS (1000ms / 18 ≈ 55ms)
    }
    
    private var channel: ManagedChannel? = null
    private var stub: TranslationServiceGrpc.TranslationServiceStub? = null
    private var blockingStub: TranslationServiceGrpc.TranslationServiceBlockingStub? = null
    private var requestObserver: StreamObserver<LandmarkFrame>? = null
    
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    
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
                // Create new channel
                channel = ManagedChannelBuilder.forAddress(serverHost, serverPort)
                    .usePlaintext() // For development - use TLS in production
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(5, TimeUnit.SECONDS)
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
                    Log.d(TAG, "Received recognition event: type=${event.type}, label='${event.label}', confidence=${event.confidence}")
                    scope.launch {
                        _recognitionEvents.emit(event)
                    }
                }
                
                override fun onError(t: Throwable) {
                    Log.e(TAG, "gRPC stream error: ${t.message}", t)
                    isConnected.set(false)
                    isConnecting.set(false)
                    scope.launch {
                        _connectionState.emit(ConnectionState.ERROR)
                    }
                    // Attempt reconnection after delay
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
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
            Log.i(TAG, "✅ Request observer created")
            
            Log.i(TAG, "✅ Step 1/2: Setting isConnected = true, isConnecting = false")
            isConnected.set(true)
            isConnecting.set(false)
            Log.i(TAG, "✅ State updated: isConnected=${isConnected.get()}, isConnecting=${isConnecting.get()}")
            
            scope.launch {
                Log.i(TAG, "📡 Step 2/2: Emitting CONNECTED state")
                _connectionState.emit(ConnectionState.CONNECTED)
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
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected, cannot send landmarks")
            return
        }
        
        // Throttle to ~18 FPS
        val now = System.currentTimeMillis()
        if (now - lastSendTime < THROTTLE_INTERVAL_MS) {
            // Log throttled frames occasionally for debugging
            if ((now / 1000) % 5 == 0L) { // Log every 5 seconds
                Log.v(TAG, "⏸️ Frame throttled (throttle interval: ${THROTTLE_INTERVAL_MS}ms)")
            }
            return // Skip this frame
        }
        lastSendTime = now
        
        val observer = requestObserver
        if (observer == null) {
            Log.w(TAG, "Request observer not initialized")
            return
        }
        
        try {
            val landmarkFrame = LandmarkConverter.toLandmarkFrame(
                result = result,
                timestampMs = timestampMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
            
            // Log frame details for debugging
            val handCount = result.leftHandLandmarks()?.size ?: 0 + (result.rightHandLandmarks()?.size ?: 0)
            val faceCount = result.faceLandmarks()?.size ?: 0
            val poseCount = result.poseLandmarks()?.size ?: 0
            
            Log.v(TAG, "📤 Sending landmark frame: hands=$handCount, face=$faceCount, pose=$poseCount, timestamp=$timestampMs")
            
            observer.onNext(landmarkFrame)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send landmark frame: ${e.message}", e)
        }
    }
    
    /**
     * Translate a sequence of glosses using the unary TranslateSequence RPC.
     * 
     * @param glosses List of gloss labels to translate
     * @param tone Dominant tone tag (e.g., "/question", "/neutral")
     * @return TranslationResult containing the translated sentence and source
     * @throws Exception if translation fails or not connected
     */
    suspend fun translateSequence(glosses: List<String>, tone: String): TranslationResult {
        Log.i(TAG, "📞 translateSequence() called: ${glosses.size} glosses, tone=$tone")
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
            val request = GlossSequence.newBuilder()
                .addAllGlosses(glosses)
                .setDominantTone(tone)
                .build()
            Log.i(TAG, "✅ Request built: ${glosses.size} glosses, tone=$tone")
            
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
