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
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.expressora.components.user_bottom_nav.BottomNav
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.recognition.camera.CameraBitmapAnalyzer
import com.example.expressora.recognition.config.PerformanceConfig
import com.example.expressora.recognition.di.RecognitionProvider
import com.example.expressora.recognition.diagnostics.RecognitionDiagnostics
import com.example.expressora.recognition.mediapipe.HolisticLandmarkerEngine
import com.example.expressora.recognition.model.GlossEvent
import com.example.expressora.recognition.pipeline.RecognitionViewModel
import com.example.expressora.recognition.roi.MediaPipePalmRoiDetector
import com.example.expressora.recognition.view.HolisticLandmarkOverlay
import com.example.expressora.ui.theme.InterFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import androidx.camera.core.Preview as CameraPreview

class TranslationActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val recognitionViewModel: RecognitionViewModel by viewModels {
        RecognitionProvider.provideViewModelFactory(this)
    }
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var selectedLanguage by mutableStateOf("English")
    private lateinit var holisticEngine: HolisticLandmarkerEngine
    @Volatile private var recognitionEnabled = false
    @Volatile private var handEngineReady = false
    private var debugFrameCount = 0
    private var assetsReady = false
    private var cameraPermissionGranted = false
    @Volatile
    private var holisticLandmarkOverlay: HolisticLandmarkOverlay? = null
    @Volatile
    private var handEngineInitializing = false
    private val _handEngineInitializationState = MutableSharedFlow<Boolean>(replay = 1)
    val handEngineInitializationState = _handEngineInitializationState.asSharedFlow()

    private val _initializationErrorState = MutableSharedFlow<String?>(replay = 1)
    val initializationErrorState = _initializationErrorState.asSharedFlow()

    @Volatile
    private var roiDetector: MediaPipePalmRoiDetector? = null
    @Volatile
    private var cameraAnalyzer: CameraBitmapAnalyzer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            cameraPermissionGranted = isGranted
            if (isGranted) {
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

    internal fun setHolisticLandmarkOverlay(overlay: HolisticLandmarkOverlay) {
        holisticLandmarkOverlay = overlay
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this, this)

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
            RecognitionProvider.logDiagnostics(this)

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

            handEngineInitializing = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Initializing HolisticLandmarkerEngine on background thread...")
                    var frameCount = 0
                    var handsDetectedCount = 0
                    var faceDetectedCount = 0

                    val initializedEngine = HolisticLandmarkerEngine.create(
                        context = this@TranslationActivity,
                        onResult = { result ->
                            frameCount++
                            val leftHand = result.leftHandLandmarks()
                            val rightHand = result.rightHandLandmarks()
                            val faceLandmarks = result.faceLandmarks()

                            val hasLeftHand = leftHand != null && leftHand.isNotEmpty()
                            val hasRightHand = rightHand != null && rightHand.isNotEmpty()
                            val hasFace = faceLandmarks != null && faceLandmarks.isNotEmpty()

                            if (hasLeftHand) handsDetectedCount++
                            if (hasRightHand) handsDetectedCount++
                            if (hasFace) faceDetectedCount++

                            if (frameCount % 30 == 0 || hasLeftHand || hasRightHand || hasFace) {
                                Log.d(TAG, "🎯 Holistic Detection [Frame $frameCount]: " +
                                        "LHand=${hasLeftHand}, RHand=${hasRightHand}, " +
                                        "Face=${hasFace} (${faceLandmarks?.size ?: 0} pts)")
                            }

                            if (frameCount % 30 == 0) {
                                val handsRate = (handsDetectedCount * 100f / 30f).toInt()
                                val faceRate = (faceDetectedCount * 100f / 30f).toInt()
                                Log.i(TAG, "📊 Detection Summary [Last 30 frames]: " +
                                        "Hands=$handsRate%, Face=$faceRate%")
                                handsDetectedCount = 0
                                faceDetectedCount = 0
                            }

                            val inferenceTimestamp = System.currentTimeMillis()

                            // Only update overlay when hands OR face are detected
                            if (hasLeftHand || hasRightHand || hasFace) {
                                holisticLandmarkOverlay?.let { overlay ->
                                    val analyzer = cameraAnalyzer
                                    val (imageWidth, imageHeight) = analyzer?.getFullImageDimensions() ?: Pair(640, 480)

                                    overlay.post {
                                        overlay.setHolisticLandmarks(result, imageWidth, imageHeight, inferenceTimestamp)
                                        Log.v(TAG, "📝 Overlay updated: result=${if (result != null) "not null" else "null"}, " +
                                                "hands=${if (result?.leftHandLandmarks() != null) "L" else ""}${if (result?.rightHandLandmarks() != null) "R" else ""}, " +
                                                "size=${imageWidth}x${imageHeight}, timestamp=$inferenceTimestamp")
                                    }
                                } ?: run {
                                    Log.w(TAG, "⚠️ holisticLandmarkOverlay is null - cannot update overlay")
                                }
                            } else {
                                // Clear overlay when nothing is detected
                                holisticLandmarkOverlay?.let { overlay ->
                                    val analyzer = cameraAnalyzer
                                    val (imageWidth, imageHeight) = analyzer?.getFullImageDimensions() ?: Pair(640, 480)

                                    overlay.post {
                                        overlay.setHolisticLandmarks(null, imageWidth, imageHeight, inferenceTimestamp)
                                        Log.v(TAG, "🧹 Overlay cleared: no hands/face detected")
                                    }
                                }
                            }

                            val analyzer = cameraAnalyzer
                            val (imageWidth, imageHeight) = analyzer?.getFullImageDimensions() ?: Pair(640, 480)

                            val timestampMs = inferenceTimestamp

                            if (hasLeftHand || hasRightHand || hasFace) {
                                Log.v(TAG, "📤 Sending landmarks to server: " +
                                        "hands=${if (hasLeftHand) "L" else ""}${if (hasRightHand) "R" else ""}, " +
                                        "face=${if (hasFace) "✓" else "✗"}")
                            }

                            recognitionViewModel.onLandmarks(result, timestampMs, imageWidth, imageHeight)
                        },
                        onError = { error: Throwable -> Log.e(HAND_TAG, "Error running holistic landmarker", error) }
                    )

                    withContext(Dispatchers.Main) {
                        holisticEngine = initializedEngine
                        handEngineReady = true
                        recognitionEnabled = true
                        handEngineInitializing = false
                        _handEngineInitializationState.emit(true)
                        Log.i(TAG, "HolisticLandmarkerEngine initialized successfully on background thread")

                        val holisticDelegate = "${holisticEngine.getDelegateType().name}/${holisticEngine.getRunningMode().name}"

                        launch {
                            delay(1000)
                            val classifierDelegate = recognitionViewModel.getClassifierDelegate()
                            val classifierModel = recognitionViewModel.getClassifierModelVariant()
                            RecognitionDiagnostics.logStartupConfig(
                                context = this@TranslationActivity,
                                handsDelegate = holisticDelegate,
                                classifierDelegate = classifierDelegate,
                                classifierModel = classifierModel,
                                faceDelegate = holisticDelegate
                            )
                        }
                    }
                } catch (error: Throwable) {
                    withContext(Dispatchers.Main) {
                        val errorMessage = when {
                            error.message?.contains("not found in assets") == true ->
                                "Holistic model file missing. Please rebuild the app."
                            error.message?.contains("Failed to create HolisticLandmarker") == true ->
                                "Failed to load holistic model: ${error.message}"
                            else -> "Engine initialization failed: ${error.message ?: error.javaClass.simpleName}"
                        }

                        Log.e(TAG, "Failed to initialize HolisticLandmarkerEngine: $errorMessage", error)

                        Toast.makeText(
                            this@TranslationActivity,
                            "Failed to initialize recognition engine: ${error.message?.take(50) ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()

                        handEngineReady = false
                        handEngineInitializing = false
                        _handEngineInitializationState.emit(false)
                        assetsReady = false

                        _initializationErrorState.emit(errorMessage)
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
            val glossList by recognitionViewModel.glossList.collectAsState()
            val currentTone by recognitionViewModel.currentTone.collectAsState()
            val isScanning by recognitionViewModel.isScanning.collectAsState()
            val translationResult by recognitionViewModel.translationResult.collectAsState()
            val isTranslating by recognitionViewModel.isTranslating.collectAsState()
            val useOnlineMode by recognitionViewModel.useOnlineMode.collectAsState()

            LaunchedEffect(glossList) {
                Log.d(TRANSLATION_SCREEN_TAG, "🖥️ UI: glossList changed: size=${glossList.size}, items=[${glossList.joinToString(", ")}]")
            }

            LaunchedEffect(isTranslating) {
                Log.i(TRANSLATION_SCREEN_TAG, "🔄 UI: isTranslating state changed to: $isTranslating")
            }
            LaunchedEffect(translationResult) {
                val result = translationResult
                if (result != null) {
                    Log.i(TRANSLATION_SCREEN_TAG, "🔄 UI: translationResult state changed - sentence='${result.sentence}', source='${result.source}'")
                } else {
                    Log.i(TRANSLATION_SCREEN_TAG, "🔄 UI: translationResult state changed to: null")
                }
            }
            LaunchedEffect(isScanning) {
                Log.i(TRANSLATION_SCREEN_TAG, "🔄 UI: isScanning state changed to: $isScanning")
                if (!isScanning) {
                    // Clear overlay when scanning stops
                    val overlay = this@TranslationActivity.holisticLandmarkOverlay
                    overlay?.post { overlay.clear() }
                    Log.v(TAG, "🧹 Overlay cleared: scanning stopped")
                }
            }

            var hasCameraPermission by remember { mutableStateOf(cameraPermissionGranted) }

            LaunchedEffect(recognitionResult) {
                val result = recognitionResult
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

            var handEngineInitialized by remember { mutableStateOf(handEngineReady) }
            var isInitializing by remember { mutableStateOf(handEngineInitializing) }
            var initializationError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                handEngineInitializationState.collectLatest { initialized ->
                    handEngineInitialized = initialized
                    isInitializing = false
                }
            }

            LaunchedEffect(Unit) {
                initializationErrorState.collectLatest { error ->
                    initializationError = error
                }
            }

            val analyzer = remember(hasCameraPermission, roiDetector, isScanning) {
                Log.i(TAG, "🔄 Creating new CameraBitmapAnalyzer: hasCameraPermission=$hasCameraPermission, isScanning=$isScanning")
                val detector = roiDetector
                CameraBitmapAnalyzer(
                    onFrameProcessed = { bitmap, timestamp ->
                        try {
                            val threadName = Thread.currentThread().name
                            if (threadName.contains("main", ignoreCase = true)) {
                                Log.w(TAG, "⚠️ onFrameProcessed callback running on main thread! Thread: $threadName")
                            }

                            // Debug logging: print every 60 frames to confirm analyzer is alive
                            if (debugFrameCount++ % 60 == 0) {
                                Log.v(TAG, "📷 Analyzer alive. Scanning: $isScanning, RecognitionEnabled: $recognitionEnabled, HandEngineReady: $handEngineReady")
                            }

                            if (recognitionEnabled && assetsReady && handEngineReady && hasCameraPermission && isScanning) {
                                holisticEngine.detectAsync(bitmap, timestamp)
                            }
                        } catch (error: Throwable) {
                            Log.e(HAND_TAG, "detectAsync failed", error)
                        }
                    },
                    frameSkip = 2,
                    roiDetector = detector
                ).also {
                    cameraAnalyzer = it
                    Log.i(TAG, "✅ CameraBitmapAnalyzer created and stored (isScanning=$isScanning)")
                }
            }

            TranslationScreen(
                glossEvent = glossEvent,
                recognitionResult = recognitionResult,
                accumulatorState = accumulatorState,
                lastBusEvent = lastBusEvent,
                cameraPermissionGranted = hasCameraPermission,
                glossList = glossList,
                currentTone = currentTone,
                isScanning = isScanning,
                translationResult = translationResult,
                isTranslating = isTranslating,
                onRemoveGloss = { recognitionViewModel.removeLastGloss() },
                onConfirmTranslation = {
                    // Clear overlay immediately when translation starts
                    val overlay = this@TranslationActivity.holisticLandmarkOverlay
                    overlay?.post { overlay.clear() }
                    recognitionViewModel.confirmTranslation()
                },
                onResumeScanning = { recognitionViewModel.resumeScanning() },
                onSetIsScanning = { value -> recognitionViewModel.setIsScanning(value) },
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
                speakText = { text, languageCode ->
                    speakTextAloud(this, tts, text, languageCode)
                },
                onStartRecognition = {
                    if (canStartRecognition) {
                        recognitionEnabled = true
                        recognitionStarted = true
                    }
                },
                cameraAnalyzer = analyzer,
                startRecognitionEnabled = canStartRecognition && !recognitionStarted,
                isInitializing = isInitializing,
                handEngineInitialized = handEngineInitialized,
                initializationError = initializationError,
                useOnlineMode = useOnlineMode,
                onToggleOnlineMode = { recognitionViewModel.toggleOnlineMode() }
            )
        }
    }

    override fun onDestroy() {
        roiDetector?.close()
        roiDetector = null
        cameraAnalyzer = null

        super.onDestroy()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                recognitionViewModel.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognition engine", e)
            }
        }

        if (handEngineReady) {
            try {
                holisticEngine.close()
                handEngineReady = false
            } catch (e: Exception) {
                Log.e(TAG, "Error closing hand engine", e)
            }
        }

        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }

        recognitionEnabled = false
        holisticLandmarkOverlay = null

        Log.d(TAG, "TranslationActivity destroyed, resources cleaned up")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)

            val filipinoLocale = Locale("fil", "PH")
            val tagalogLocale = Locale("tl", "PH")

            val filipinoAvailable = tts?.isLanguageAvailable(filipinoLocale) ?: TextToSpeech.LANG_MISSING_DATA
            val tagalogAvailable = tts?.isLanguageAvailable(tagalogLocale) ?: TextToSpeech.LANG_MISSING_DATA

            if (filipinoAvailable >= TextToSpeech.LANG_AVAILABLE) {
                Log.i(TAG, "Filipino (fil) locale is available")
            } else if (tagalogAvailable >= TextToSpeech.LANG_AVAILABLE) {
                Log.i(TAG, "Tagalog (tl) locale is available as fallback")
            } else {
                Log.w(TAG, "⚠️ Filipino/Tagalog voice data not installed. Filipino text may sound unnatural. Please install Filipino voice pack in Android Settings > Text-to-speech output")
            }

            ttsReady = true
        } else {
            ttsReady = false
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    companion object {
        private const val TAG = "TranslationActivity"
        private const val HAND_TAG = "HandLandmarker"

        internal val imageAnalysisExecutor = Executors.newFixedThreadPool(
            2,
            object : ThreadFactory {
                private val threadNumber = AtomicInteger(1)
                override fun newThread(r: Runnable): Thread {
                    val thread = Thread(r, "ImageAnalysis-${threadNumber.getAndIncrement()}")
                    thread.priority = Thread.NORM_PRIORITY - 1
                    thread.isDaemon = true
                    return thread
                }
            }
        )
    }
}

