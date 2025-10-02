package com.example.expressora.dashboard.admin.analytics

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    val difficultyDistribution: Map<String, Int>
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
            quizCorrect = 60,
            quizWrong = 40,
            mostAttemptedQuiz = "Easy",
            difficultyDistribution = mapOf(
                "Easy" to 40, "Medium" to 30, "Difficult" to 20, "Pro" to 10
            )
        )

        setContent {
            val context = LocalContext.current
            val bgColor = Color(0xFFF8F8F8)

            Scaffold(topBar = {
                TopNav(notificationCount = 2, onProfileClick = {
                    context.startActivity(
                        Intent(
                            context, AdminSettingsActivity::class.java
                        )
                    )
                }, onTranslateClick = {
                    context.startActivity(
                        Intent(
                            context, CommunitySpaceManagementActivity::class.java
                        )
                    )
                }, onNotificationClick = {
                    context.startActivity(
                        Intent(
                            context, NotificationActivity::class.java
                        )
                    )
                })
            }, bottomBar = {
                BottomNav2(onLearnClick = {
                    context.startActivity(
                        Intent(
                            context, LearningManagementActivity::class.java
                        )
                    )
                }, onAnalyticsClick = { { /* already in analytics dashboard */ } }, onQuizClick = {
                    context.startActivity(
                        Intent(
                            context, QuizManagementActivity::class.java
                        )
                    )
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

@Composable
fun ModernDashboard(data: DashboardData) {
    val context = LocalContext.current

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Dashboard",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = InterFontFamily,
            color = Color(0xFF1E1E1E)
        )
        Text(
            "Analytics Overview",
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
            "Lesson Analytics",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFontFamily,
            color = Color(0xFF1E1E1E)
        )
        GradientBarChart(data.lessonViews)

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard("Most Viewed", data.mostViewedLesson, Modifier.weight(1f))
            InfoCard("Least Viewed", data.leastViewedLesson, Modifier.weight(1f))
            InfoCard("Completion Rate", "${data.completionRate}%", Modifier.weight(1f))
        }

        Text(
            "Quiz Analytics",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFontFamily,
            color = Color(0xFF1E1E1E)
        )

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PieChartWithLegendAndPercent(
                data = mapOf("Correct" to data.quizCorrect, "Wrong" to data.quizWrong),
                modifier = Modifier.weight(1f)
            )
            MostAttemptedQuizCard(data, Modifier.weight(1f))
        }

        Box(
            modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    val activity = context as? ComponentActivity
                    if (activity != null) {
                        exportDashboardCSVCompat(context, data)
                        exportDashboardPDFCompose(context, data, activity)
                        Toast.makeText(
                            context, "Report downloaded as CSV and PDF", Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(context, "Unable to export report", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier
                    .width(150.dp)
                    .height(35.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFACC15), contentColor = Color.Black
                )
            ) {
                Text(
                    text = "Export Report",
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily
                )
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
                fontSize = 12.sp,
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
            Text(title, fontSize = 12.sp, fontFamily = InterFontFamily, color = Color(0xFF888888))
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
fun MostAttemptedQuizCard(data: DashboardData, modifier: Modifier = Modifier) {
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
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            level,
                            fontSize = 12.sp,
                            fontFamily = InterFontFamily,
                            color = Color(0xFF555555)
                        )
                        Text(
                            "$percent%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = InterFontFamily,
                            color = Color(0xFF111111)
                        )
                    }
                    LinearProgressIndicator(
                        progress = percent / 100f,
                        color = MainColor,
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

@Composable
fun GradientBarChart(data: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val animatedValues = data.map { animateFloatAsState(it.second.toFloat(), tween(1000)).value }
    val density = LocalDensity.current
    val colors = listOf(Color(0xFF800000), Color(0xFFFFC107))

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val padding = 0.05f * size.width
        val usableWidth = size.width - padding * 2
        val barCount = data.size
        val barSpacing = usableWidth / (barCount * 2 + 1)
        val barWidth = barSpacing
        val maxVal = 100f
        val gridLines = 4

        for (i in 0..gridLines) {
            val y = size.height - (size.height / gridLines) * i
            drawLine(
                Color.LightGray.copy(alpha = 0.5f),
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1.2f
            )
            val label = (i * 25).toString()
            drawContext.canvas.nativeCanvas.drawText(
                label, 0f, y + 4f, android.graphics.Paint().apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                    textSize = with(density) { 12.sp.toPx() }
                    color = android.graphics.Color.BLACK
                })
        }

        animatedValues.forEachIndexed { index, value ->
            val x = padding + barSpacing * (index * 2 + 1)
            val barHeight = (value / maxVal) * size.height
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        colors[index % colors.size], colors[index % colors.size]
                    )
                ),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(12f, 12f)
            )
        }
    }
}

@Composable
fun PieChartWithLegendAndPercent(data: Map<String, Int>, modifier: Modifier = Modifier) {
    val animatedValues = data.values.map { animateFloatAsState(it.toFloat(), tween(1000)).value }
    val total = animatedValues.sum()
    val density = LocalDensity.current
    val colors = listOf(Color(0xFF388E3C), Color(0xFFD32F2F))
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(modifier = Modifier.size(160.dp)) {
            var startAngle = -90f
            val outerRadius = size.minDimension / 2

            animatedValues.forEachIndexed { index, value ->
                val sweep = (value / total) * 360f
                drawArc(
                    colors[index], startAngle = startAngle, sweepAngle = sweep, useCenter = true
                )
                val angleRad = Math.toRadians((startAngle + sweep / 2).toDouble())
                val textRadius = outerRadius / 2
                val x = (size.width / 2 + textRadius * cos(angleRad)).toFloat()
                val y = (size.height / 2 + textRadius * sin(angleRad)).toFloat()
                val percent = ((value / total) * 100).roundToInt()
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
            data.keys.forEachIndexed { index, key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(colors[index], shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        key,
                        fontSize = 12.sp,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF555555)
                    )
                }
            }
        }
    }
}

