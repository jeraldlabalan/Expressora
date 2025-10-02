package com.example.expressora.dashboard.user.quiz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.expressora.R
import com.example.expressora.components.top_nav3.TopNav3
import com.example.expressora.components.user_bottom_nav.BottomNav
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.translation.TranslationActivity
import com.example.expressora.ui.theme.InterFontFamily
import kotlinx.coroutines.delay

class QuizActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QuizApp() }
    }
}

@Composable
fun QuizApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val completedDifficulties = remember { mutableStateListOf<String>() }
    var selectedDifficulty by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopNav3(onTranslateClick = {
                context.startActivity(Intent(context, CommunitySpaceActivity::class.java))
            })
        }, bottomBar = {
            BottomNav(onLearnClick = {
                context.startActivity(
                    Intent(
                        context, LearnActivity::class.java
                    )
                )
            }, onCameraClick = {
                context.startActivity(
                    Intent(
                        context, TranslationActivity::class.java
                    )
                )
            }, onQuizClick = { /* already in quiz */ })
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            NavHost(navController = navController, startDestination = "difficulty") {

                composable("difficulty") {
                    QuizDifficultyScreen(
                        selectedDifficulty = selectedDifficulty,
                        completedDifficulties = completedDifficulties,
                        onDifficultySelected = { difficulty ->
                            selectedDifficulty = difficulty
                            navController.navigate("question")
                        })
                }

                composable("question") {
                    QuizQuestionScreen(
                        difficulty = selectedDifficulty, onComplete = {
                            if (selectedDifficulty.isNotEmpty() && !completedDifficulties.contains(
                                    selectedDifficulty
                                )
                            ) {
                                completedDifficulties.add(selectedDifficulty)
                            }
                            navController.navigate("completion")
                        })
                }

                composable("completion") {
                    QuizCompletionScreen(
                        onNextCourse = {
                            navController.popBackStack(
                                "difficulty", inclusive = false
                            )
                        })
                }
            }
        }
    }
}

@Composable
fun QuizDifficultyScreen(
    selectedDifficulty: String,
    completedDifficulties: List<String>,
    onDifficultySelected: (String) -> Unit
) {
    val difficulties = listOf("Easy", "Medium", "Difficult", "Pro")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8))
    ) {
        Text(
            text = "Quiz",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFontFamily,
            modifier = Modifier.padding(16.dp)
        )

        difficulties.forEach { difficulty ->
            val isCompleted = completedDifficulties.contains(difficulty)
            DifficultyRow(
                label = difficulty,
                isCompleted = isCompleted,
                onClick = { onDifficultySelected(difficulty) })
        }
    }
}

@Composable
fun DifficultyRow(label: String, isCompleted: Boolean, onClick: () -> Unit) {
    val bg = if (isCompleted) Color(0xFFBBFFA0) else Color(0xFFF8F8F8)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onClick() }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFontFamily,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Black
            )
        }
        Divider(color = Color.LightGray, thickness = 1.dp)
    }
}

@Composable
fun QuizQuestionScreen(
    difficulty: String, onComplete: () -> Unit
) {
    var currentQuestionIndex by remember { mutableStateOf(0) }
    val totalQuestions = 10
    var selectedAnswer by remember { mutableStateOf<String?>(null) }
    val answers = listOf("Hello", "M", "A", "Good morning!")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = difficulty,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${currentQuestionIndex + 1}/$totalQuestions",
                fontSize = 16.sp,
                fontFamily = InterFontFamily,
                color = Color.Black
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "What sign is this?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = InterFontFamily,
                    textAlign = TextAlign.Center,
                    color = Color.Black
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .background(Color.Transparent, shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = R.drawable.camera_preview,
                        contentDescription = "Quiz Sign",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    answers.chunked(2).forEach { rowAnswers ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowAnswers.forEach { answer ->
                                val isSelected = selectedAnswer == answer
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .background(
                                            if (isSelected) Color(0xFFBBFFA0) else Color(0xFFFDE58A),
                                            shape = MaterialTheme.shapes.medium
                                        )
                                        .clickable { selectedAnswer = answer },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = answer,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = InterFontFamily
                                    )
                                }

                                if (isSelected) {
                                    LaunchedEffect(answer) {
                                        delay(400)
                                        selectedAnswer = null
                                        if (currentQuestionIndex < totalQuestions - 1) {
                                            currentQuestionIndex++
                                        } else {
                                            onComplete()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuizCompletionScreen(
    onNextCourse: () -> Unit
) {
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
fun PreviewQuizApp() {
    QuizApp()
}
