package com.example.expressora.dashboard.user.learn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.expressora.R
import com.example.expressora.components.top_nav3.TopNav3
import com.example.expressora.components.user_bottom_nav.BottomNav
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.dashboard.user.translation.TranslationActivity
import com.example.expressora.ui.theme.InterFontFamily
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.core.ImageAnalysis
import com.example.expressora.recognition.grpc.LandmarkStreamer
import com.example.expressora.recognition.di.RecognitionProvider
import com.example.expressora.recognition.mediapipe.HolisticLandmarkerEngine
import com.example.expressora.recognition.view.HolisticLandmarkOverlay
import com.example.expressora.recognition.camera.CameraBitmapAnalyzer
import com.example.expressora.grpc.RecognitionEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

class LearnActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LearnApp() }
    }
}

@Composable
fun LearnApp() {
    val navController = rememberNavController()
    val completedLessons = remember { mutableStateListOf<String>() }
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf("lessonList") }

    Scaffold(
        topBar = {
            if (currentScreen != "detection") {
                TopNav3(onTranslateClick = {
                    context.startActivity(Intent(context, CommunitySpaceActivity::class.java))
                })
            }
        }, bottomBar = {
            BottomNav(
                onLearnClick = { /* Already in learn */ },
                onCameraClick = {
                    context.startActivity(
                        Intent(
                            context, TranslationActivity::class.java
                        )
                    )
                },
                onQuizClick = { context.startActivity(Intent(context, QuizActivity::class.java)) })
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->

        Box(modifier = Modifier.padding(paddingValues)) {
            NavHost(navController = navController, startDestination = "lessonList") {
                composable("lessonList") {
                    currentScreen = "lessonList"
                    LessonListScreen(
                        completedLessons = completedLessons, onLessonSelected = { lesson ->
                            navController.currentBackStackEntry?.savedStateHandle?.set(
                                "lesson", lesson
                            )
                            navController.navigate("lessonDetail")
                        })
                }

                composable("lessonDetail") {
                    currentScreen = "lessonDetail"
                    val lesson =
                        navController.previousBackStackEntry?.savedStateHandle?.get<Pair<String, String>>(
                            "lesson"
                        )
                    lesson?.let { (title, description) ->
                        LessonDetailScreen(
                            lessonTitle = title,
                            lessonDescription = description,
                            mediaAttachments = listOf(
                                R.drawable.sample_profile,
                                R.drawable.sample_profile2,
                                R.drawable.expressora_logo,
                                R.drawable.camera_preview,
                            ),
                            tryItems = listOf("A", "B", "C"), // TODO: Get from actual lesson data
                            onTryItOut = { tryItems ->
                                if (!completedLessons.contains(title)) completedLessons.add(title)
                                navController.currentBackStackEntry?.savedStateHandle?.set(
                                    "tryItems", tryItems
                                )
                                navController.navigate("detection")
                            })
                    }
                }


                composable("detection") {
                    currentScreen = "detection"
                    val tryItems = navController.previousBackStackEntry?.savedStateHandle?.get<List<String>>(
                        "tryItems"
                    ) ?: listOf("A", "B", "C") // Default fallback
                    DetectionScreen(
                        expectedGlosses = tryItems,
                        onDetectionFinished = { navController.navigate("completion") })
                }

                composable("completion") {
                    currentScreen = "completion"
                    LessonCompletionScreen(
                        onNextCourse = {
                            navController.popBackStack(
                                "lessonList", inclusive = false
                            )
                        })
                }
            }
        }
    }
}

