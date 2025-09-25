package com.example.expressora.dashboard.user.notification

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.components.bottom_nav.BottomNav
import com.example.expressora.components.top_nav.TopNav
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.dashboard.user.learn.LearnActivity
import com.example.expressora.dashboard.user.quiz.QuizActivity
import com.example.expressora.dashboard.user.settings.SettingsActivity
import com.example.expressora.dashboard.user.translation.TranslationActivity
import com.example.expressora.ui.theme.InterFontFamily
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.SwipeRefreshIndicator
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotificationScreen()
        }
    }
}

data class NotificationItem(
    val id: Int,
    val title: String,
    val message: String,
    val time: String,
    val iconRes: Int? = null,
    val isRead: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bgColor = Color(0xFFF8F8F8)
    val cardColorUnread = Color(0xFFFFF4C2)
    val cardColorRead = Color.White
    val textColor = Color.Black
    val subtitleColor = Color(0xFF666666)

    val notifications = remember {
        mutableStateListOf(
            NotificationItem(
                1,
                "Hyein Lee replied to your post",
                "Comeback when?",
                "2h ago",
                R.drawable.sample_profile2
            ),
            NotificationItem(2, "Achievement", "You completed 10 lessons!", "3d ago"),
        )
    }

    val visibilityStates = remember {
        mutableStateMapOf<Int, MutableTransitionState<Boolean>>().apply {
            notifications.forEach { notif ->
                this[notif.id] = MutableTransitionState(true)
            }
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing)
    val listState = rememberLazyListState()

    fun fetchNewNotifications() {
        scope.launch {
            isRefreshing = true
            delay(1500)
            val newId = (notifications.maxOfOrNull { it.id } ?: 0) + 1
            val newNotif = NotificationItem(
                id = newId,
                title = "New Notification #$newId",
                message = "This is a fresh notification.",
                time = "Just now"
            )
            notifications.add(0, newNotif)
            visibilityStates[newId] = MutableTransitionState(true)
            isRefreshing = false
            listState.scrollToItem(0)
        }
    }

    Scaffold(topBar = {
        TopNav(notificationCount = notifications.count { !it.isRead }, onProfileClick = {
            context.startActivity(
                Intent(
                    context, SettingsActivity::class.java
                )
            )
        }, onTranslateClick = {
            context.startActivity(
                Intent(
                    context, CommunitySpaceActivity::class.java
                )
            )
        }, onNotificationClick = {
            { /* already in notification */ }
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
        }, onQuizClick = { context.startActivity(Intent(context, QuizActivity::class.java)) })
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(paddingValues)
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notifications",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    color = textColor
                )
                if (notifications.isNotEmpty()) {
                    Text(
                        text = "Clear All",
                        fontSize = 14.sp,
                        color = subtitleColor,
                        modifier = Modifier.clickable {
                            scope.launch {
                                notifications.forEach {
                                    visibilityStates[it.id]?.targetState = false
                                    delay(100)
                                }
                                delay(400)
                                notifications.clear()
                                visibilityStates.clear()
                            }
                        })
                }
            }

            Divider(color = Color(0xFFB8BCC2), thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            SwipeRefresh(
                state = swipeRefreshState,
                onRefresh = { fetchNewNotifications() },
                indicator = { state, trigger ->
                    SwipeRefreshIndicator(
                        state = state,
                        refreshTriggerDistance = trigger,
                        backgroundColor = bgColor,
                        contentColor = Color.Black,
                        scale = true
                    )
                }) {
                if (notifications.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = "No notifications yet.",
                            color = subtitleColor,
                            fontSize = 16.sp,
                            fontFamily = InterFontFamily
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp, bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = notifications.toList(),
                            key = { _, item -> item.id }) { _, notif ->
                            val visibleState = visibilityStates.getOrPut(notif.id) {
                                MutableTransitionState(true)
                            }

                            val swipeState = rememberSwipeToDismissBoxState(
                                initialValue = SwipeToDismissBoxValue.Settled,
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        visibleState.targetState = false
                                        true
                                    } else false
                                })

                            if (!visibleState.currentState && !visibleState.targetState) {
                                LaunchedEffect(Unit) {
                                    delay(300)
                                    notifications.remove(notif)
                                    visibilityStates.remove(notif.id)
                                }
                            }

                            SwipeToDismissBox(
                                state = swipeState,
                                enableDismissFromEndToStart = true,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {}) {
                                AnimatedVisibility(
                                    visibleState = visibleState,
                                    enter = fadeIn(tween(300)) + expandVertically(),
                                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
                                ) {
                                    NotificationCard(
                                        item = notif,
                                        unreadBackground = cardColorUnread,
                                        readBackground = cardColorRead,
                                        textColor = textColor,
                                        subtitleColor = subtitleColor,
                                        onClick = {
                                            val idx =
                                                notifications.indexOfFirst { it.id == notif.id }
                                            if (idx != -1 && !notifications[idx].isRead) {
                                                notifications[idx] =
                                                    notifications[idx].copy(isRead = true)
                                            }
                                        })
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
fun NotificationCard(
    item: NotificationItem,
    unreadBackground: Color,
    readBackground: Color,
    textColor: Color,
    subtitleColor: Color,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (item.isRead) readBackground else unreadBackground,
        animationSpec = tween(300),
        label = "bgColorAnim"
    )
    val iconBackgroundColor by animateColorAsState(
        targetValue = if (item.isRead) readBackground else unreadBackground,
        animationSpec = tween(300),
        label = "iconColorAnim"
    )
    val icon = item.iconRes ?: R.drawable.expressora_logo
    val elevation = if (item.isRead) 2.dp else 6.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBackgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = icon),
                    contentDescription = "Notification Icon",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.message,
                    fontSize = 14.sp,
                    fontFamily = InterFontFamily,
                    color = subtitleColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = item.time,
                fontSize = 12.sp,
                fontFamily = InterFontFamily,
                color = subtitleColor
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationScreenPreview() {
    NotificationScreen()
}