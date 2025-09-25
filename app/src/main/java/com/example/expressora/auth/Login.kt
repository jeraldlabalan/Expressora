package com.example.expressora.auth

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.ui.theme.ExpressoraTheme
import com.example.expressora.ui.theme.InterFontFamily

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExpressoraTheme {
                LoginScreen()
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val textColor = Color.Black

    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFFACC15), Color(0xFFF8F8F8)), startY = 0f, endY = 1000f
    )

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

            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var passwordVisible by remember { mutableStateOf(false) }


            TextField(
                value = email,
                onValueChange = { email = it },
                placeholder = {
                    Text("Email", color = textColor, fontFamily = InterFontFamily)
                },
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
                    Text("Password", color = textColor, fontFamily = InterFontFamily)
                },
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
                    val intent = Intent(context, CommunitySpaceActivity::class.java)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .width(150.dp)
                    .height(35.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFACC15), contentColor = textColor
                )
            ) {
                Text(
                    text = "Log In",
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFontFamily,
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(24.dp))


            val registerText = buildAnnotatedString {
                append("Don’t have an account?\n")
                withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append("Register Now")
                }
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
                        val intent = Intent(context, RegisterActivity::class.java)
                        context.startActivity(intent)
                    }
                })

            Spacer(modifier = Modifier.height(32.dp))


            val resetText = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append("Forgot Password")
                }
            }

            ClickableText(
                text = resetText, style = TextStyle(
                    fontSize = 14.sp,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    fontFamily = InterFontFamily
                ), onClick = {
                    val intent = Intent(context, ResetPasswordActivity::class.java)
                    context.startActivity(intent)
                })
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginPreview() {
    ExpressoraTheme {
        LoginScreen()
    }
}
