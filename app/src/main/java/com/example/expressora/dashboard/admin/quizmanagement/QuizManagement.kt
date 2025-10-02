package com.example.expressora.dashboard.admin.quizmanagement

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.expressora.components.admin_bottom_nav.BottomNav2
import com.example.expressora.components.top_nav3.TopNav3
import com.example.expressora.dashboard.admin.analytics.AnalyticsDashboardActivity
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.admin.learningmanagement.LearningManagementActivity
import com.example.expressora.ui.theme.InterFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class Difficulty { EASY, MEDIUM, DIFFICULT, PRO }

data class Question(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "",
    var imageUri: Uri? = null,
    var correctAnswer: String = "",
    var wrongOptions: MutableList<String> = mutableListOf()
)

data class Quiz(
    val id: String = UUID.randomUUID().toString(),
    var difficulty: Difficulty = Difficulty.EASY,
    val questions: MutableList<Question> = mutableStateListOf(),
    var lastUpdated: Long = System.currentTimeMillis()
)

fun formatDate(time: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return fmt.format(Date(time))
}

private val AppBackground = Color(0xFFF8F8F8)
private val CardSurface = Color(0xFFFFFFFF)
private val MutedText = Color(0xFF666666)

class QuizManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QuizApp() }
    }
}

fun getTimeAgo(time: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - time
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        else -> "$days day${if (days > 1) "s" else ""} ago"
    }
}

fun randomPastTime(): Long {
    return System.currentTimeMillis() - (1..10).random() * 24 * 60 * 60 * 1000L
}

@Composable
fun QuizApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    val allQuizzes = remember {
        mutableStateListOf(
            Quiz(difficulty = Difficulty.EASY, lastUpdated = randomPastTime()),
            Quiz(difficulty = Difficulty.MEDIUM, lastUpdated = randomPastTime()),
            Quiz(difficulty = Difficulty.DIFFICULT, lastUpdated = randomPastTime()),
            Quiz(difficulty = Difficulty.PRO, lastUpdated = randomPastTime())
        )
    }

    Scaffold(
        topBar = {
            TopNav3(onTranslateClick = {
                context.startActivity(Intent(context, CommunitySpaceManagementActivity::class.java))
            })
        }, bottomBar = {
            BottomNav2(onLearnClick = {
                context.startActivity(
                    Intent(context, LearningManagementActivity::class.java)
                )
            }, onAnalyticsClick = {
                context.startActivity(
                    Intent(context, AnalyticsDashboardActivity::class.java)
                )
            }, onQuizClick = { /* already in quiz management */ })
        }, containerColor = AppBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .background(AppBackground)
                .fillMaxSize()
        ) {
            NavHost(navController = navController, startDestination = "list") {

                composable("list") {
                    val quizzesByDifficulty = Difficulty.values().map { diff ->
                        allQuizzes.firstOrNull { it.difficulty == diff } ?: Quiz(difficulty = diff)
                    }
                    QuizListScreen(quizzesByDifficulty = quizzesByDifficulty, onAddQuiz = { diff ->
                        val quiz =
                            allQuizzes.find { it.difficulty == diff } ?: Quiz(difficulty = diff)
                        if (!allQuizzes.contains(quiz)) allQuizzes.add(quiz)
                        navController.navigate("manage/${quiz.id}")
                    })
                }

                composable("manage/{quizId}") { backEntry ->
                    val quizId = backEntry.arguments?.getString("quizId") ?: return@composable
                    val quiz = allQuizzes.find { it.id == quizId } ?: return@composable

                    ManageQuizScreen(
                        quiz = quiz, navController = navController, context = context
                    )
                }

                composable("addQuestion/{quizId}") { backEntry ->
                    val quizId = backEntry.arguments?.getString("quizId") ?: return@composable
                    val quiz = allQuizzes.find { it.id == quizId } ?: return@composable

                    if (quiz.questions.size >= 10) {
                        LaunchedEffect(Unit) {
                            Toast.makeText(
                                context, "Maximum 10 questions allowed", Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@composable
                    }

                    val newQuestion = Question()
                    QuestionEditorScreen(
                        title = "Add Question",
                        question = newQuestion,
                        onSaveConfirmed = { question ->
                            quiz.questions.add(question)
                            quiz.lastUpdated = System.currentTimeMillis()
                            Toast.makeText(context, "Question added", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        },
                        isAdd = true
                    )
                }

                composable("editQuestion/{quizId}/{questionId}") { backEntry ->
                    val quizId = backEntry.arguments?.getString("quizId") ?: return@composable
                    val questionId =
                        backEntry.arguments?.getString("questionId") ?: return@composable
                    val quiz = allQuizzes.find { it.id == quizId } ?: return@composable
                    val question = quiz.questions.find { it.id == questionId } ?: return@composable

                    QuestionEditorScreen(
                        title = "Edit Question", question = question, onSaveConfirmed = { updated ->
                            val index = quiz.questions.indexOfFirst { it.id == updated.id }
                            if (index != -1) quiz.questions[index] = updated
                            quiz.lastUpdated = System.currentTimeMillis()
                            Toast.makeText(context, "Question updated", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        }, isAdd = false
                    )
                }
            }
        }
    }
}

@Composable
fun QuizListScreen(quizzesByDifficulty: List<Quiz>, onAddQuiz: (Difficulty) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp)
    ) {
        Text(
            "Quizzes",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = InterFontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Add quiz for a difficulty level",
            fontSize = 14.sp,
            color = MutedText,
            fontFamily = InterFontFamily
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(quizzesByDifficulty) { quiz ->
                QuizCard(quiz = quiz, onAdd = { onAddQuiz(quiz.difficulty) })
            }
        }
    }
}

