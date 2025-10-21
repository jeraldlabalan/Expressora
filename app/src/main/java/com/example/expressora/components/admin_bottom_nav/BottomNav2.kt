package com.example.expressora.components.admin_bottom_nav

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.dashboard.admin.analytics.AnalyticsDashboardActivity
import com.example.expressora.dashboard.admin.learningmanagement.LearningManagementActivity
import com.example.expressora.dashboard.admin.quizmanagement.QuizManagementActivity
import com.example.expressora.ui.theme.InterFontFamily

@Composable
fun BottomNav2(
    modifier: Modifier = Modifier,
    onLearnClick: () -> Unit,
    onAnalyticsClick: () -> Unit,
    onQuizClick: () -> Unit
) {
    val navItemSize = 70.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(Color(0xFFFDE58A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(navItemSize)
                    .clip(CircleShape)
                    .clickable { onLearnClick() }) {
                Image(
                    painter = painterResource(id = R.drawable.book_open),
                    contentDescription = "Learn",
                    modifier = Modifier.size(30.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                )
                Text(
                    text = "Learn",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(navItemSize)
                    .clip(CircleShape)
                    .clickable { onAnalyticsClick() }) {
                Image(
                    painter = painterResource(id = R.drawable.analytics),
                    contentDescription = "Analytics",
                    modifier = Modifier.size(30.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                )
                Text(
                    text = "Metrics",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(navItemSize)
                    .clip(CircleShape)
                    .clickable { onQuizClick() }) {
                Image(
                    painter = painterResource(id = R.drawable.star),
                    contentDescription = "Quiz",
                    modifier = Modifier.size(30.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                )
                Text(
                    text = "Quiz",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BottomNav2BarPreview() {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        BottomNav2(modifier = Modifier.align(Alignment.BottomCenter), onLearnClick = {
            context.startActivity(Intent(context, LearningManagementActivity::class.java))
        }, onAnalyticsClick = {
            context.startActivity(Intent(context, AnalyticsDashboardActivity::class.java))
        }, onQuizClick = {
            context.startActivity(Intent(context, QuizManagementActivity::class.java))
        })
    }
}