fun speakTextAloud(context: Context, tts: TextToSpeech?, text: String, languageCode: String = "en") {
    if (tts == null) return
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return

    val cleanedText = text.replace(Regex("/\\w+"), "").trim()
    tts.stop()

    val locale = when (languageCode) {
        "fil", "tl" -> {
            val fil = Locale("fil", "PH")
            val tagalog = Locale("tl", "PH")
            val filAvailable = tts.isLanguageAvailable(fil)
            if (filAvailable >= TextToSpeech.LANG_AVAILABLE) {
                fil
            } else {
                val tagalogAvailable = tts.isLanguageAvailable(tagalog)
                if (tagalogAvailable >= TextToSpeech.LANG_AVAILABLE) {
                    tagalog
                } else {
                    Log.w("TranslationActivity", "⚠️ Filipino/Tagalog voice data not available. Falling back to English.")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "Filipino voice data not installed on device", Toast.LENGTH_SHORT).show()
                    }
                    Locale.US
                }
            }
        }
        else -> Locale.US
    }

    val setLanguageResult = tts.setLanguage(locale)
    if (setLanguageResult == TextToSpeech.LANG_MISSING_DATA ||
        setLanguageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w("TranslationActivity", "⚠️ Language not supported or missing data: $locale")
        if (languageCode == "fil" || languageCode == "tl") {
            tts.setLanguage(Locale.US)
        }
    }

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

