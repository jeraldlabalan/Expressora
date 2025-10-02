package com.example.expressora.components.admin_top_nav2

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.admin.tutorialmonitoring.TutorialMonitoringActivity
import com.example.expressora.ui.theme.InterFontFamily

@Composable
fun TopTabNav_2(
    selectedTab: Int, onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("Community", "ASL/FSL Video")
    val textStyle = TextStyle(
        fontSize = 16.sp, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F8F8))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, title ->
                val measuredText = textMeasurer.measure(AnnotatedString(title), style = textStyle)
                val underlineWidth = with(density) { measuredText.size.width.toDp() }

                Column(
                    modifier = Modifier
                        .clickable {
                            onTabSelected(index)

                            when (index) {
                                0 -> context.startActivity(
                                    Intent(
                                        context, CommunitySpaceManagementActivity::class.java
                                    )
                                )

                                1 -> context.startActivity(
                                    Intent(
                                        context, TutorialMonitoringActivity::class.java
                                    )
                                )
                            }
                        }
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.Black,
                        fontFamily = InterFontFamily
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (selectedTab == index) {
                        Box(
                            modifier = Modifier
                                .height(1.dp)
                                .width(underlineWidth)
                                .background(Color(0xFF0F172A), shape = RoundedCornerShape(1.dp))
                        )
                    } else {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
        Divider(color = Color(0xFFB8BCC2), thickness = 1.dp)
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewTopTabNav_2() {
    var selectedTab by remember { mutableStateOf(0) }
    TopTabNav_2(
        selectedTab = selectedTab, onTabSelected = { selectedTab = it })
}
