package com.example.expressora.dashboard.admin.analytics

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.components.admin_bottom_nav.BottomNav2
import com.example.expressora.components.top_nav.TopNav
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.admin.learningmanagement.LearningManagementActivity
import com.example.expressora.dashboard.admin.notification.NotificationActivity
import com.example.expressora.dashboard.admin.quizmanagement.QuizManagementActivity
import com.example.expressora.dashboard.admin.settings.AdminSettingsActivity
import com.example.expressora.ui.theme.InterFontFamily
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

val MainColor = Color(0xFFFACC15)

data class DashboardData(
    val lessons: Int,
    val quizzes: Int,
    val learners: Int,
    val lessonViews: List<Pair<String, Int>>,
    val completionRate: Float,
    val mostViewedLesson: String,
    val leastViewedLesson: String,
    val quizCorrect: Int,
    val quizWrong: Int,
    val mostAttemptedQuiz: String,
    val difficultyDistribution: Map<String, Int>,
    val perDifficultyCorrect: Map<String, Int>,
    val perDifficultyWrong: Map<String, Int>
)

class AnalyticsDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sampleData = DashboardData(
            lessons = 12,
            quizzes = 8,
            learners = 90,
            lessonViews = listOf("Alphabets" to 50, "Conversations" to 30),
            completionRate = 85f,
            mostViewedLesson = "Alphabets",
            leastViewedLesson = "Conversations",
            quizCorrect = 80,
            quizWrong = 20,
            mostAttemptedQuiz = "Easy",
            difficultyDistribution = mapOf(
                "Easy" to 80, "Medium" to 50, "Difficult" to 30, "Pro" to 20
            ),
            perDifficultyCorrect = mapOf(
                "Easy" to 80, "Medium" to 50, "Difficult" to 30, "Pro" to 20
            ),
            perDifficultyWrong = mapOf(
                "Easy" to 20, "Medium" to 50, "Difficult" to 70, "Pro" to 80
            )
        )

        setContent {
            val context = LocalContext.current
            val bgColor = Color(0xFFF8F8F8)

            Scaffold(topBar = {
                TopNav(notificationCount = 2, onProfileClick = {
                    context.startActivity(Intent(context, AdminSettingsActivity::class.java))
                }, onTranslateClick = {
                    context.startActivity(
                        Intent(
                            context, CommunitySpaceManagementActivity::class.java
                        )
                    )
                }, onNotificationClick = {
                    context.startActivity(Intent(context, NotificationActivity::class.java))
                })
            }, bottomBar = {
                BottomNav2(onLearnClick = {
                    context.startActivity(Intent(context, LearningManagementActivity::class.java))
                }, onAnalyticsClick = { /* already here */ }, onQuizClick = {
                    context.startActivity(Intent(context, QuizManagementActivity::class.java))
                })
            }) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                        .verticalScroll(rememberScrollState())
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    ModernDashboard(sampleData)
                }
            }
        }
    }
}

@SuppressLint("ContextCastToActivity")
@Composable
fun ModernDashboard(data: DashboardData, isPdfExport: Boolean = false) {
    val context = LocalContext.current
    var selectedDifficulty by remember { mutableStateOf(data.mostAttemptedQuiz) }
    var selectedDifficultyProgress by remember { mutableStateOf(data.mostAttemptedQuiz) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Dashboard",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = InterFontFamily,
            color = Color(0xFF1E1E1E)
        )
        Text(
            "Performance Overview",
            fontSize = 14.sp,
            fontFamily = InterFontFamily,
            color = Color(0xFF555555)
        )

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard("Lessons", data.lessons.toString(), Icons.Default.LibraryBooks)
            MetricCard("Quizzes", data.quizzes.toString(), Icons.Default.Quiz)
            MetricCard("Learners", data.learners.toString(), Icons.Default.People)
        }

        Text(
            "Lesson Overview",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFontFamily,
            color = Color(0xFF1E1E1E)
        )

        val lessonChartData = data.lessonViews.toMutableList()
        lessonChartData.add("Completion Rate" to data.completionRate.roundToInt())
        GradientBarChart(lessonChartData, isPdfExport = isPdfExport)

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard("Most Viewed", data.mostViewedLesson, Modifier.weight(1f))
            InfoCard("Least Viewed", data.leastViewedLesson, Modifier.weight(1f))
            InfoCard("Completion Rate", "${data.completionRate}%", Modifier.weight(1f))
        }

        Text(
            "Quiz Overview",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFontFamily,
            color = Color(0xFF1E1E1E)
        )

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PieChartWithLegendInteractive(
                dataCorrect = data.perDifficultyCorrect,
                dataWrong = data.perDifficultyWrong,
                selectedDifficulty = selectedDifficulty,
                modifier = Modifier.weight(1f),
                isPdfExport = isPdfExport
            )
            MostAttemptedQuizCardInteractive(
                data = data,
                selectedDifficultyProgress = selectedDifficultyProgress,
                onSelectDifficulty = {
                    selectedDifficulty = it
                    selectedDifficultyProgress = it
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (!isPdfExport) {
            val activity = LocalContext.current as ComponentActivity

            Box(
                modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        try {
                            exportDashboardCSV(context, data)
                            exportDashboardPDF(context, data, activity)
                            Toast.makeText(
                                context, "Report downloaded as CSV and PDF", Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context, "Failed to export report: ${e.message}", Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .width(150.dp)
                        .height(35.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MainColor, contentColor = Color.Black
                    )
                ) {
                    Text(
                        "Export Report",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.MetricCard(title: String, value: String, icon: ImageVector) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White), contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = Color.Black,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                fontFamily = InterFontFamily,
                color = Color(0xFF111111)
            )
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = InterFontFamily,
                color = Color(0xFF555555)
            )
        }
    }
}

@Composable
fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, fontSize = 14.sp, fontFamily = InterFontFamily, color = Color(0xFF888888))
            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = InterFontFamily,
                color = Color(0xFF111111)
            )
        }
    }
}