@Composable
fun LessonListScreen(
    completedLessons: List<String>, onLessonSelected: (Pair<String, String>) -> Unit
) {
    val lessonTitles = listOf(
        "Introduction and Orientation" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla convallis risus nec risus fermentum, tempor ullamcorper orci molestie. Sed pharetra lobortis eros eu consequat. Sed elementum ipsum at sem eleifend tincidunt. Vestibulum quis nibh vitae eros molestie vulputate ac quis nulla. Cras sagittis elementum eros quis commodo. Nullam convallis sollicitudin arcu quis porttitor. Proin dapibus tempor lectus, id finibus diam suscipit a. Vestibulum ac odio risus. Proin lectus mauris, fringilla ut efficitur sit amet, mattis quis risus. Aenean pellentesque consectetur risus. Sed luctus arcu eget lectus auctor mattis.\n" + "\n" + "Cras interdum et magna eu gravida. Maecenas gravida mollis viverra. Phasellus eu ante a metus pulvinar hendrerit. Integer dapibus, libero sit amet tempor mattis, libero lorem lobortis neque, in convallis mauris felis rutrum lorem. Fusce consequat a enim sed rhoncus. Duis viverra imperdiet velit laoreet pharetra. Morbi porta odio nec tellus mollis, non porta ante mollis. Fusce feugiat porttitor gravida. Integer pulvinar lorem et pretium malesuada. Ut condimentum mauris turpis, et egestas quam dapibus non. Integer eu felis quam. Quisque scelerisque a quam nec euismod. Nulla consequat nulla eget euismod venenatis. Etiam tellus erat, ultricies ut efficitur et, rhoncus a dolor.\n" + "\n" + "Quisque lacinia eleifend dui, nec bibendum quam. Sed aliquet neque placerat mauris malesuada venenatis. In ac orci feugiat, bibendum velit vitae, efficitur erat. Mauris aliquam nunc purus, vitae viverra nunc pulvinar in. Praesent sit amet commodo ante. Integer aliquet, justo quis aliquet mollis, mauris dui cursus elit, a convallis ante risus sit amet ipsum. Sed enim diam, consequat in auctor non, vestibulum ut nunc. Nam nec lectus iaculis, dapibus felis et, finibus metus. Maecenas faucibus lorem purus, eget porttitor leo eleifend condimentum. Praesent lectus lacus, sodales sit amet luctus et, interdum a nunc. Nunc condimentum faucibus lobortis.",
        "Alphabets" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla convallis risus nec risus fermentum, tempor ullamcorper orci molestie. Sed pharetra lobortis eros eu consequat. Sed elementum ipsum at sem eleifend tincidunt. Vestibulum quis nibh vitae eros molestie vulputate ac quis nulla. Cras sagittis elementum eros quis commodo. Nullam convallis sollicitudin arcu quis porttitor. Proin dapibus tempor lectus, id finibus diam suscipit a. Vestibulum ac odio risus. Proin lectus mauris, fringilla ut efficitur sit amet, mattis quis risus. Aenean pellentesque consectetur risus. Sed luctus arcu eget lectus auctor mattis.\n" + "\n" + "Cras interdum et magna eu gravida. Maecenas gravida mollis viverra. Phasellus eu ante a metus pulvinar hendrerit. Integer dapibus, libero sit amet tempor mattis, libero lorem lobortis neque, in convallis mauris felis rutrum lorem. Fusce consequat a enim sed rhoncus. Duis viverra imperdiet velit laoreet pharetra. Morbi porta odio nec tellus mollis, non porta ante mollis. Fusce feugiat porttitor gravida. Integer pulvinar lorem et pretium malesuada. Ut condimentum mauris turpis, et egestas quam dapibus non. Integer eu felis quam. Quisque scelerisque a quam nec euismod. Nulla consequat nulla eget euismod venenatis. Etiam tellus erat, ultricies ut efficitur et, rhoncus a dolor.\n" + "\n" + "Quisque lacinia eleifend dui, nec bibendum quam. Sed aliquet neque placerat mauris malesuada venenatis. In ac orci feugiat, bibendum velit vitae, efficitur erat. Mauris aliquam nunc purus, vitae viverra nunc pulvinar in. Praesent sit amet commodo ante. Integer aliquet, justo quis aliquet mollis, mauris dui cursus elit, a convallis ante risus sit amet ipsum. Sed enim diam, consequat in auctor non, vestibulum ut nunc. Nam nec lectus iaculis, dapibus felis et, finibus metus. Maecenas faucibus lorem purus, eget porttitor leo eleifend condimentum. Praesent lectus lacus, sodales sit amet luctus et, interdum a nunc. Nunc condimentum faucibus lobortis.",
        "Greetings and Basic Phrases" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla convallis risus nec risus fermentum, tempor ullamcorper orci molestie. Sed pharetra lobortis eros eu consequat. Sed elementum ipsum at sem eleifend tincidunt. Vestibulum quis nibh vitae eros molestie vulputate ac quis nulla. Cras sagittis elementum eros quis commodo. Nullam convallis sollicitudin arcu quis porttitor. Proin dapibus tempor lectus, id finibus diam suscipit a. Vestibulum ac odio risus. Proin lectus mauris, fringilla ut efficitur sit amet, mattis quis risus. Aenean pellentesque consectetur risus. Sed luctus arcu eget lectus auctor mattis.\n" + "\n" + "Cras interdum et magna eu gravida. Maecenas gravida mollis viverra. Phasellus eu ante a metus pulvinar hendrerit. Integer dapibus, libero sit amet tempor mattis, libero lorem lobortis neque, in convallis mauris felis rutrum lorem. Fusce consequat a enim sed rhoncus. Duis viverra imperdiet velit laoreet pharetra. Morbi porta odio nec tellus mollis, non porta ante mollis. Fusce feugiat porttitor gravida. Integer pulvinar lorem et pretium malesuada. Ut condimentum mauris turpis, et egestas quam dapibus non. Integer eu felis quam. Quisque scelerisque a quam nec euismod. Nulla consequat nulla eget euismod venenatis. Etiam tellus erat, ultricies ut efficitur et, rhoncus a dolor.\n" + "\n" + "Quisque lacinia eleifend dui, nec bibendum quam. Sed aliquet neque placerat mauris malesuada venenatis. In ac orci feugiat, bibendum velit vitae, efficitur erat. Mauris aliquam nunc purus, vitae viverra nunc pulvinar in. Praesent sit amet commodo ante. Integer aliquet, justo quis aliquet mollis, mauris dui cursus elit, a convallis ante risus sit amet ipsum. Sed enim diam, consequat in auctor non, vestibulum ut nunc. Nam nec lectus iaculis, dapibus felis et, finibus metus. Maecenas faucibus lorem purus, eget porttitor leo eleifend condimentum. Praesent lectus lacus, sodales sit amet luctus et, interdum a nunc. Nunc condimentum faucibus lobortis.",
        "School" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla convallis risus nec risus fermentum, tempor ullamcorper orci molestie. Sed pharetra lobortis eros eu consequat. Sed elementum ipsum at sem eleifend tincidunt. Vestibulum quis nibh vitae eros molestie vulputate ac quis nulla. Cras sagittis elementum eros quis commodo. Nullam convallis sollicitudin arcu quis porttitor. Proin dapibus tempor lectus, id finibus diam suscipit a. Vestibulum ac odio risus. Proin lectus mauris, fringilla ut efficitur sit amet, mattis quis risus. Aenean pellentesque consectetur risus. Sed luctus arcu eget lectus auctor mattis.\n" + "\n" + "Cras interdum et magna eu gravida. Maecenas gravida mollis viverra. Phasellus eu ante a metus pulvinar hendrerit. Integer dapibus, libero sit amet tempor mattis, libero lorem lobortis neque, in convallis mauris felis rutrum lorem. Fusce consequat a enim sed rhoncus. Duis viverra imperdiet velit laoreet pharetra. Morbi porta odio nec tellus mollis, non porta ante mollis. Fusce feugiat porttitor gravida. Integer pulvinar lorem et pretium malesuada. Ut condimentum mauris turpis, et egestas quam dapibus non. Integer eu felis quam. Quisque scelerisque a quam nec euismod. Nulla consequat nulla eget euismod venenatis. Etiam tellus erat, ultricies ut efficitur et, rhoncus a dolor.\n" + "\n" + "Quisque lacinia eleifend dui, nec bibendum quam. Sed aliquet neque placerat mauris malesuada venenatis. In ac orci feugiat, bibendum velit vitae, efficitur erat. Mauris aliquam nunc purus, vitae viverra nunc pulvinar in. Praesent sit amet commodo ante. Integer aliquet, justo quis aliquet mollis, mauris dui cursus elit, a convallis ante risus sit amet ipsum. Sed enim diam, consequat in auctor non, vestibulum ut nunc. Nam nec lectus iaculis, dapibus felis et, finibus metus. Maecenas faucibus lorem purus, eget porttitor leo eleifend condimentum. Praesent lectus lacus, sodales sit amet luctus et, interdum a nunc. Nunc condimentum faucibus lobortis.",
        "Workplace" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla convallis risus nec risus fermentum, tempor ullamcorper orci molestie. Sed pharetra lobortis eros eu consequat. Sed elementum ipsum at sem eleifend tincidunt. Vestibulum quis nibh vitae eros molestie vulputate ac quis nulla. Cras sagittis elementum eros quis commodo. Nullam convallis sollicitudin arcu quis porttitor. Proin dapibus tempor lectus, id finibus diam suscipit a. Vestibulum ac odio risus. Proin lectus mauris, fringilla ut efficitur sit amet, mattis quis risus. Aenean pellentesque consectetur risus. Sed luctus arcu eget lectus auctor mattis.\n" + "\n" + "Cras interdum et magna eu gravida. Maecenas gravida mollis viverra. Phasellus eu ante a metus pulvinar hendrerit. Integer dapibus, libero sit amet tempor mattis, libero lorem lobortis neque, in convallis mauris felis rutrum lorem. Fusce consequat a enim sed rhoncus. Duis viverra imperdiet velit laoreet pharetra. Morbi porta odio nec tellus mollis, non porta ante mollis. Fusce feugiat porttitor gravida. Integer pulvinar lorem et pretium malesuada. Ut condimentum mauris turpis, et egestas quam dapibus non. Integer eu felis quam. Quisque scelerisque a quam nec euismod. Nulla consequat nulla eget euismod venenatis. Etiam tellus erat, ultricies ut efficitur et, rhoncus a dolor.\n" + "\n" + "Quisque lacinia eleifend dui, nec bibendum quam. Sed aliquet neque placerat mauris malesuada venenatis. In ac orci feugiat, bibendum velit vitae, efficitur erat. Mauris aliquam nunc purus, vitae viverra nunc pulvinar in. Praesent sit amet commodo ante. Integer aliquet, justo quis aliquet mollis, mauris dui cursus elit, a convallis ante risus sit amet ipsum. Sed enim diam, consequat in auctor non, vestibulum ut nunc. Nam nec lectus iaculis, dapibus felis et, finibus metus. Maecenas faucibus lorem purus, eget porttitor leo eleifend condimentum. Praesent lectus lacus, sodales sit amet luctus et, interdum a nunc. Nunc condimentum faucibus lobortis.",
        "Native Words and Idioms" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla convallis risus nec risus fermentum, tempor ullamcorper orci molestie. Sed pharetra lobortis eros eu consequat. Sed elementum ipsum at sem eleifend tincidunt. Vestibulum quis nibh vitae eros molestie vulputate ac quis nulla. Cras sagittis elementum eros quis commodo. Nullam convallis sollicitudin arcu quis porttitor. Proin dapibus tempor lectus, id finibus diam suscipit a. Vestibulum ac odio risus. Proin lectus mauris, fringilla ut efficitur sit amet, mattis quis risus. Aenean pellentesque consectetur risus. Sed luctus arcu eget lectus auctor mattis.\n" + "\n" + "Cras interdum et magna eu gravida. Maecenas gravida mollis viverra. Phasellus eu ante a metus pulvinar hendrerit. Integer dapibus, libero sit amet tempor mattis, libero lorem lobortis neque, in convallis mauris felis rutrum lorem. Fusce consequat a enim sed rhoncus. Duis viverra imperdiet velit laoreet pharetra. Morbi porta odio nec tellus mollis, non porta ante mollis. Fusce feugiat porttitor gravida. Integer pulvinar lorem et pretium malesuada. Ut condimentum mauris turpis, et egestas quam dapibus non. Integer eu felis quam. Quisque scelerisque a quam nec euismod. Nulla consequat nulla eget euismod venenatis. Etiam tellus erat, ultricies ut efficitur et, rhoncus a dolor.\n" + "\n" + "Quisque lacinia eleifend dui, nec bibendum quam. Sed aliquet neque placerat mauris malesuada venenatis. In ac orci feugiat, bibendum velit vitae, efficitur erat. Mauris aliquam nunc purus, vitae viverra nunc pulvinar in. Praesent sit amet commodo ante. Integer aliquet, justo quis aliquet mollis, mauris dui cursus elit, a convallis ante risus sit amet ipsum. Sed enim diam, consequat in auctor non, vestibulum ut nunc. Nam nec lectus iaculis, dapibus felis et, finibus metus. Maecenas faucibus lorem purus, eget porttitor leo eleifend condimentum. Praesent lectus lacus, sodales sit amet luctus et, interdum a nunc. Nunc condimentum faucibus lobortis."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8))
    ) {
        Text(
            text = "Learn",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFontFamily,
            modifier = Modifier.padding(16.dp)
        )

        lessonTitles.forEach { (title, subtitle) ->
            val isCompleted = completedLessons.contains(title)
            LessonRow(
                title = title,
                subtitle = subtitle,
                isCompleted = isCompleted,
                onClick = { onLessonSelected(title to subtitle) })
        }
    }
}

