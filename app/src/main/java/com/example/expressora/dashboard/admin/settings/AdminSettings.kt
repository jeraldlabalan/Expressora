package com.example.expressora.dashboard.admin.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.example.expressora.R
import com.example.expressora.auth.LoginActivity
import com.example.expressora.components.admin_bottom_nav.BottomNav2
import com.example.expressora.components.top_nav.TopNav
import com.example.expressora.dashboard.admin.analytics.AnalyticsDashboardActivity
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.admin.learningmanagement.LearningManagementActivity
import com.example.expressora.dashboard.admin.notification.NotificationActivity
import com.example.expressora.dashboard.admin.quizmanagement.QuizManagementActivity
import com.example.expressora.ui.theme.InterFontFamily
import com.google.firebase.auth.FirebaseAuth

class AdminSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val customSelectionColors = TextSelectionColors(
                handleColor = Color(0xFFFACC15), backgroundColor = Color(0x33FACC15)
            )

            val navController = rememberNavController()

            CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
                NavHost(navController = navController, startDestination = "settings") {
                    composable("settings") { AdminSettingsScreen(navController) }

                    composable(
                        route = "profile/{label}", arguments = listOf(navArgument("label") {
                            defaultValue = "Personal Information"
                        })
                    ) { backStackEntry ->
                        val label =
                            backStackEntry.arguments?.getString("label") ?: "Personal Information"
                        if (label == "Account Information") {
                            AdminAccountInfoScreen(navController, label)
                        } else {
                            AdminUserProfileScreen(label)
                        }
                    }

                    composable(
                        route = "change_email/{label}",
                        arguments = listOf(navArgument("label") { defaultValue = "Change Email" })
                    ) { backStackEntry ->
                        val label = backStackEntry.arguments?.getString("label") ?: "Change Email"
                        AdminChangeEmailScreen(label)
                    }

                    composable(
                        route = "change_password/{label}", arguments = listOf(navArgument("label") {
                            defaultValue = "Change Password"
                        })
                    ) { backStackEntry ->
                        val label =
                            backStackEntry.arguments?.getString("label") ?: "Change Password"
                        AdminChangePasswordScreen(label)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminSettingsScreen(navController: NavHostController) {
    val settingsItems = listOf(
        "Personal Information", "Account Information", "Log Out"
    )
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopNav(notificationCount = 2, onProfileClick = {
                { /* already in admin settings */ }
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
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = "Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily,
                modifier = Modifier.padding(16.dp)
            )

            settingsItems.forEach { item ->
                AdminSettingsRow(
                    label = item, showArrow = item != "Log Out", onClick = {
                        when (item) {
                            "Personal Information", "Account Information" -> navController.navigate(
                                "profile/${item}"
                            )

                            "Preferences" -> navController.navigate("preferences/Preferences")


                            "Log Out" -> {
                                FirebaseAuth.getInstance().signOut()

                                val sharedPref = context.getSharedPreferences(
                                    "user_session", android.content.Context.MODE_PRIVATE
                                )
                                with(sharedPref.edit()) {
                                    clear()
                                    apply()
                                }

                                val intent = Intent(context, LoginActivity::class.java)
                                intent.flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                context.startActivity(intent)
                            }

                        }
                    })
            }
        }
    }
}

@Composable
fun AdminSettingsRow(label: String, showArrow: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(Color(0xFFF8F8F8))) {
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
            if (showArrow) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.Black
                )
            }
        }
        Divider(color = Color.LightGray, thickness = 1.dp)
    }
}

@Composable
fun AdminSettingsRowWithSubtitle(label: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(Color(0xFFF8F8F8))) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFontFamily
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = Color(0xFF666666),
                fontFamily = InterFontFamily,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Divider(color = Color.LightGray, thickness = 1.dp)
    }
}

