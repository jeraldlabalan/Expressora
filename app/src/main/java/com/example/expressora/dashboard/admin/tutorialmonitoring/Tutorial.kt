package com.example.expressora.dashboard.admin.tutorialmonitoring

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.components.admin_bottom_nav.BottomNav2
import com.example.expressora.components.admin_top_nav2.TopTabNav_2
import com.example.expressora.components.top_nav.TopNav
import com.example.expressora.dashboard.admin.analytics.AnalyticsDashboardActivity
import com.example.expressora.dashboard.admin.learningmanagement.LearningManagementActivity
import com.example.expressora.dashboard.admin.notification.NotificationActivity
import com.example.expressora.dashboard.admin.quizmanagement.QuizManagementActivity
import com.example.expressora.dashboard.admin.settings.AdminSettingsActivity
import com.example.expressora.ui.theme.InterFontFamily
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TutorialMonitoringActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TutorialScreen()
        }
    }
}

data class VideoItem(
    val id: Int,
    val title: String,
    val description: String,
    val thumbnailRes: Int,
    val videoUrl: String,
    var isWatched: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bgColor = Color(0xFFF8F8F8)

    val videos = remember {
        mutableStateListOf(
            VideoItem(
                1,
                "Basic Sign Language | Keith Dasalla",
                "Hi! This is Keith Dasalla from BSCS 4-5.",
                R.drawable.thumbnail,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                isWatched = false
            ), VideoItem(
                2,
                "Basic Sign Language 2 | Hyein Lee",
                "Learn the basics with Hyein Lee.",
                R.drawable.thumbnail,
                "https://www.youtube.com/watch?v=oHg5SJYRHA0",
                isWatched = true
            )
        )
    }

    var isRefreshing by remember { mutableStateOf(false) }
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing)
    val listState = rememberLazyListState()

    fun fetchNewVideos() {
        scope.launch {
            isRefreshing = true
            delay(1500)
            val newId = (videos.maxOfOrNull { it.id } ?: 0) + 1
            val newVideo = VideoItem(
                id = newId,
                title = "New Sign Language Video #$newId",
                description = "Newly added video tutorial.",
                thumbnailRes = R.drawable.thumbnail,
                videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                isWatched = false
            )
            videos.add(0, newVideo)
            isRefreshing = false
            listState.scrollToItem(0)
        }
    }

    Scaffold(topBar = {
        Column {
            TopNav(notificationCount = 2, onProfileClick = {
                context.startActivity(Intent(context, AdminSettingsActivity::class.java))
            }, onTranslateClick = {
                { /* already in tutorial monitoring */ }
            }, onNotificationClick = {
                context.startActivity(Intent(context, NotificationActivity::class.java))
            })
            var selectedTab by remember { mutableStateOf(1) }
            TopTabNav_2(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        }
    }, bottomBar = {
        BottomNav2(onLearnClick = {
            context.startActivity(
                Intent(
                    context, LearningManagementActivity::class.java
                )
            )
        }, onAnalyticsClick = {
            context.startActivity(
                Intent(
                    context, AnalyticsDashboardActivity::class.java
                )
            )
        }, onQuizClick = {
            context.startActivity(
                Intent(
                    context, QuizManagementActivity::class.java
                )
            )
        })
    }) { paddingValues ->
        SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = { fetchNewVideos() },
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(
                    top = 20.dp, bottom = 20.dp, start = 24.dp, end = 24.dp
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
            ) {
                itemsIndexed(videos, key = { _, item -> item.id }) { _, video ->
                    VideoTutorialCard(
                        title = video.title,
                        description = video.description,
                        thumbnailRes = video.thumbnailRes,
                        isWatched = video.isWatched,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(video.videoUrl))
                            context.startActivity(intent)
                            val idx = videos.indexOfFirst { it.id == video.id }
                            if (idx != -1 && !videos[idx].isWatched) {
                                videos[idx] = videos[idx].copy(isWatched = true)
                            }
                        },
                        onDownloadClick = {
                            Toast.makeText(
                                context, "Download started for ${video.title}", Toast.LENGTH_SHORT
                            ).show()
                        })
                }
            }
        }
    }
}

@Composable
fun VideoTutorialCard(
    title: String,
    description: String,
    thumbnailRes: Int,
    isWatched: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val cardBgColor = if (isWatched) Color.White else Color(0xFFFFF4C2)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 14.dp))
            ) {
                Image(
                    painter = painterResource(id = thumbnailRes),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0x99000000)),
                                startY = 0f,
                                endY = 300f
                            )
                        ), contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .padding(4.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = Color(0xFF666666),
                    fontFamily = InterFontFamily
                )
            }
            IconButton(
                onClick = { onDownloadClick() },
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download, contentDescription = "Download"
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TutorialScreenPreview() {
    TutorialScreen()
}