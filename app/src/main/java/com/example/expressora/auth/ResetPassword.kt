package com.example.expressora.auth

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.expressora.R
import com.example.expressora.dashboard.admin.communityspacemanagement.CommunitySpaceManagementActivity
import com.example.expressora.dashboard.user.community_space.CommunitySpaceActivity
import com.example.expressora.ui.theme.ExpressoraTheme
import com.example.expressora.ui.theme.InterFontFamily
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ResetPasswordActivity : ComponentActivity() {

    private lateinit var context: Context
    private val firestore = FirebaseFirestore.getInstance()
    private var resetEmail: String = ""
    private var resetOtp: String = ""

    private var currentStep by mutableStateOf(1)
    private var isOtpSent by mutableStateOf(false)
    private var isPasswordReset by mutableStateOf(false)

    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this
        checkUserAndRedirect()
    }

    private fun checkUserAndRedirect() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            redirectToDashboard(currentUser.uid)
        } else {
            checkSavedSession()
        }
    }

    private fun checkSavedSession() {
        val sharedPref = getSharedPreferences("user_session", MODE_PRIVATE)
        val userEmail = sharedPref.getString("user_email", null)
        val userRole = sharedPref.getString("user_role", null)

        if (userEmail != null && userRole != null) {
            val intent = when (userRole) {
                "admin" -> Intent(this, CommunitySpaceManagementActivity::class.java)
                else -> Intent(this, CommunitySpaceActivity::class.java)
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } else {
            showResetPasswordScreen()
        }
    }

    private fun showResetPasswordScreen() {
        setContent {
            ExpressoraTheme {
                ResetPasswordScreen(
                    onSendOtp = { email -> checkEmailAndSendOtp(email) },
                    onVerifyOtp = { otp ->
                        if (resetEmail.isNotBlank()) {
                            verifyOtp(otp)
                        } else {
                            Toast.makeText(
                                context, "Missing email, please re-enter.", Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onResetPassword = { newPassword, confirmPassword ->
                        resetPassword(newPassword, confirmPassword)
                    },
                    currentStep = currentStep
                )
            }
        }
    }

    private fun redirectToDashboard(uid: String) {
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
            showResetPasswordScreen()
        }
    }

    private fun checkEmailAndSendOtp(email: String) {
        if (email.isBlank()) {
            Toast.makeText(context, "Please enter your email", Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedEmail = email.trim().lowercase()
        firestore.collection("users").whereEqualTo("email", normalizedEmail).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    Toast.makeText(
                        context,
                        "Email not found. Please check your email or register first.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    resetEmail = normalizedEmail
                    sendOtp(normalizedEmail)
                }
            }.addOnFailureListener { e ->
                Toast.makeText(
                    context, "Error checking email: ${e.localizedMessage}", Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun sendOtp(email: String) {
        val LOCAL_HOST_IP = "192.168.1.9"
        val baseUrl = if (isEmulator()) "http://10.0.2.2:3000" else "http://$LOCAL_HOST_IP:3000"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply { put("email", email) }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url("$baseUrl/reset-send-otp").post(body)
                    .addHeader("Content-Type", "application/json").build()

                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(context, "OTP sent to $email", Toast.LENGTH_SHORT).show()
                        currentStep = 2
                        isOtpSent = true
                    } else {
                        Toast.makeText(
                            context, "Failed to send OTP (${response.code})", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context, "Error sending OTP: ${e.localizedMessage}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun verifyOtp(enteredOtp: String) {
        val LOCAL_HOST_IP = "192.168.1.9"
        val baseUrl = if (isEmulator()) "http://10.0.2.2:3000" else "http://$LOCAL_HOST_IP:3000"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("email", resetEmail)
                    put("otp", enteredOtp)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url("$baseUrl/reset-verify-otp").post(body)
                    .addHeader("Content-Type", "application/json").build()

                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(context, "OTP Verified", Toast.LENGTH_SHORT).show()
                        resetOtp = enteredOtp
                        currentStep = 3
                    } else {
                        Toast.makeText(
                            context, "Incorrect OTP. Please try again.", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context, "Error verifying OTP: ${e.localizedMessage}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun resetPassword(newPassword: String, confirmPassword: String) {
        if (newPassword.isBlank() || confirmPassword.isBlank()) {
            Toast.makeText(context, "Please fill both fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword != confirmPassword) {
            Toast.makeText(context, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword.length < 8) {
            Toast.makeText(
                context, "Password must be at least 8 characters long", Toast.LENGTH_LONG
            ).show()
            return
        }

        val LOCAL_HOST_IP = "192.168.1.9"
        val baseUrl = if (isEmulator()) "http://10.0.2.2:3000" else "http://$LOCAL_HOST_IP:3000"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hashedPassword =
                    MessageDigest.getInstance("SHA-256").digest(newPassword.toByteArray())
                        .joinToString("") { "%02x".format(it) }

                val json = JSONObject().apply {
                    put("email", resetEmail.lowercase())
                    put("newPassword", hashedPassword)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder().url("$baseUrl/reset-password").post(body)
                    .addHeader("Content-Type", "application/json").build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()
                Log.d("ResetPassword", "Response: $responseBody")

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val message = if (responseBody.trim().startsWith("{")) {
                            try {
                                JSONObject(responseBody).optString(
                                    "message", "Password reset successful!"
                                )
                            } catch (_: Exception) {
                                "Password reset successful!"
                            }
                        } else {
                            responseBody.ifBlank { "Password reset successful!" }
                        }

                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        val intent = Intent(context, LoginActivity::class.java)
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()

                    } else {
                        val message = if (responseBody.trim().startsWith("{")) {
                            try {
                                JSONObject(responseBody).optString(
                                    "message", "Password reset failed"
                                )
                            } catch (_: Exception) {
                                "Password reset failed"
                            }
                        } else {
                            responseBody.ifBlank { "Password reset failed" }
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }


    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.lowercase()
            .contains("vbox") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for x86") || Build.MANUFACTURER.contains(
            "Genymotion"
        ) || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")))
    }
}

@Composable
fun ResetPasswordScreen(
    onSendOtp: (String) -> Unit = {},
    onVerifyOtp: (String) -> Unit = {},
    onResetPassword: (String, String) -> Unit = { _, _ -> },
    currentStep: Int = 1
) {
    val textColor = Color.Black
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFFACC15), Color(0xFFF8F8F8)), startY = 0f, endY = 1000f
    )

    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var timer by remember { mutableStateOf(180) }
    var canResend by remember { mutableStateOf(false) }

    if (currentStep == 2) {
        LaunchedEffect(Unit) {
            timer = 180
            canResend = false
            while (timer > 0) {
                delay(1000)
                timer--
            }
            canResend = true
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.width(300.dp)
        ) {

            Image(
                painter = painterResource(id = R.drawable.expressora_logo),
                contentDescription = "Expressora Logo",
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))


            Text(
                text = when (currentStep) {
                    1 -> "Reset Password"
                    2 -> "Enter OTP"
                    else -> "Set New Password"
                },
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontFamily = InterFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))


            Text(
                text = when (currentStep) {
                    1 -> "Enter your email to receive a reset code"
                    2 -> "We've sent a code to your email"
                    else -> "Choose a new password you'll remember"
                },
                color = textColor,
                fontSize = 14.sp,
                fontFamily = InterFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (currentStep) {
                1 -> {
                    StyledTextField(
                        value = email, onValueChange = { email = it }, placeholder = "Email"
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    YellowButton("Next") {
                        if (email.isNotBlank()) {
                            onSendOtp(email)
                        }
                    }
                }

                2 -> {
                    val context = LocalContext.current

                    OTPInput(
                        otpText = otp, onOtpChange = { if (it.length <= 5) otp = it })

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (!canResend) "Code expires in ${timer / 60}:${
                            (timer % 60).toString().padStart(2, '0')
                        }"
                        else "Code expired. Please resend OTP.",
                        color = if (canResend) Color.Red else Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = InterFontFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    YellowButton(
                        text = if (!canResend) "Verify" else "Resend OTP"
                    ) {
                        if (!canResend) {
                            if (otp.length == 5) {
                                onVerifyOtp(otp)
                            } else {
                                Toast.makeText(
                                    context, "Please enter the 5-digit OTP", Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            onSendOtp(email)
                            timer = 180
                            canResend = false
                            Toast.makeText(
                                context,
                                "A new OTP has been sent to your email.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }


                3 -> {
                    StyledTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        placeholder = "New Password",
                        isPassword = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    StyledTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        placeholder = "Confirm Password",
                        isPassword = true
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    YellowButton("Reset") {
                        if (newPassword.isNotBlank() && confirmPassword.isNotBlank()) {
                            onResetPassword(newPassword, confirmPassword)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StyledTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, isPassword: Boolean = false
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val textColor = Color.Black

    val customSelectionColors = TextSelectionColors(
        handleColor = Color(0xFFFACC15), backgroundColor = Color(0x33FACC15)
    )

    CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    color = textColor,
                    fontFamily = InterFontFamily,
                    fontSize = 16.sp
                )
            },
            textStyle = TextStyle(
                color = textColor, fontFamily = InterFontFamily, fontSize = 16.sp
            ),
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation()
            else VisualTransformation.None,
            trailingIcon = {
                if (isPassword) {
                    val icon = if (passwordVisible) Icons.Filled.Visibility
                    else Icons.Filled.VisibilityOff
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(icon, contentDescription = null, tint = textColor)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                cursorColor = textColor,
                focusedIndicatorColor = textColor,
                unfocusedIndicatorColor = textColor,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )
    }
}

@Composable
fun YellowButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(150.dp)
            .height(35.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFACC15), contentColor = Color.Black
        )
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFontFamily,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun OTPInput(
    otpText: String, onOtpChange: (String) -> Unit
) {
    val textColor = Color.Black

    Box(
        modifier = Modifier
            .width(300.dp)
            .padding(horizontal = 8.dp)
    ) {
        BasicTextField(
            value = otpText, onValueChange = { onOtpChange(it) }, decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    val char = otpText.getOrNull(index)?.toString() ?: ""
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(10.dp))
                            .background(Color.White), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char, style = TextStyle(
                                color = textColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                fontFamily = InterFontFamily
                            )
                        )
                    }
                }
            }
        }, textStyle = TextStyle(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center
        )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ResetPasswordPreview() {
    ExpressoraTheme {
        ResetPasswordScreen()
    }
}