@Composable
fun AdminAccountInfoScreen(navController: NavHostController, label: String) {
    val context = LocalContext.current
    val email = "abc@proton.me"

    Scaffold(
        topBar = {
            TopNav(notificationCount = 2, onProfileClick = {
                { /* already in admin settings */ }
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
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = label,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily,
                modifier = Modifier.padding(16.dp)
            )

            AdminSettingsRowWithSubtitle(
                label = "Email", subtitle = email, onClick = {
                    navController.navigate("change_email/Change Email")
                })

            AdminSettingsRow(
                label = "Change Password", showArrow = false, onClick = {
                    navController.navigate("change_password/Change Password")
                })
        }
    }
}

@Composable
fun AdminUserProfileScreen(label: String) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { imageUri = it } }

    Scaffold(
        topBar = {
            TopNav(
                notificationCount = 2,
                onProfileClick = { /* already in admin settings */ },
                onTranslateClick = {
                    context.startActivity(
                        Intent(context, CommunitySpaceManagementActivity::class.java)
                    )
                },
                onNotificationClick = {
                    context.startActivity(Intent(context, NotificationActivity::class.java))
                })
        }, bottomBar = {
            BottomNav2(onLearnClick = {
                context.startActivity(Intent(context, LearningManagementActivity::class.java))
            }, onAnalyticsClick = {
                context.startActivity(Intent(context, AnalyticsDashboardActivity::class.java))
            }, onQuizClick = {
                context.startActivity(Intent(context, QuizManagementActivity::class.java))
            })
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .width(300.dp)
                    .padding(top = 24.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Box(
                    modifier = Modifier.wrapContentSize(), contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.Black, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            imageUri != null -> {
                                AsyncImage(
                                    model = imageUri,
                                    contentDescription = "Profile Image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            }

                            else -> {
                                AestheticProfilePlaceholder(
                                    firstName = firstName, drawableRes = R.drawable.profile
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .offset(x = -6.dp, y = 4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFACC15))
                            .clickable { imagePickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Upload Photo",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${firstName.ifEmpty { "Your" }} ${lastName.ifEmpty { "Name" }}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    placeholder = {
                        Text("Firstname", color = Color.Black, fontFamily = InterFontFamily)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedIndicatorColor = Color.Black,
                        unfocusedIndicatorColor = Color.Black,
                        cursorColor = Color.Black,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    placeholder = {
                        Text("Lastname", color = Color.Black, fontFamily = InterFontFamily)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedIndicatorColor = Color.Black,
                        unfocusedIndicatorColor = Color.Black,
                        cursorColor = Color.Black,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        {/* Save logic */ }
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
                        text = "Save Changes",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily
                    )
                }
            }
        }
    }
}

@Composable
fun AestheticProfilePlaceholder(firstName: String, drawableRes: Int) {
    val initial = firstName.firstOrNull()?.uppercaseChar()?.toString() ?: ""

    if (firstName.isEmpty()) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = "Default Profile",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFF5F5F5), 1.0f to Color(0xFFBEBEBE)
                        )
                    ), shape = CircleShape
                ), contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                textAlign = TextAlign.Center,
                fontFamily = InterFontFamily
            )
        }
    }
}

@Composable
fun AdminChangeEmailScreen(label: String) {
    var newEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val textColor = Color.Black

    Scaffold(
        topBar = {
            TopNav(notificationCount = 2, onProfileClick = {
                { /* already in admin settings */ }
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
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(300.dp)
                    .padding(top = 24.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                TextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    placeholder = { Text("New email address", fontFamily = InterFontFamily) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedIndicatorColor = Color.Black,
                        unfocusedIndicatorColor = Color.Black,
                        cursorColor = Color.Black,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = {
                        Text("Password", color = textColor, fontFamily = InterFontFamily)
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        val image =
                            if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = image,
                                contentDescription = "Toggle Password",
                                tint = textColor
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedIndicatorColor = textColor,
                        unfocusedIndicatorColor = textColor,
                        cursorColor = textColor,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { /* Save new email logic */ },
                    modifier = Modifier
                        .width(150.dp)
                        .height(35.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFACC15), contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "Save Changes",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily
                    )
                }
            }
        }
    }
}

@Composable
fun AdminChangePasswordScreen(label: String) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var currentPasswordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val textColor = Color.Black

    Scaffold(
        topBar = {
            TopNav(notificationCount = 2, onProfileClick = {
                { /* already in admin settings */ }
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
        }, containerColor = Color(0xFFF8F8F8)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(300.dp)
                    .padding(top = 24.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFontFamily,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                TextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    placeholder = { Text("Current password", fontFamily = InterFontFamily) },
                    visualTransformation = if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        val image =
                            if (currentPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                            Icon(
                                imageVector = image,
                                contentDescription = "Toggle Password",
                                tint = textColor
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedIndicatorColor = textColor,
                        unfocusedIndicatorColor = textColor,
                        cursorColor = textColor,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    placeholder = { Text("New password", fontFamily = InterFontFamily) },
                    visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        val image =
                            if (newPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                            Icon(
                                imageVector = image,
                                contentDescription = "Toggle Password",
                                tint = textColor
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedIndicatorColor = textColor,
                        unfocusedIndicatorColor = textColor,
                        cursorColor = textColor,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    placeholder = { Text("Confirm password", fontFamily = InterFontFamily) },
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        val image =
                            if (confirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(
                                imageVector = image,
                                contentDescription = "Toggle Password",
                                tint = textColor
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedIndicatorColor = textColor,
                        unfocusedIndicatorColor = textColor,
                        cursorColor = textColor,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { /* Save new password logic */ },
                    modifier = Modifier
                        .width(150.dp)
                        .height(35.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFACC15), contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "Save Changes",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewAccountInfoScreen() {
    val navController = rememberNavController()
    AdminAccountInfoScreen(navController, "Account Information")
}

@Preview(showBackground = true)
@Composable
fun PreviewProfileScreen() {
    AdminUserProfileScreen("Personal Information")
}