@Composable
fun QuizCard(quiz: Quiz, onAdd: () -> Unit) {
    val difficultyLetter = when (quiz.difficulty) {
        Difficulty.EASY -> "E"
        Difficulty.MEDIUM -> "M"
        Difficulty.DIFFICULT -> "D"
        Difficulty.PRO -> "P"
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFDE58A)), contentAlignment = Alignment.Center
            ) {
                Text(
                    difficultyLetter,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    fontFamily = InterFontFamily
                )
            }

            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                DifficultyBadge(quiz.difficulty)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${quiz.questions.size} question(s)",
                    color = MutedText,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily
                )
                Text(
                    "Updated ${getTimeAgo(quiz.lastUpdated)}\n${formatDate(quiz.lastUpdated)}",
                    color = MutedText,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily
                )
            }

            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, tint = Color.Black, contentDescription = "Add Quiz")
            }
        }
    }
}

@Composable
fun DifficultyBadge(difficulty: Difficulty) {
    val bg = when (difficulty) {
        Difficulty.EASY -> Color(0xFFEFFDF0)
        Difficulty.MEDIUM -> Color(0xFFFFFBEA)
        Difficulty.DIFFICULT -> Color(0xFFFFF0EE)
        Difficulty.PRO -> Color(0xFFF6F2FF)
    }
    val animated by animateColorAsState(
        targetValue = bg, animationSpec = tween(300, easing = FastOutSlowInEasing)
    )
    Surface(shape = RoundedCornerShape(8.dp), color = animated) {
        Text(
            difficulty.name.lowercase().replaceFirstChar { it.uppercase() },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFontFamily
        )
    }
}

@Composable
fun ManageQuizScreen(
    quiz: Quiz, navController: NavController, context: android.content.Context
) {
    val deleteDialog = remember { mutableStateOf<Pair<Boolean, String?>>(false to null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Manage Quiz",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (quiz.questions.size < 10) navController.navigate("addQuestion/${quiz.id}")
                    else Toast.makeText(context, "Maximum 10 questions reached", Toast.LENGTH_SHORT)
                        .show()
                }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Question",
                    tint = if (quiz.questions.size < 10) Color.Black else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Questions (${quiz.questions.size})", fontFamily = InterFontFamily, color = MutedText
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(quiz.questions) { question ->
                QuestionCard(
                    question = question,
                    onEdit = { navController.navigate("editQuestion/${quiz.id}/${question.id}") },
                    onDelete = { deleteDialog.value = true to question.id })
            }
        }
    }

    if (deleteDialog.value.first) {
        ConfirmStyledDialog(
            title = "Delete Question",
            message = "Are you sure you want to delete this question?",
            confirmText = "Delete",
            confirmColor = Color.Red,
            onDismiss = { deleteDialog.value = false to null },
            onConfirm = {
                quiz.questions.removeAll { it.id == deleteDialog.value.second }
                quiz.lastUpdated = System.currentTimeMillis()
                Toast.makeText(context, "Question deleted", Toast.LENGTH_SHORT).show()
                deleteDialog.value = false to null
            })
    }
}

