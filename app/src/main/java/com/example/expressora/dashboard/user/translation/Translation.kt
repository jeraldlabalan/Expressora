package com.example.expressora.dashboard.user.translation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.expressora.components.user_bottom_nav.BottomNav
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.recognition.camera.CameraBitmapAnalyzer
import com.example.expressora.recognition.di.RecognitionProvider
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.model.GlossEvent
import com.example.expressora.recognition.mediapipe.HandLandmarkerEngine
import com.example.expressora.recognition.pipeline.HandToFeaturesBridge
import com.example.expressora.recognition.pipeline.RecognitionViewModel
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.roi.MediaPipePalmRoiDetector
import com.example.expressora.recognition.view.HandLandmarkOverlay
import com.example.expressora.ui.theme.InterFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import android.util.Size
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context as AndroidContext
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.draw.shadow
import androidx.camera.core.Preview as CameraPreview

class TranslationActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val recognitionViewModel: RecognitionViewModel by viewModels {
        RecognitionProvider.provideViewModelFactory(this)
    }
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var selectedLanguage by mutableStateOf("English")
    private lateinit var handEngine: HandLandmarkerEngine
    private var recognitionEnabled = false
    private var handEngineReady = false
    private var assetsReady = false
    private var cameraPermissionGranted = false
    @Volatile
    private var handLandmarkOverlay: HandLandmarkOverlay? = null
    @Volatile
    private var handEngineInitializing = false
    private val _handEngineInitializationState = MutableSharedFlow<Boolean>(replay = 1)
    val handEngineInitializationState = _handEngineInitializationState.asSharedFlow()
    
    // ROI detector for performance optimization
    @Volatile
    private var roiDetector: MediaPipePalmRoiDetector? = null
    @Volatile
    private var cameraAnalyzer: CameraBitmapAnalyzer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            cameraPermissionGranted = isGranted
            if (isGranted) {
                // Trigger recomposition to start camera binding
                lifecycleScope.launch { _cameraPermissionState.emit(true) }
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required for recognition.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val _cameraPermissionState = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(replay = 1)
    private val cameraPermissionFlow = _cameraPermissionState.asSharedFlow()
    
    // Function to set overlay from Compose
    internal fun setHandLandmarkOverlay(overlay: HandLandmarkOverlay) {
        handLandmarkOverlay = overlay
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this, this)
        
        // Load Lite Mode preference
        val prefs = getSharedPreferences("ExpressoraPrefs", Context.MODE_PRIVATE)
        PerformanceConfig.LITE_MODE_ENABLED = prefs.getBoolean("lite_mode_enabled", false)

        recognitionEnabled = false
        handEngineReady = false
        assetsReady = RecognitionProvider.ensureAssets(this)

        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraPermissionGranted) {
            lifecycleScope.launch { _cameraPermissionState.emit(true) }
        }

        if (assetsReady) {
            // Log diagnostics once
            RecognitionProvider.logDiagnostics(this)
            
            // Initialize ROI detector if enabled
            if (PerformanceConfig.ROI_ENABLED) {
                try {
                    roiDetector = MediaPipePalmRoiDetector(
                        context = this,
                        paddingMultiplier = PerformanceConfig.ROI_PADDING_MULTIPLIER,
                        minConfidence = PerformanceConfig.ROI_MIN_CONFIDENCE,
                        detectionCadence = PerformanceConfig.ROI_DETECTION_CADENCE,
                        cacheFrames = PerformanceConfig.ROI_CACHE_FRAMES
                    )
                    Log.i(TAG, "ROI detector initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize ROI detector, continuing without ROI", e)
                    roiDetector = null
                }
            }
            
            // Initialize FeatureScaler (required for retrained model)
            try {
                HandToFeaturesBridge.initializeScaler(this@TranslationActivity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FeatureScaler, continuing without feature scaling", e)
                // App will continue to work, but model predictions may be incorrect
            }
            
            // Initialize HandLandmarkerEngine on background thread
            handEngineInitializing = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Initializing HandLandmarkerEngine on background thread...")
                    val initializedEngine = HandLandmarkerEngine(this@TranslationActivity).apply {
                        onResult = { result ->
                            // Update overlay with landmarks for visualization (must run on UI thread)
                            handLandmarkOverlay?.let { overlay ->
                                overlay.post { overlay.setHandLandmarks(result, 640, 480) }
                            }

                            // Get ROI info for coordinate mapping
                            val analyzer = cameraAnalyzer
                            val roiOffset = analyzer?.getCurrentRoiOffset()
                            val (fullWidth, fullHeight) = analyzer?.getFullImageDimensions() ?: Pair(0, 0)
                            val (croppedWidth, croppedHeight) = analyzer?.getProcessedBitmapDimensions() ?: Pair(0, 0)

                            // Continue with recognition (with ROI coordinate mapping if applicable)
                            val hands = HandToFeaturesBridge.extract(
                                result = result,
                                roiOffset = roiOffset,
                                croppedWidth = croppedWidth,
                                croppedHeight = croppedHeight,
                                fullWidth = fullWidth,
                                fullHeight = fullHeight
                            )
                            if (hands.left != null || hands.right != null) {
                                Log.d(HAND_TAG, "Detected ${listOfNotNull(hands.left, hands.right).size} hand(s)")
                            }
                            val vector = HandToFeaturesBridge.toVec(hands, RecognitionProvider.TWO_HANDS)
                            recognitionViewModel.onFeatures(vector)
                        }
                        onError = { error -> Log.e(HAND_TAG, "Error running hand landmarker", error) }
                    }
                    
                    // Switch to main thread to update UI state
                    withContext(Dispatchers.Main) {
                        handEngine = initializedEngine
                        handEngineReady = true
                        recognitionEnabled = true  // Auto-start recognition when ready
                        handEngineInitializing = false
                        _handEngineInitializationState.emit(true)
                        Log.i(TAG, "HandLandmarkerEngine initialized successfully on background thread")
                        
                        val handDelegate = "${handEngine.getDelegateType().name}/${handEngine.getRunningMode().name}"

                        // Log startup configuration (once, after initialization)
                        launch {
                            // Give classifier time to initialize on first inference
                            delay(1000)
                            val classifierDelegate = recognitionViewModel.getClassifierDelegate()
                            val classifierModel = recognitionViewModel.getClassifierModelVariant()
                            // MediaPipe Hands typically uses GPU for inference
                            RecognitionDiagnostics.logStartupConfig(
                                context = this@TranslationActivity,
                                handsDelegate = handDelegate,
                                classifierDelegate = classifierDelegate,
                                classifierModel = classifierModel,
                                faceDelegate = "CPU"
                            )
                        }
                    }
                } catch (error: Throwable) {
                    withContext(Dispatchers.Main) {
                        Log.e(TAG, "Failed to initialize HandLandmarkerEngine", error)
                        handEngineReady = false
                        handEngineInitializing = false
                        _handEngineInitializationState.emit(false)
                        assetsReady = false
                    }
                }
            }
        } else {
            Log.w(TAG, "Recognition assets missing; Start Recognition disabled.")
            lifecycleScope.launch {
                _handEngineInitializationState.emit(false)
            }
        }

        if (!cameraPermissionGranted) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val context = LocalContext.current
            val glossEvent by recognitionViewModel.state.collectAsState()
            val recognitionResult by recognitionViewModel.recognitionResult.collectAsState()
            val accumulatorState by recognitionViewModel.accumulatorState.collectAsState()
            val lastBusEvent by recognitionViewModel.lastBusEvent.collectAsState()
            var hasCameraPermission by remember { mutableStateOf(cameraPermissionGranted) }

            // DEBUG: Log when UI receives recognition result updates
            LaunchedEffect(recognitionResult) {
                val result = recognitionResult // Store in local variable to allow smart cast
                if (result != null) {
                    Log.d(TAG, "🖥️ UI RECEIVED recognition result update: label='${result.glossLabel}', " +
                            "conf=${result.glossConf} (${(result.glossConf * 100).toInt()}%), " +
                            "timestamp=${result.timestamp}, " +
                            "top3=[${result.debugInfo?.softmaxProbs?.take(3)?.joinToString { "${it.label}=${(it.value * 100).toInt()}%" }}]")
                } else {
                    Log.d(TAG, "🖥️ UI recognition result is null")
                }
            }

            LaunchedEffect(Unit) {
                cameraPermissionFlow.collectLatest { granted ->
                    hasCameraPermission = granted
                }
            }
            
            var recognitionStarted by remember { mutableStateOf(recognitionEnabled) }
            val canStartRecognition = assetsReady && handEngineReady
            
            // Track hand engine initialization state
            var handEngineInitialized by remember { mutableStateOf(handEngineReady) }
            var isInitializing by remember { mutableStateOf(handEngineInitializing) }
            
            LaunchedEffect(Unit) {
                handEngineInitializationState.collectLatest { initialized ->
                    handEngineInitialized = initialized
                    isInitializing = false
                }
            }
            
            // Lite Mode toggle state
            var liteModeEnabled by remember { mutableStateOf(PerformanceConfig.LITE_MODE_ENABLED) }
            val analyzer = remember(hasCameraPermission, roiDetector) {
                val detector = roiDetector
                CameraBitmapAnalyzer(
                    onBitmap = { bitmap ->
                        try {
                            if (recognitionEnabled && assetsReady && handEngineReady && hasCameraPermission) {
                                handEngine.detectAsync(bitmap)
                            }
                        } catch (error: Throwable) {
                            Log.e(HAND_TAG, "detectAsync failed", error)
                        } finally {
                            bitmap.recycle()
                        }
                    },
                    frameSkip = 2,
                    roiDetector = detector
                ).also {
                    // Store reference for ROI coordinate mapping
                    cameraAnalyzer = it
                }
            }
            TranslationScreen(
                glossEvent = glossEvent,
                recognitionResult = recognitionResult,
                accumulatorState = accumulatorState,
                lastBusEvent = lastBusEvent,
                cameraPermissionGranted = hasCameraPermission,
                onDevPredict = {
                    recognitionViewModel.onFeatures(RecognitionProvider.zeroFeatureVector())
                },
                onBackspace = { recognitionViewModel.backspace() },
                onClear = { recognitionViewModel.clear() },
                onSend = { recognitionViewModel.send() },
                onCopy = {
                    val json = recognitionViewModel.copySequence()
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sequence", json))
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                onSave = {
                    lifecycleScope.launch {
                        recognitionViewModel.saveSequence()
                        Toast.makeText(this@TranslationActivity, "Sequence saved", Toast.LENGTH_SHORT).show()
                    }
                },
                onShare = {
                    lifecycleScope.launch {
                        val json = recognitionViewModel.exportLastSequence()
                        if (json != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share sequence"))
                        }
                    }
                },
                ttsReady = ttsReady,
                selectedLanguage = selectedLanguage,
                onLanguageChanged = { newLang ->
                    selectedLanguage = newLang
                    tts?.language = if (newLang == "English") Locale.US else Locale("fil", "PH")
                },
                speakText = { text ->
                    speakTextAloud(this, tts, text)
                },
                onStartRecognition = {
                    if (canStartRecognition) {
                        recognitionEnabled = true
                        recognitionStarted = true
                    }
                },
                cameraAnalyzer = analyzer,
                startRecognitionEnabled = canStartRecognition && !recognitionStarted,
                liteModeEnabled = liteModeEnabled,
                onLiteModeToggle = {
                    liteModeEnabled = !liteModeEnabled
                    PerformanceConfig.LITE_MODE_ENABLED = liteModeEnabled
                    // Persist to SharedPreferences
                    getSharedPreferences("ExpressoraPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("lite_mode_enabled", liteModeEnabled)
                        .apply()
                    Toast.makeText(
                        this,
                        if (liteModeEnabled) "Lite Mode: ON (15-30 FPS target)" else "Lite Mode: OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                isInitializing = isInitializing,
                handEngineInitialized = handEngineInitialized
            )
        }
    }

    override fun onDestroy() {
        // Cleanup ROI detector
        roiDetector?.close()
        roiDetector = null
        cameraAnalyzer = null
        
        super.onDestroy()
        
        // Cleanup recognition engine
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                recognitionViewModel.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognition engine", e)
            }
        }
        
        // Cleanup hand landmarker engine
        if (handEngineReady) {
            try {
                handEngine.close()
                handEngineReady = false
            } catch (e: Exception) {
                Log.e(TAG, "Error closing hand engine", e)
            }
        }
        
        // Cleanup TTS
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
        
        recognitionEnabled = false
        
        // Clear overlay reference
        handLandmarkOverlay = null
        
        Log.d(TAG, "TranslationActivity destroyed, resources cleaned up")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
            ttsReady = true
        } else {
            ttsReady = false
        }
    }

    companion object {
        private const val TAG = "TranslationActivity"
        private const val HAND_TAG = "HandLandmarker"
        
        // Optimized executor for ImageAnalysis - uses fixed thread pool for better performance
        internal val imageAnalysisExecutor = Executors.newFixedThreadPool(
            2,
            object : ThreadFactory {
                private val threadNumber = AtomicInteger(1)
                override fun newThread(r: Runnable): Thread {
                    val thread = Thread(r, "ImageAnalysis-${threadNumber.getAndIncrement()}")
                    thread.priority = Thread.NORM_PRIORITY - 1 // Slightly lower priority
                    thread.isDaemon = true
                    return thread
                }
            }
        )
    }
}

