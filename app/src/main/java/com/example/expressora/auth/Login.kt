package com.example.expressora.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.backend.AuthRepository
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.ui.theme.ExpressoraTheme
import com.example.expressora.ui.theme.InterFontFamily
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkUserAndRedirect()
    }

    private fun checkUserAndRedirect() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            redirectToDashboard(currentUser.uid)
        } else {
            setContent {
                ExpressoraTheme { LoginScreen() }
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
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get user info", Toast.LENGTH_LONG).show()
            setContent { ExpressoraTheme { LoginScreen() } }
        }
    }
}

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val repo = remember { AuthRepository() }
    val scope = rememberCoroutineScope()
    val textColor = Color.Black

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFFACC15), Color(0xFFF8F8F8)), startY = 0f, endY = 1000f
    )

    val customSelectionColors = TextSelectionColors(
        handleColor = Color(0xFFFACC15), backgroundColor = Color(0x33FACC15)
    )

    CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = gradient),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "WELCOME TO",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontFamily = InterFontFamily,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Image(
                    painter = painterResource(id = R.drawable.expressora_logo),
                    contentDescription = "Expressora Logo",
                    modifier = Modifier.size(100.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "EXPRESSORA",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontFamily = InterFontFamily,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                TextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = {
                        Text(
                            "Email",
                            color = textColor,
                            fontFamily = InterFontFamily,
                            fontSize = 16.sp
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = InterFontFamily, fontSize = 16.sp, color = textColor
                    ),
                    modifier = Modifier.width(300.dp),
                    singleLine = true,
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

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = {
                        Text(
                            "Password",
                            color = textColor,
                            fontFamily = InterFontFamily,
                            fontSize = 16.sp
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = InterFontFamily, fontSize = 16.sp, color = textColor
                    ),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.width(300.dp),
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
                    onClick = {
                        when {
                            email.isBlank() || password.isBlank() -> {
                                Toast.makeText(
                                    context, "Please fill in all fields", Toast.LENGTH_SHORT
                                ).show()
                            }

                            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                                Toast.makeText(context, "Invalid email format", Toast.LENGTH_SHORT)
                                    .show()
                            }

                            else -> {
                                scope.launch {
                                    isLoading = true
                                    val (success, role, errorCode) = repo.loginUser(
                                        email.trim(), password.trim()
                                    )
                                    isLoading = false

                                    if (success) {
                                        Toast.makeText(
                                            context, "Login successful!", Toast.LENGTH_SHORT
                                        ).show()
                                        val intent = when (role) {
                                            "admin" -> Intent(
                                                context,
                                                CommunitySpaceManagementActivity::class.java
                                            )

                                            else -> Intent(
                                                context, CommunitySpaceActivity::class.java
                                            )
                                        }
                                        intent.flags =
                                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        context.startActivity(intent)
                                    } else {
                                        val message = when (errorCode) {
                                            "INVALID_PASSWORD" -> "Incorrect password"
                                            "USER_NOT_FOUND" -> "Account not found"
                                            else -> "Login failed. Try again."
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .width(150.dp)
                        .height(35.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFACC15), contentColor = textColor
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = textColor, modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "Log In",
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = InterFontFamily,
                            color = textColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val registerText = buildAnnotatedString {
                    append("Don’t have an account?\n")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Register Now") }
                }

                ClickableText(
                    text = registerText, style = TextStyle(
                        fontSize = 14.sp,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        fontFamily = InterFontFamily
                    ), onClick = { offset ->
                        val registerPart = "Register Now"
                        val startIndex = registerText.indexOf(registerPart)
                        val endIndex = startIndex + registerPart.length
                        if (offset in startIndex until endIndex) {
                            context.startActivity(Intent(context, RegisterActivity::class.java))
                        }
                    })

                Spacer(modifier = Modifier.height(32.dp))

                val resetText = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Forgot Password") }
                }

                ClickableText(
                    text = resetText, style = TextStyle(
                        fontSize = 14.sp,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        fontFamily = InterFontFamily
                    ), onClick = {
                        context.startActivity(Intent(context, ResetPasswordActivity::class.java))
                    })
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginPreview() {
    ExpressoraTheme { LoginScreen() }
}