@Composable
fun GradientBarChart(
    data: List<Pair<String, Int>>, modifier: Modifier = Modifier, isPdfExport: Boolean = false
) {
    val density = LocalDensity.current
    val colors = listOf(Color(0xFF800000), Color(0xFFFFC107), Color(0xFF0D47A1))

    val animatedValues = if (!isPdfExport) {
        data.map { remember { Animatable(0f) } }
    } else {
        data.map { Animatable(it.second.toFloat()) }
    }

    if (!isPdfExport) {
        LaunchedEffect(Unit) {
            animatedValues.forEachIndexed { index, anim ->
                anim.animateTo(
                    data[index].second.toFloat(),
                    animationSpec = tween(800, delayMillis = index * 200)
                )
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val barCount = data.size
        val barWidth = size.width / (barCount * 2)
        val maxVal = (data.maxOfOrNull { it.second }?.toFloat() ?: 100f).coerceAtLeast(100f)

        val gridLines = 4
        for (i in 0..gridLines) {
            val y = size.height - (size.height / gridLines) * i
            drawLine(Color.LightGray.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1.2f)
            drawContext.canvas.nativeCanvas.drawText(
                ((i * maxVal / gridLines).roundToInt()).toString(),
                0f,
                y + 4f,
                android.graphics.Paint().apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                    textSize = with(density) { 12.sp.toPx() }
                    color = android.graphics.Color.BLACK
                })
        }

        animatedValues.forEachIndexed { index, anim ->
            val value = anim.value
            val centerX = (size.width / barCount) * (index + 0.5f)
            val topLeftX = centerX - barWidth / 2
            val barHeight = (value / maxVal) * size.height
            val topLeftY = size.height - barHeight

            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        colors[index % colors.size], colors[index % colors.size]
                    )
                ),
                topLeft = Offset(topLeftX, topLeftY),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(12f, 12f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                "${value.roundToInt()}%",
                centerX,
                topLeftY + barHeight / 2 + with(density) { 6.sp.toPx() },
                android.graphics.Paint().apply {
                    textAlign = android.graphics.Paint.Align.CENTER
                    color = android.graphics.Color.WHITE
                    textSize = with(density) { 14.sp.toPx() }
                    isFakeBoldText = true
                })
        }
    }
}