@SuppressLint("RememberReturnType")
@Composable
fun QuestionEditorScreen(
    title: String, question: Question, onSaveConfirmed: (Question) -> Unit, isAdd: Boolean
) {
    val context = LocalContext.current

    var questionText by rememberSaveable { mutableStateOf(question.text) }
    var correctAnswer by rememberSaveable { mutableStateOf(question.correctAnswer) }
    var wrongOptionsText by rememberSaveable { mutableStateOf(question.wrongOptions.joinToString(", ")) }
    var localImageUri by rememberSaveable { mutableStateOf(question.imageUri) }
    val saveDialogVisible = remember { mutableStateOf(false) }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            localImageUri = uri
        }

    val isSaveEnabled by remember(questionText, correctAnswer, wrongOptionsText, localImageUri) {
        derivedStateOf {
            val allFieldsFilled =
                questionText.isNotBlank() && correctAnswer.isNotBlank() && wrongOptionsText.isNotBlank() && localImageUri != null
            if (isAdd) allFieldsFilled
            else allFieldsFilled && (questionText != question.text || correctAnswer != question.correctAnswer || wrongOptionsText != question.wrongOptions.joinToString(
                ", "
            ) || localImageUri != question.imageUri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = InterFontFamily
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { if (isSaveEnabled) saveDialogVisible.value = true },
                enabled = isSaveEnabled
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Save Question",
                    tint = if (isSaveEnabled) Color.Black else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = questionText, onValueChange = { questionText = it }, label = {
                Text("Question", fontFamily = InterFontFamily, color = Color(0xFF666666))
            }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(
                cursorColor = Color.Black,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color(0xFF666666),
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = Color(0xFF666666)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF2F4F7)), contentAlignment = Alignment.Center
        ) {
            if (localImageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(localImageUri).crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Upload sign image / GIF", color = MutedText, fontFamily = InterFontFamily)
                }
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { imagePicker.launch("image/*") })
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = correctAnswer, onValueChange = { correctAnswer = it }, label = {
                Text("Correct Answer", fontFamily = InterFontFamily, color = Color(0xFF666666))
            }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(
                cursorColor = Color.Black,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color(0xFF666666),
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = Color(0xFF666666)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = wrongOptionsText,
            onValueChange = { wrongOptionsText = it },
            label = {
                Text(
                    "Wrong Options (comma separated)",
                    fontFamily = InterFontFamily,
                    color = Color(0xFF666666)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                cursorColor = Color.Black,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color(0xFF666666),
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = Color(0xFF666666)
            )
        )
    }

    if (saveDialogVisible.value) {
        ConfirmStyledDialog(
            title = "Save Question",
            message = "Do you want to save this question?",
            confirmText = "Save",
            confirmColor = Color(0xFFFACC15),
            confirmTextColor = Color.Black,
            onDismiss = { saveDialogVisible.value = false },
            onConfirm = {
                saveDialogVisible.value = false
                onSaveConfirmed(
                    question.copy(
                        text = questionText,
                        imageUri = localImageUri,
                        correctAnswer = correctAnswer.trim(),
                        wrongOptions = wrongOptionsText.split(",").map { it.trim() }
                            .filter { it.isNotEmpty() }.toMutableList()
                    )
                )
            })
    }
}

@Composable
fun ConfirmStyledDialog(
    title: String,
    message: String,
    confirmText: String,
    confirmColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmTextColor: Color = Color.White
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(message, color = MutedText)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                    ) { Text("Cancel", color = Color(0xFF666666)) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm, colors = ButtonDefaults.buttonColors(
                            containerColor = confirmColor, contentColor = confirmTextColor
                        )
                    ) { Text(confirmText) }
                }
            }
        }
    }
}

@Composable
fun QuestionCard(question: Question, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (question.imageUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(question.imageUri)
                            .crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    question.text.ifBlank { "Question Text" },
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily
                )
                Text(
                    "Correct: ${question.correctAnswer.ifBlank { "—" }}",
                    color = MutedText,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily
                )
                Text(
                    "Wrong: ${question.wrongOptions.joinToString(", ")}",
                    color = MutedText,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit, contentDescription = "Edit", tint = Color.Black
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red
                )
            }
        }
    }
}