@Composable
fun LessonRow(
    title: String, subtitle: String, isCompleted: Boolean, onClick: () -> Unit
) {
    val backgroundColor = if (isCompleted) Color(0xFFBBFFA0) else Color(0xFFF8F8F8)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable { onClick() }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(30.dp)
            )
        }
        Divider(color = Color.LightGray, thickness = 1.dp)
    }
}

@Composable
fun LessonDetailScreen(
    lessonTitle: String,
    lessonDescription: String,
    mediaAttachments: List<Int>,
    tryItems: List<String>,
    onTryItOut: (List<String>) -> Unit
) {
    val scrollState = rememberScrollState()
    var selectedImage by remember { mutableStateOf<Int?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp)
        ) {
            Text(
                text = lessonTitle,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = lessonDescription,
                fontSize = 16.sp,
                fontFamily = InterFontFamily,
                color = Color(0xFF666666),
                textAlign = TextAlign.Justify,
                lineHeight = 22.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (mediaAttachments.isNotEmpty()) {
                Text(
                    text = "Attachments",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val chunkedAttachments = mediaAttachments.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    chunkedAttachments.forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { imageRes ->
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(140.dp)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }) {
                                            selectedImage = imageRes
                                        }) {
                                    AsyncImage(
                                        model = imageRes,
                                        contentDescription = "Lesson Media",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Button(
                onClick = { onTryItOut(tryItems) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(150.dp)
                    .height(35.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFACC15),
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFFFACC15),
                    disabledContentColor = Color.Black
                ),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "Try It Out",
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        selectedImage?.let { imageRes ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { selectedImage = null }, contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageRes,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .fillMaxHeight(0.6f)
                )
            }
        }
    }
}