fun exportDashboardCSVCompat(context: Context, data: DashboardData) {
    try {
        val csv = buildString {
            append("Metric,Value\n")
            append("Lessons,${data.lessons}\n")
            append("Quizzes,${data.quizzes}\n")
            append("Learners,${data.learners}\n")
            append("Completion Rate,${data.completionRate}\n")
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
            uri?.let {
                resolver.openOutputStream(it)?.use { out -> out.write(csv.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } else {
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, fileName)
            FileOutputStream(file).use { out -> out.write(csv.toByteArray()) }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun exportDashboardPDFCompose(
    context: Context, data: DashboardData, activity: ComponentActivity
) {
    try {
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.INVISIBLE
        }
        activity.addContentView(container, container.layoutParams)

        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setContent {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8F8F8))
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Text(
                        "Dashboard",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF1E1E1E)
                    )
                    Text(
                        "Analytics Overview",
                        fontSize = 14.sp,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF555555)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard("Lessons", data.lessons.toString(), Icons.Default.LibraryBooks)
                        MetricCard("Quizzes", data.quizzes.toString(), Icons.Default.Quiz)
                        MetricCard("Learners", data.learners.toString(), Icons.Default.People)
                    }

                    Text(
                        "Lesson Analytics",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF1E1E1E)
                    )
                    GradientBarChart(data.lessonViews)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoCard("Most Viewed", data.mostViewedLesson, Modifier.weight(1f))
                        InfoCard("Least Viewed", data.leastViewedLesson, Modifier.weight(1f))
                        InfoCard("Completion Rate", "${data.completionRate}%", Modifier.weight(1f))
                    }

                    Text(
                        "Quiz Analytics",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily,
                        color = Color(0xFF1E1E1E)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PieChartWithLegendAndPercent(
                            data = mapOf(
                                "Correct" to data.quizCorrect, "Wrong" to data.quizWrong
                            ), modifier = Modifier.weight(1f)
                        )
                        MostAttemptedQuizCard(data, Modifier.weight(1f))
                    }
                }
            }
        }

        container.addView(composeView)

        composeView.measure(View.MeasureSpec.makeMeasureSpec(container.width.takeIf { it > 0 }
            ?: 1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
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

    } catch (e: Exception) {
        e.printStackTrace()
    }
}