fun speakTextAloud(context: Context, tts: TextToSpeech?, text: String) {
    if (tts == null) return
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return

    val cleanedText = text.replace(Regex("/\\w+"), "").trim()
    tts.setPitch(1.0f)
    tts.setSpeechRate(0.95f)

    val voice = tts.voices?.firstOrNull {
        it.locale == tts.language && !it.name.contains("network", ignoreCase = true)
    }
    voice?.let { tts.voice = it }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        tts.speak(cleanedText, TextToSpeech.QUEUE_FLUSH, null, "tts1")
    } else {
        @Suppress("DEPRECATION") tts.speak(cleanedText, TextToSpeech.QUEUE_FLUSH, null)
    }
}

@Composable
fun TranslationScreen(
    glossEvent: GlossEvent,
    recognitionResult: com.example.expressora.recognition.model.RecognitionResult? = null,
    accumulatorState: com.example.expressora.recognition.accumulator.AccumulatorState = com.example.expressora.recognition.accumulator.AccumulatorState(),
    lastBusEvent: String? = null,
    cameraPermissionGranted: Boolean = false,
    onDevPredict: () -> Unit,
    onBackspace: () -> Unit = {},
    onClear: () -> Unit = {},
    onSend: () -> Unit = {},
    onCopy: () -> Unit = {},
    onSave: () -> Unit = {},
    onShare: () -> Unit = {},
    ttsReady: Boolean,
    selectedLanguage: String,
    onLanguageChanged: (String) -> Unit,
    speakText: (String) -> Unit,
    onStartRecognition: () -> Unit = {},
    cameraAnalyzer: ImageAnalysis.Analyzer? = null,
    startRecognitionEnabled: Boolean = false,
    liteModeEnabled: Boolean = true,
    onLiteModeToggle: () -> Unit = {},
    isInitializing: Boolean = false,
    handEngineInitialized: Boolean = false,
) {
    val context = LocalContext.current
    var useFrontCamera by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var currentFps by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        delay(3000)
        showCard = true
    }
    
    // Update FPS every 500ms for live display
    LaunchedEffect(Unit) {
        while (true) {
            currentFps = RecognitionDiagnostics.getCurrentFPS()
            delay(500)
        }
    }

    val translations = mapOf(
        "English" to "Hi! I am Keith. /happy", "Filipino" to "Kumusta! Ako si Keith. /masaya"
    )

    var topLang by remember { mutableStateOf(selectedLanguage) }
    var bottomLang by remember { mutableStateOf(if (selectedLanguage == "English") "Filipino" else "English") }
    var topText by remember { mutableStateOf(translations[selectedLanguage] ?: "") }
    var bottomText by remember { mutableStateOf(translations[bottomLang] ?: "") }

    LaunchedEffect(selectedLanguage) {
        topLang = selectedLanguage
        bottomLang = if (selectedLanguage == "English") "Filipino" else "English"
        topText = translations[topLang] ?: ""
        bottomText = translations[bottomLang] ?: ""
    }

    val bgColor = Color(0xFFF8F8F8)
    val cardBgColor = Color(0xFFF8F8F8)

    val glossText = when (val event = glossEvent) {
        is GlossEvent.StableChunk -> event.tokens.joinToString(" ")
        is GlossEvent.InProgress -> event.tokens.joinToString(" ")
        else -> ""
    }
    val glossError = (glossEvent as? GlossEvent.Error)?.message

    Scaffold(
        bottomBar = {
            BottomNav(
                onLearnClick = {
                    context.startActivity(
                        Intent(
                            context, LearnActivity::class.java
                        )
                    )
                },
                onCameraClick = {
                    { /* already in translation */ }
                },
                onQuizClick = { context.startActivity(Intent(context, QuizActivity::class.java)) },
                modifier = Modifier.navigationBarsPadding()
            )
        }, containerColor = bgColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val lifecycleOwner = context as ComponentActivity
            val previewView = remember { PreviewView(context) }

            LaunchedEffect(useFrontCamera, cameraPermissionGranted) {
                if (!cameraPermissionGranted) return@LaunchedEffect
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    try {
                        val preview = CameraPreview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(
                                Size(PerformanceConfig.CAMERA_WIDTH, PerformanceConfig.CAMERA_HEIGHT)
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                cameraAnalyzer?.let { analyzer ->
                                    // Use optimized executor for background frame processing
                                    // Access executor via companion object since it's now internal
                                    imageAnalysis.setAnalyzer(
                                        TranslationActivity.imageAnalysisExecutor,
                                        analyzer
                                    )
                                }
                            }
                        val cameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, analysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))
            }

            // Camera preview with 4:3 aspect ratio, centered
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .align(Alignment.Center)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { previewView }
                )
            }
            
            // Hand landmark overlay on top of camera preview (matching 4:3 aspect ratio)
            val overlayView = remember {
                HandLandmarkOverlay(context).also {
                    (context as? TranslationActivity)?.setHandLandmarkOverlay(it)
                }
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .align(Alignment.Center)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { overlayView }
                )
            }

            // Recognition overlay - ALWAYS VISIBLE (top-left: status + label + FPS)
            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Status indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (recognitionResult != null) Color(0xFF4CAF50) else Color(0xFFFFA726),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isInitializing -> "Initializing models..."
                                !handEngineInitialized -> "Loading engine..."
                                recognitionResult != null -> "Recognizing"
                                else -> "Waiting for hands..."
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.DarkGray,
                            fontFamily = InterFontFamily
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Current detection - use timestamp as key to force recomposition
                    if (recognitionResult != null) {
                        key(recognitionResult.timestamp) { // Force recomposition on timestamp change
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = recognitionResult.glossLabel,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.Black,
                                fontFamily = InterFontFamily
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(recognitionResult.glossConf * 100).toInt()}%",
                                fontSize = 14.sp,
                                color = if (recognitionResult.glossConf >= 0.65f) Color(0xFF4CAF50) else Color.Gray,
                                fontFamily = InterFontFamily
                            )
                        }
                        
                        // Origin badge
                        if (recognitionResult.originLabel != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val originDisplay = if (recognitionResult.originConf != null && recognitionResult.originConf < 0.70f) {
                                "${recognitionResult.originLabel}~"
                            } else {
                                recognitionResult.originLabel
                            }
                            Text(
                                text = "Origin: $originDisplay",
                                fontSize = 12.sp,
                                color = Color.DarkGray,
                                fontFamily = InterFontFamily
                            )
                        }
                        } // End key block
                    } else {
                        Text(
                            text = "👋 Show your hand",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            fontFamily = InterFontFamily
                        )
                    }
                    
                    // FPS - always show with color coding
                    Spacer(modifier = Modifier.height(4.dp))
                    val fpsColor = when {
                        currentFps >= 15f -> Color(0xFF4CAF50)  // Green for >= 15 FPS
                        currentFps >= 10f -> Color(0xFFFFA726)  // Yellow/Orange for 10-15 FPS
                        currentFps > 0f -> Color(0xFFFB8C00)    // Darker orange for < 10 FPS
                        else -> Color.Red                        // Red for 0 FPS
                    }
                    Text(
                        text = "FPS: %.1f".format(currentFps),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fpsColor,
                        fontFamily = InterFontFamily
                    )
                    
                    // DEBUG INFO - MediaPipe counters
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "━━━ DEBUG ━━━",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3),
                        fontFamily = InterFontFamily
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "MP Frames: ${com.example.expressora.recognition.mediapipe.HandLandmarkerEngine.framesProcessed.get()}",
                        fontSize = 10.sp,
                        color = Color.DarkGray,
                        fontFamily = InterFontFamily
                    )
                    Text(
                        text = "MP Results: ${com.example.expressora.recognition.mediapipe.HandLandmarkerEngine.resultsReceived.get()}",
                        fontSize = 10.sp,
                        color = Color.DarkGray,
                        fontFamily = InterFontFamily
                    )
                    Text(
                        text = "Hands Found: ${com.example.expressora.recognition.mediapipe.HandLandmarkerEngine.handsDetected.get()}", 
                        fontSize = 10.sp,
                        color = if (com.example.expressora.recognition.mediapipe.HandLandmarkerEngine.handsDetected.get() > 0) Color(0xFF4CAF50) else Color.Red,
                        fontFamily = InterFontFamily
                    )
                    
                    // DEBUG INFO - Model outputs
                    if (recognitionResult?.debugInfo != null) {
                        val debug = recognitionResult.debugInfo
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "━━━ MODEL DEBUG ━━━",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9C27B0),
                            fontFamily = InterFontFamily
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // Feature vector stats
                        val fvStats = debug.featureVectorStats
                        Text(
                            text = "Features: ${fvStats.nonZeroCount}/${fvStats.size} non-zero",
                            fontSize = 9.sp,
                            color = if (fvStats.isAllZeros) Color.Red else Color.DarkGray,
                            fontFamily = InterFontFamily
                        )
                        Text(
                            text = "Range: [${"%.3f".format(fvStats.min)}, ${"%.3f".format(fvStats.max)}]",
                            fontSize = 9.sp,
                            color = Color.DarkGray,
                            fontFamily = InterFontFamily
                        )
                        
                        // Top 3 raw logits
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Top 3 Raw Logits:",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF9C27B0),
                            fontFamily = InterFontFamily
                        )
                        debug.rawLogits.take(3).forEach { pred ->
                            Text(
                                text = "  ${pred.label}: ${"%.2f".format(pred.value)}",
                                fontSize = 8.sp,
                                color = Color.DarkGray,
                                fontFamily = InterFontFamily
                            )
                        }
                        
                        // Top 3 softmax probabilities
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Top 3 Probs:",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF9C27B0),
                            fontFamily = InterFontFamily
                        )
                        debug.softmaxProbs.take(3).forEach { pred ->
                            Text(
                                text = "  ${pred.label}: ${(pred.value * 100).toInt()}%",
                                fontSize = 8.sp,
                                color = if (pred.value >= 0.65f) Color(0xFF4CAF50) else Color.DarkGray,
                                fontFamily = InterFontFamily
                            )
                        }
                    }
                }
            }
            
            // Token accumulator row (top-right side)
            if (accumulatorState.tokens.isNotEmpty() || accumulatorState.currentWord.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Sequence (${accumulatorState.tokens.size}/7)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.DarkGray,
                            fontFamily = InterFontFamily
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            accumulatorState.tokens.forEach { token ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = token,
                                            fontSize = 12.sp,
                                            fontFamily = InterFontFamily
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color(0xFFFACC15)
                                    )
                                )
                            }
                        }
                        
                        // Current word being built
                        if (accumulatorState.currentWord.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Building: [${accumulatorState.currentWord}]",
                                fontSize = 12.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = Color.DarkGray,
                                fontFamily = InterFontFamily
                            )
                        }
                    }
                }
            }
            
            // Last bus event (top-center, below recognition overlay)
            if (lastBusEvent != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 120.dp, start = 16.dp, end = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE64CAF50))
                ) {
                    Text(
                        text = "Published: $lastBusEvent",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Camera controls (top-center)
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { useFrontCamera = !useFrontCamera },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Camera,
                        contentDescription = "Switch Camera",
                        tint = Color(0xFFFACC15)
                    )
                }
                
                // Lite Mode toggle
                IconButton(
                    onClick = onLiteModeToggle,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (liteModeEnabled) Color(0xE64CAF50) else Color(0xE6FF9800),
                            CircleShape
                        )
                ) {
                    Text(
                        text = if (liteModeEnabled) "L" else "H",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = InterFontFamily
                    )
                }
            }

            // Control buttons (Backspace, Clear, Send) - COMMENTED OUT
            /*
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBackspace,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = Color.Black
                    )
                }
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Clear",
                        tint = Color.Black
                    )
                }
                IconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xE6FACC15), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.Black
                    )
                }
            }
            
            // Action buttons (Copy, Save, Share) - COMMENTED OUT
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onSave,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "Save",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            */
            
            AnimatedVisibility(
                visible = showCard, enter = slideInVertically(
                    initialOffsetY = { it }, animationSpec = tween(600)
                ), exit = slideOutVertically(
                    targetOffsetY = { it }, animationSpec = tween(400)
                ), modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (expanded) 260.dp else 140.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBgColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!expanded) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = topLang,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = Color.Black,
                                            fontFamily = InterFontFamily
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = topText,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 16.sp,
                                            color = Color.Black,
                                            fontFamily = InterFontFamily
                                        )
                                    }
                                    IconButton(onClick = { if (ttsReady) speakText(topText) }) {
                                        Icon(
                                            imageVector = Icons.Filled.VolumeUp,
                                            contentDescription = "Sound",
                                            tint = Color.Black,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = topLang,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color.Black,
                                                fontFamily = InterFontFamily
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = topText,
                                                fontWeight = FontWeight.Normal,
                                                fontSize = 14.sp,
                                                color = Color.Black,
                                                fontFamily = InterFontFamily
                                            )
                                        }
                                        IconButton(onClick = { if (ttsReady) speakText(topText) }) {
                                            Icon(
                                                imageVector = Icons.Filled.VolumeUp,
                                                contentDescription = "Top Sound",
                                                tint = Color.Black,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(1.dp)
                                                .background(Color.Gray)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(35.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFFACC15)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            IconButton(onClick = {
                                                val tempLang = topLang
                                                topLang = bottomLang
                                                bottomLang = tempLang
                                                topText = translations[topLang] ?: ""
                                                bottomText = translations[bottomLang] ?: ""
                                                onLanguageChanged(topLang)
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.SwapCalls,
                                                    contentDescription = "Swap Languages",
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = bottomLang,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color.Black,
                                                fontFamily = InterFontFamily
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = bottomText,
                                                fontWeight = FontWeight.Normal,
                                                fontSize = 14.sp,
                                                color = Color.Black,
                                                fontFamily = InterFontFamily
                                            )
                                        }
                                        IconButton(onClick = { if (ttsReady) speakText(bottomText) }) {
                                            Icon(
                                                imageVector = Icons.Filled.VolumeUp,
                                                contentDescription = "Bottom Sound",
                                                tint = Color.Black,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            IconButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 8.dp, bottom = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (expanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Start Recognition button - COMMENTED OUT (auto-starts now)
            /*
            Button(
                onClick = onStartRecognition,
                enabled = startRecognitionEnabled,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
            ) {
                Text("Start Recognition", fontFamily = InterFontFamily)
            }
            */

            // Dev Predict button - COMMENTED OUT (testing/debug button)
            /*
            Button(
                onClick = onDevPredict,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                Text("Dev Predict", fontFamily = InterFontFamily)
            }
            */
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TranslationScreenPreview() {
    TranslationScreen(
        glossEvent = GlossEvent.Idle,
        recognitionResult = null,
        accumulatorState = com.example.expressora.recognition.accumulator.AccumulatorState(),
        lastBusEvent = null,
        onDevPredict = {},
        onBackspace = {},
        onClear = {},
        onSend = {},
        onCopy = {},
        onSave = {},
        onShare = {},
        ttsReady = false,
        selectedLanguage = "English",
        onLanguageChanged = {},
        speakText = {},
        onStartRecognition = {},
        startRecognitionEnabled = false,
        isInitializing = false,
        handEngineInitialized = false
    )
}