@Composable
fun DetectionScreen(
    expectedGlosses: List<String>,
    onDetectionFinished: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as ComponentActivity
    val previewView = remember { PreviewView(context) }
    
    var useFrontCamera by remember { mutableStateOf(true) }
    var currentGlossIndex by remember { mutableStateOf(0) }
    var showCheck by remember { mutableStateOf(false) }
    var recognitionError by remember { mutableStateOf<String?>(null) }
    var holisticEngine by remember { mutableStateOf<HolisticLandmarkerEngine?>(null) }
    var landmarkStreamer by remember { mutableStateOf<LandmarkStreamer?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var cameraAnalyzer by remember { mutableStateOf<CameraBitmapAnalyzer?>(null) }
    
    // Landmark overlay for drawing mesh on camera preview
    val landmarkOverlay = remember {
        HolisticLandmarkOverlay(context)
    }
    
    val currentGloss = if (currentGlossIndex < expectedGlosses.size) {
        expectedGlosses[currentGlossIndex]
    } else {
        null
    }
    val progressText = if (expectedGlosses.isNotEmpty()) {
        "${currentGlossIndex + 1} of ${expectedGlosses.size}"
    } else {
        ""
    }
    val isComplete = currentGlossIndex >= expectedGlosses.size

    // Initialize gRPC streamer and holistic engine
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // Create gRPC streamer
                val streamer = RecognitionProvider.provideStreamer(context)
                landmarkStreamer = streamer
                
                // Connect to gRPC server
                streamer.connect()
                
                // Initialize holistic landmarker engine (suspend function)
                val engine = HolisticLandmarkerEngine.create(
                    context = context,
                    onResult = { result ->
                        val timestampMs = System.currentTimeMillis()
                        val (imageWidth, imageHeight) = cameraAnalyzer?.getFullImageDimensions() ?: Pair(640, 480)
                        
                        // Check if hands or face are detected
                        val hasLeftHand = result.leftHandLandmarks() != null && result.leftHandLandmarks()!!.isNotEmpty()
                        val hasRightHand = result.rightHandLandmarks() != null && result.rightHandLandmarks()!!.isNotEmpty()
                        val hasFace = result.faceLandmarks() != null && result.faceLandmarks()!!.isNotEmpty()
                        
                        // Update landmark overlay (mesh) on main thread
                        landmarkOverlay.post {
                            if (hasLeftHand || hasRightHand || hasFace) {
                                landmarkOverlay.setHolisticLandmarks(result, imageWidth, imageHeight, timestampMs)
                            } else {
                                // Clear overlay when nothing is detected
                                landmarkOverlay.setHolisticLandmarks(null, imageWidth, imageHeight, timestampMs)
                            }
                        }
                        
                        // Send landmarks to gRPC server
                        if (streamer.isConnected()) {
                            streamer.sendLandmarks(result, timestampMs, imageWidth, imageHeight)
                        }
                    },
                    onError = { error ->
                        // Use CoroutineScope to switch to Main thread from non-suspend callback
                        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                            recognitionError = "Recognition error: ${error.message}"
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    holisticEngine = engine
                }
                
                // Listen to recognition events from gRPC
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    streamer.recognitionEvents
                        .filter { it.type == RecognitionEvent.Type.GLOSS }
                        .collectLatest { event ->
                            if (!isComplete && currentGloss != null) {
                                // Check if recognized gloss matches current expected gloss (case-insensitive)
                                val recognizedGloss = event.label.uppercase().trim()
                                val expectedGloss = currentGloss.uppercase().trim()
                                
                                if (recognizedGloss == expectedGloss) {
                                    // Correct sign detected!
                                    showCheck = true
                                    
                                    // Wait a bit then move to next gloss
                                    delay(1500)
                                    if (currentGlossIndex < expectedGlosses.size - 1) {
                                        currentGlossIndex++
                                        showCheck = false
                                    } else {
                                        // All glosses completed
                                        delay(1000)
                                        onDetectionFinished()
                                    }
                                }
                            }
                        }
                }
                
                // Monitor connection state
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    streamer.connectionState.collectLatest { state ->
                        isConnected = state == LandmarkStreamer.ConnectionState.CONNECTED
                        if (state == LandmarkStreamer.ConnectionState.ERROR) {
                            recognitionError = "Connection error. Please check if gRPC server is running."
                        }
                    }
                }
            } catch (e: Exception) {
                recognitionError = "Failed to initialize: ${e.message}"
            }
        }
    }

    // Setup camera with holistic landmarker
    LaunchedEffect(useFrontCamera, holisticEngine) {
        if (holisticEngine == null) return@LaunchedEffect
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraPreview = androidx.camera.core.Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val cameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

                // Setup ImageAnalysis with CameraBitmapAnalyzer (same as TranslationActivity)
                val analyzer = CameraBitmapAnalyzer(
                    onFrameProcessed = { bitmap, timestamp ->
                        holisticEngine?.detectAsync(bitmap, timestamp)
                    }
                )
                cameraAnalyzer = analyzer
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    cameraPreview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
                recognitionError = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Cleanup on dispose
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            holisticEngine?.close()
            landmarkStreamer?.stopStreaming()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
        
        // Landmark overlay (mesh) on top of camera preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { landmarkOverlay }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = { useFrontCamera = !useFrontCamera },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Camera,
                    contentDescription = "Flip Camera",
                    tint = Color(0xFFFACC15)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isComplete) {
                    "All signs completed!"
                } else {
                    "Sign: ${currentGloss ?: "Loading..."}"
                },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                fontFamily = InterFontFamily
            )
            
            if (expectedGlosses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Progress: $progressText",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = if (isComplete) {
                        "Great job! You've completed all signs."
                    } else {
                        "Sign the word shown above. The prompt will move on once you perform it correctly."
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    fontFamily = InterFontFamily
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (currentGloss != null) {
                        Text(
                            text = currentGloss,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            color = Color.Black,
                            fontFamily = InterFontFamily
                        )
                        if (showCheck) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Confirm",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else if (isComplete) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Complete",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                
                if (!isConnected) {
                    Text(
                        text = "Connecting to recognition server...",
                        fontSize = 12.sp,
                        color = Color(0xFF666666),
                        fontFamily = InterFontFamily
                    )
                } else if (recognitionError != null) {
                    Text(
                        text = recognitionError ?: "",
                        fontSize = 12.sp,
                        color = Color(0xFFE53935),
                        fontFamily = InterFontFamily,
                        maxLines = 2
                    )
                }
            }
        }
    }
}


@Composable
fun LessonCompletionScreen(onNextCourse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = R.drawable.check_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "You’re doing great!",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFontFamily,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "You’ve successfully completed this step.\nKeep up the good work — your progress is impressive.",
            fontSize = 14.sp,
            fontFamily = InterFontFamily,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onNextCourse,
            modifier = Modifier
                .width(150.dp)
                .height(35.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFACC15),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFFFACC15),
                disabledContentColor = Color.Black
            ),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                "Next Course",
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(35.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewLearnApp() {
    LearnApp()
}
