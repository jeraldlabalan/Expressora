package com.example.expressora.dashboard.user.translation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.expressora.components.bottom_nav.BottomNav
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.ui.theme.InterFontFamily
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.camera.core.Preview as CameraPreview

class TranslationActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var selectedLanguage by mutableStateOf("English")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this, this)

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            TranslationScreen(
                ttsReady = ttsReady,
                selectedLanguage = selectedLanguage,
                onLanguageChanged = { newLang ->
                    selectedLanguage = newLang
                    tts?.language = if (newLang == "English") Locale.US else Locale("fil", "PH")
                },
                speakText = { text ->
                    speakTextAloud(this, tts, text)
                })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
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
    ttsReady: Boolean,
    selectedLanguage: String,
    onLanguageChanged: (String) -> Unit,
    speakText: (String) -> Unit
) {
    val context = LocalContext.current
    var useFrontCamera by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(3000)
        showCard = true
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

            LaunchedEffect(useFrontCamera) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    try {
                        val preview = CameraPreview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val cameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(), factory = { previewView })

            IconButton(
                onClick = { useFrontCamera = !useFrontCamera },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Filled.Camera,
                    contentDescription = "Switch Camera",
                    tint = Color(0xFFFACC15)
                )
            }

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
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TranslationScreenPreview() {
    TranslationScreen(
        ttsReady = false,
        selectedLanguage = "English",
        onLanguageChanged = {},
        speakText = {})
}
