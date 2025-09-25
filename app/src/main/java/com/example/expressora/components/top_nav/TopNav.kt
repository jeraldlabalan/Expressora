package com.example.expressora.components.top_nav

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.expressora.R
import com.example.expressora.dashboard.user.notification.NotificationActivity
import com.example.expressora.dashboard.user.settings.SettingsActivity

@Composable
fun TopNav(
    modifier: Modifier = Modifier,
    notificationCount: Int = 0,
    onProfileClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onNotificationClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(Color(0xFFF8F8F8))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.settings),
            contentDescription = "Settings",
            colorFilter = ColorFilter.tint(Color.Black),
            modifier = Modifier
                .size(30.dp)
                .clickable { onProfileClick() })

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFFF8F8F8))
                .padding(8.dp)
                .clickable { onTranslateClick() }, contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.expressora_logo),
                contentDescription = "Expressora Logo",
                modifier = Modifier.size(37.dp)
            )
        }

        BadgedBox(
            badge = {
                if (notificationCount > 0) {
                    Badge(
                        containerColor = Color.Black, contentColor = Color.White
                    ) {
                        Text(
                            text = notificationCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }) {
            Image(
                painter = painterResource(id = R.drawable.notification),
                contentDescription = "Notifications",
                colorFilter = ColorFilter.tint(Color.Black),
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onNotificationClick() })
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TopNavBarPreview() {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(Color(0xFFF8F8F8))
    ) {
        TopNav(
            modifier = Modifier.align(Alignment.Center),
            notificationCount = 2,
            onProfileClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            },
            onTranslateClick = {

            },
            onNotificationClick = {
                context.startActivity(Intent(context, NotificationActivity::class.java))
            })
    }
}