@Composable
fun PieChartWithLegendInteractive(
    dataCorrect: Map<String, Int>,
    dataWrong: Map<String, Int>,
    selectedDifficulty: String,
    modifier: Modifier = Modifier,
    isPdfExport: Boolean = false
) {
    val density = LocalDensity.current
    val colors = listOf(Color(0xFF388E3C), Color(0xFFD32F2F))

    val correct = dataCorrect[selectedDifficulty]?.toFloat() ?: 0f
    val wrong = dataWrong[selectedDifficulty]?.toFloat() ?: 0f

    val animatedCorrect = remember { Animatable(0f) }
    val animatedWrong = remember { Animatable(0f) }

    if (!isPdfExport) {
        LaunchedEffect(selectedDifficulty) {
            animatedCorrect.animateTo(correct, animationSpec = tween(800))
            animatedWrong.animateTo(wrong, animationSpec = tween(800))
        }
    }

    val displayCorrect = if (isPdfExport) correct else animatedCorrect.value
    val displayWrong = if (isPdfExport) wrong else animatedWrong.value

    val total = displayCorrect + displayWrong

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(modifier = Modifier.size(160.dp)) {
            var startAngle = -90f
            val outerRadius = size.minDimension / 2
            val values = listOf(displayCorrect, displayWrong)
            values.forEachIndexed { index, value ->
                val sweep = if (total == 0f) 0f else (value / total) * 360f
                drawArc(
                    colors[index], startAngle = startAngle, sweepAngle = sweep, useCenter = true
                )

                val angleRad = Math.toRadians((startAngle + sweep / 2).toDouble())
                val textRadius = outerRadius / 2
                val x = (size.width / 2 + textRadius * cos(angleRad)).toFloat()
                val y = (size.height / 2 + textRadius * sin(angleRad)).toFloat()
                val percent = if (total == 0f) 0 else ((value / total) * 100).roundToInt()
                drawContext.canvas.nativeCanvas.drawText(
                    "$percent%", x, y, android.graphics.Paint().apply {
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = with(density) { 14.sp.toPx() }
                        color = android.graphics.Color.WHITE
                        isFakeBoldText = true
                    })

                startAngle += sweep
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Correct" to colors[0], "Wrong" to colors[1]).forEach { (label, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label, fontSize = 14.sp, fontFamily = InterFontFamily, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun MostAttemptedQuizCardInteractive(
    data: DashboardData,
    selectedDifficultyProgress: String,
    onSelectDifficulty: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        Column {
            Text(
                "Most Attempted Level",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = InterFontFamily,
                color = Color(0xFF555555)
            )
            Text(
                data.mostAttemptedQuiz,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily,
                color = Color(0xFF111111)
            )
            Spacer(modifier = Modifier.height(12.dp))
            data.difficultyDistribution.forEach { (level, percent) ->
                Column(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSelectDifficulty(level)
                        }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            level,
                            fontSize = 14.sp,
                            fontFamily = InterFontFamily,
                            color = Color(0xFF555555)
                        )
                        Text(
                            "$percent%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = InterFontFamily,
                            color = Color(0xFF111111)
                        )
                    }
                    LinearProgressIndicator(
                        progress = percent / 100f,
                        color = if (level == selectedDifficultyProgress) MainColor else Color(
                            0xFF111111
                        ),
                        trackColor = Color.LightGray.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

fun exportDashboardCSV(context: Context, data: DashboardData) {
    try {
        val csv = buildString {
            append("Metric,Value\n")
            append("Lessons,${data.lessons}\n")
            append("Quizzes,${data.quizzes}\n")
            append("Learners,${data.learners}\n")
            append("Completion Rate,${data.completionRate}\n")
            append("Most Viewed Lesson,${data.mostViewedLesson}\n")
            append("Least Viewed Lesson,${data.leastViewedLesson}\n")
            append("Quiz Correct,${data.quizCorrect}\n")
            append("Quiz Wrong,${data.quizWrong}\n")
            append("\nLesson Views\nLesson,Views\n")
            data.lessonViews.forEach { append("${it.first},${it.second}\n") }
            append("\nQuiz Difficulty Distribution\nDifficulty,Attempts,Correct,Wrong\n")
            data.difficultyDistribution.forEach { (level, percent) ->
                append("$level,$percent,${data.perDifficultyCorrect[level] ?: 0},${data.perDifficultyWrong[level] ?: 0}\n")
            }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "dashboard_$timestamp.csv"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } else {
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, fileName)
            FileOutputStream(file).use { it.write(csv.toByteArray()) }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun exportDashboardPDF(context: Context, data: DashboardData, activity: ComponentActivity) {
    try {
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        activity.addContentView(container, container.layoutParams)

        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8F8F8))
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    ModernDashboard(data, isPdfExport = true)
                }
            }
        }
        container.addView(composeView)

        composeView.post {
            val widthSpec =
                View.MeasureSpec.makeMeasureSpec(container.width.takeIf { it > 0 } ?: 1080,
                    View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            composeView.measure(widthSpec, heightSpec)
            composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

            val bitmap = Bitmap.createBitmap(
                composeView.measuredWidth, composeView.measuredHeight, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            composeView.draw(canvas)

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDocument.finishPage(page)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "dashboard_$timestamp.pdf"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { pdfDocument.writeTo(it) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                val downloads =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloads, fileName)
                FileOutputStream(file).use { pdfDocument.writeTo(it) }
            }

            pdfDocument.close()
            (container.parent as? ViewGroup)?.removeView(container)
        }
    } catch (_: Exception) {
    }
}
