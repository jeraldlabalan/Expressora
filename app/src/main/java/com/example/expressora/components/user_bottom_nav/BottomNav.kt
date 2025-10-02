package com.example.expressora.components.user_bottom_nav

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.dashboard.user.translation.TranslationActivity
import com.example.expressora.ui.theme.InterFontFamily

@Composable
fun BottomNav(
    modifier: Modifier = Modifier,
    onLearnClick: () -> Unit,
    onCameraClick: () -> Unit,
    onQuizClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                Color(0xFFFDE58A), shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp)
            )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onLearnClick() }) {
                Image(
                    painter = painterResource(id = R.drawable.book_open),
                    contentDescription = "Learn",
                    modifier = Modifier.size(30.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                )
                Text(
                    text = "Learn",
                    fontSize = 14.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }


            Spacer(modifier = Modifier.width(72.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onQuizClick() }) {
                Image(
                    painter = painterResource(id = R.drawable.star),
                    contentDescription = "Quiz",
                    modifier = Modifier.size(30.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                )
                Text(
                    text = "Quiz",
                    fontSize = 14.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
        }


        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-30).dp)
                .size(75.dp)
                .clip(CircleShape)
                .background(Color(0xFFF8F8F8)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xFFFDE58A),
                                0.999f to Color(0xFFFACC15),
                            )
                        )
                    )

                    .clickable { onCameraClick() }, contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.camera),
                    contentDescription = "Camera",
                    modifier = Modifier.size(37.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BottomNavBarPreview() {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        BottomNav(
            modifier = Modifier.align(Alignment.BottomCenter),
            onLearnClick = {
                context.startActivity(Intent(context, LearnActivity::class.java))
            },
            onCameraClick = {
                context.startActivity(Intent(context, TranslationActivity::class.java))
            },
            onQuizClick = {
                context.startActivity(Intent(context, QuizActivity::class.java))
            })
    }
}