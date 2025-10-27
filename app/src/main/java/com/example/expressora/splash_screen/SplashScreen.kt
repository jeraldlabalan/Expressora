package com.example.expressora.splash_screen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.auth.LoginActivity
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.ui.theme.ExpressoraTheme
import com.example.expressora.ui.theme.InterFontFamily
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay

class SplashScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            redirectToDashboard(currentUser.uid)
            finish()
            return
        }

        // Check if user has a saved session
        checkSavedSession()
    }

    private fun checkSavedSession() {
        val sharedPref = getSharedPreferences("user_session", MODE_PRIVATE)
        val userEmail = sharedPref.getString("user_email", null)
        val userRole = sharedPref.getString("user_role", null)

        if (userEmail != null && userRole != null) {
            // User has a saved session, redirect to dashboard
            val intent = when (userRole) {
                "admin" -> Intent(this, CommunitySpaceManagementActivity::class.java)
                else -> Intent(this, CommunitySpaceActivity::class.java)
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } else {
            // No saved session, show splash screen
            setContent {
                ExpressoraTheme {
                    SplashScreen {
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    private fun redirectToDashboard(uid: String) {
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
            val role = doc.getString("role") ?: "user"
            val intent = when (role) {
                "admin" -> Intent(this, CommunitySpaceManagementActivity::class.java)
                else -> Intent(this, CommunitySpaceActivity::class.java)
            }
            startActivity(intent)
            finish()
        }.addOnFailureListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(3000)
        onTimeout()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8))
    ) { innerPadding ->
        LogoWithLoadingIndicator(Modifier.padding(innerPadding))
    }
}

@Composable
fun LogoWithLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8F8F8)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.expressora_logo),
                contentDescription = "Expressora Logo",
                modifier = Modifier.size(150.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "EXPRESSORA", style = TextStyle(
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = Color.Black
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            CircularProgressIndicator(
                color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(35.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    ExpressoraTheme {
        LogoWithLoadingIndicator()
    }
}