private const val TRANSLATION_SCREEN_TAG = "TranslationScreen"

@OptIn(ExperimentalMaterial3Api::class)
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
    speakText: (String, String) -> Unit,
    onStartRecognition: () -> Unit = {},
    cameraAnalyzer: ImageAnalysis.Analyzer? = null,
    startRecognitionEnabled: Boolean = false,
    isInitializing: Boolean = false,
    handEngineInitialized: Boolean = false,
    initializationError: String? = null,
    glossList: List<String> = emptyList(),
    currentTone: String = "/neutral",
    isScanning: Boolean = true,
    translationResult: com.example.expressora.grpc.TranslationResult? = null,
    isTranslating: Boolean = false,
                onRemoveGloss: () -> Unit = {},
                onConfirmTranslation: () -> Unit = {},
                onResumeScanning: () -> Unit = {},
                onSetIsScanning: (Boolean) -> Unit = {},
                useOnlineMode: Boolean = true,
                onToggleOnlineMode: () -> Unit = {},
) {
    val context = LocalContext.current
    var useFrontCamera by remember { mutableStateOf(true) }
    var showCard by remember { mutableStateOf(false) }
    var currentFps by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        delay(3000)
        showCard = true
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentFps = RecognitionDiagnostics.getCurrentFPS()
            delay(500)
        }
    }

    val bgColor = Color(0xFF121212)
    val cardBgColor = Color(0xFF1E1E1E)

    Scaffold(
        bottomBar = {
            BottomNav(
                onLearnClick = {
                    context.startActivity(Intent(context, LearnActivity::class.java))
                },
                onCameraClick = {},
                onQuizClick = { context.startActivity(Intent(context, QuizActivity::class.java)) },
                modifier = Modifier.navigationBarsPadding()
            )
        }, containerColor = bgColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(paddingValues)
        ) {
            val lifecycleOwner = LocalLifecycleOwner.current
            val previewView = remember { PreviewView(context) }

            val configuration = LocalConfiguration.current
            val rotation = remember(configuration) {
                try {
                    // Use WindowManager for API 24+ compatibility (context.display requires API 30)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // API 30+: Use context.display
                        context.display?.rotation ?: Surface.ROTATION_0
                    } else {
                        // API 24-29: Use WindowManager
                        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                        windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                    }
                } catch (e: Exception) {
                    Surface.ROTATION_0
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top
            ) {
                LaunchedEffect(useFrontCamera, cameraPermissionGranted, isScanning, rotation) {
                    if (!cameraPermissionGranted || cameraAnalyzer == null) return@LaunchedEffect

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        try {
                            val surfaceProvider = previewView.surfaceProvider ?: return@addListener

                            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                return@addListener
                            }

                            val preview = CameraPreview.Builder()
                                .setTargetRotation(rotation)
                                .build()
                                .also { it.surfaceProvider = surfaceProvider }

                            val analysis = ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { imageAnalysis ->
                                    cameraAnalyzer?.let { analyzer ->
                                        imageAnalysis.setAnalyzer(
                                            TranslationActivity.imageAnalysisExecutor,
                                            analyzer
                                        )
                                    }
                                }

                            val cameraSelector = CameraSelector.Builder()
                                .requireLensFacing(if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                                .build()

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, preview, analysis
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(context))
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_PAUSE) {
                            onSetIsScanning(false)
                            try {
                                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                                cameraProvider.unbindAll()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val overlayView = remember {
                    HolisticLandmarkOverlay(context).also {
                        (context as? TranslationActivity)?.setHolisticLandmarkOverlay(it)
                    }
                }

                // ZONE B: Camera Card (4:3 Aspect Ratio)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { previewView }
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { overlayView }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ZONE C: Translation Deck
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    if (glossList.isNotEmpty()) {
                        val toneBorderColor = when {
                            currentTone.contains("question", ignoreCase = true) -> Color(0xFFFFEB3B)
                            currentTone.contains("angry", ignoreCase = true) || currentTone.contains("serious", ignoreCase = true) -> Color(0xFFF44336)
                            currentTone.contains("exclamation", ignoreCase = true) || currentTone.contains("happy", ignoreCase = true) -> Color(0xFF4CAF50)
                            else -> Color(0xFF2196F3)
                        }

                        // 7-Slot Grid (Wrappable) for Gloss Sequence
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp) // Spacing for wrapped lines
                        ) {
                            repeat(7) { index ->
                                val isFilled = index < glossList.size
                                val gloss = if (isFilled) glossList[index] else null
                                val isLastItem = index == glossList.size - 1
                                
                                // Slot container - use fixed width for FlowRow instead of weight
                                Box(
                                    modifier = Modifier
                                        .width(80.dp) // Fixed width for consistent slot size
                                        .height(48.dp)
                                        .then(
                                            if (isFilled) {
                                                Modifier
                                                    .background(Color(0xFF4CAF50), RoundedCornerShape(12.dp))
                                                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                            } else {
                                                Modifier
                                                    .background(Color.Transparent)
                                                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                                    .alpha(0.3f)
                                            }
                                        )
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isFilled && gloss != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = gloss,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = InterFontFamily,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isLastItem) {
                                                IconButton(
                                                    onClick = onRemoveGloss,
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Close,
                                                        contentDescription = "Delete Last",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Empty slot - show slot number with reduced opacity
                                        Text(
                                            text = "${index + 1}",
                                            color = Color.White.copy(alpha = 0.2f),
                                            fontSize = 12.sp,
                                            fontFamily = InterFontFamily
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (translationResult != null) {
                        val displayText = if (selectedLanguage == "English") {
                            translationResult.sentence
                        } else {
                            translationResult.sentenceFilipino.ifEmpty { translationResult.sentence }
                        }

                        Text(
                            text = displayText,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = InterFontFamily,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                }
            } // End Main Column

            // --- LAYER 2: FLOATING ELEMENTS ---

            // 1. Status Card (Top Left)
            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE6FFFFFF))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    when {
                                        initializationError != null -> Color.Red
                                        recognitionResult != null -> Color(0xFF4CAF50)
                                        else -> Color(0xFFFFA726)
                                    }, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                initializationError != null -> "Error"
                                isInitializing -> "Init..."
                                !handEngineInitialized -> "Loading..."
                                recognitionResult != null -> "Active"
                                else -> "Waiting"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.DarkGray,
                            fontFamily = InterFontFamily
                        )
                    }
                    if (recognitionResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = recognitionResult.glossLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "FPS: %.1f".format(currentFps),
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }

            // 2. Camera Controls (Top Center)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
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
            }

            // 2b. Online/Offline Mode Toggle (Top Right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = onToggleOnlineMode,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xE6FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = if (useOnlineMode) Icons.Filled.Cloud else Icons.Filled.CloudQueue,
                        contentDescription = if (useOnlineMode) "Online Mode (Server)" else "Offline Mode (Local)",
                        tint = if (useOnlineMode) Color(0xFF4CAF50) else Color(0xFF757575) // Green for online, Grey for offline
                    )
                }
            }

            // 3. Translate Button (Bottom Right)
            if (glossList.isNotEmpty() && isScanning) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp)
                ) {
                    FloatingActionButton(
                        onClick = onConfirmTranslation,
                        containerColor = Color(0xFF2196F3)
                    ) {
                        Text(
                            text = "TRANSLATE",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = InterFontFamily,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            // 4. Modal Bottom Sheet (Translation Result)
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            LaunchedEffect(isTranslating) {
                if (isTranslating) sheetState.show()
                else if (translationResult == null) sheetState.hide()
            }

            LaunchedEffect(translationResult) {
                if (translationResult != null && !isTranslating) sheetState.show()
            }

            if (isTranslating || translationResult != null) {
                ModalBottomSheet(
                    sheetState = sheetState,
                    onDismissRequest = {
                        if (!isTranslating) onResumeScanning()
                    },
                    containerColor = cardBgColor
                ) {
                    if (isTranslating && translationResult == null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Translating...", color = Color.White, fontFamily = InterFontFamily)
                        }
                    } else if (translationResult != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Gloss Sequence at Top
                            if (glossList.isNotEmpty()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Gloss Sequence:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 14.sp,
                                        fontFamily = InterFontFamily
                                    )
                                    Text(
                                        text = glossList.joinToString(", "),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = InterFontFamily
                                    )
                                }
                            }

                            // English Section
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "English",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = InterFontFamily
                                    )
                                    IconButton(
                                        onClick = {
                                            if (ttsReady) {
                                                speakText(translationResult.sentence, "en")
                                            }
                                        },
                                        enabled = ttsReady
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.VolumeUp,
                                            contentDescription = "Speak English",
                                            tint = if (ttsReady) Color.White else Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                Text(
                                    text = translationResult.sentence,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = InterFontFamily
                                )
                            }

                            // Filipino Section
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Filipino",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = InterFontFamily
                                    )
                                    IconButton(
                                        onClick = {
                                            if (ttsReady) {
                                                speakText(translationResult.sentenceFilipino, "fil")
                                            }
                                        },
                                        enabled = ttsReady
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.VolumeUp,
                                            contentDescription = "Speak Filipino",
                                            tint = if (ttsReady) Color.White else Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                Text(
                                    text = translationResult.sentenceFilipino.ifEmpty { translationResult.sentence },
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = InterFontFamily
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Done Button
                            Button(
                                onClick = onResumeScanning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Done", fontFamily = InterFontFamily)
                            }
                        }
                    }
                }
            }

        } // End Root Box
    } // End Scaffold
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
        speakText = { _, _ -> },
        onStartRecognition = {},
        startRecognitionEnabled = false,
        isInitializing = false,
        handEngineInitialized = false,
        initializationError = null,
        onSetIsScanning = {},
        useOnlineMode = true,
        onToggleOnlineMode = {}
    )
}