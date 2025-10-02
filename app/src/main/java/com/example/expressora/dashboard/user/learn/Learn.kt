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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.delay

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
                            lessonTitle = title, lessonDescription = description, onTryItOut = {
                                if (!completedLessons.contains(title)) completedLessons.add(title)
                                navController.navigate("detection")
                            })
                    }
                }

                composable("detection") {
                    currentScreen = "detection"
                    DetectionScreen(
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

// ==================== Original Composables ====================

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
    lessonTitle: String, lessonDescription: String, onTryItOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8))
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = lessonTitle,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = lessonDescription,
                fontSize = 16.sp,
                fontFamily = InterFontFamily,
                color = Color(0xFF666666),
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Justify
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onTryItOut,
            modifier = Modifier
                .width(150.dp)
                .height(35.dp)
                .align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFACC15)),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = "Try It Out",
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFontFamily
            )
        }
    }
}

@Composable
fun DetectionScreen(
    onDetectionFinished: () -> Unit
) {
    val context = LocalContext.current
    var showCheck by remember { mutableStateOf(false) }
    var autoFinishTriggered by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }

    val lifecycleOwner = context as ComponentActivity
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(useFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraPreview = androidx.camera.core.Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val cameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, cameraPreview)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(Unit) {
        delay(3000)
        showCheck = true
        delay(3000)
        if (!autoFinishTriggered) {
            autoFinishTriggered = true
            onDetectionFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = { useFrontCamera = !useFrontCamera }, modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Camera,
                    contentDescription = "Flip Camera",
                    tint = Color(0xFFFACC15)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Put your hands in front of the camera",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
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
                    text = "The prompt will move on to the next step once you perform the sign language correctly.",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Justify
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "A",
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        color = Color.Black
                    )
                    if (showCheck) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Confirm",
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
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
            text = "You’ve successfully completed this step. Keep up the good work — your progress is impressive.",
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFACC15)),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                "Next Course",
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.width(8.dp))
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
