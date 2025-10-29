package com.example.expressora.auth

import java.security.MessageDigest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
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
import kotlinx.coroutines.delay
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

class RegisterActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private val GOOGLE_SIGN_IN_REQUEST = 1001
    private val firestore = FirebaseFirestore.getInstance()
    private val firebaseAuth = FirebaseAuth.getInstance()
    private lateinit var context: Context

    private var otpEmail: String = ""
    private var otpPassword: String = ""

    private var currentStep by mutableStateOf(1)
    private var isOtpSent by mutableStateOf(false)
    private var isRegistrationComplete by mutableStateOf(false)

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
            showRegisterScreen()
        }
    }

    private fun showRegisterScreen() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            ExpressoraTheme {
                RegisterScreen(
                    onGoogleSignInClick = { signInWithGoogle() },
                    onSendOtp = { email, password ->
                        checkAndSendOtp(email, password)
                    },
                    onVerifyOtp = { enteredOtp ->
                        verifyOtp(enteredOtp)
                    },
                    currentStep = currentStep
                )
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
            showRegisterScreen()
        }
    }

    private fun checkAndSendOtp(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(context, "Please enter both email and password", Toast.LENGTH_SHORT)
                .show()
            return
        }

        firestore.collection("users").whereEqualTo("email", email.trim()).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    Toast.makeText(
                        context,
                        "Email already registered. Please log in instead.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    sendOtp(email.trim(), password)
                }
            }.addOnFailureListener { e ->
                Toast.makeText(
                    context,
                    "Error checking existing account: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun sendOtp(email: String, password: String) {
        otpEmail = email
        otpPassword = password

        val LOCAL_HOST_IP = "192.168.1.9"
        val baseUrl = if (isEmulator()) "http://10.0.2.2:3000" else "http://$LOCAL_HOST_IP:3000"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply { put("email", email) }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url("$baseUrl/reg-send-otp").post(body)
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
                    put("email", otpEmail)
                    put("otp", enteredOtp)
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url("$baseUrl/reg-verify-otp").post(body)
                    .addHeader("Content-Type", "application/json").build()

                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(context, "OTP Verified", Toast.LENGTH_SHORT).show()
                        saveUserToFirestore()
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

    private fun saveUserToFirestore() {
        val hashedPassword = MessageDigest.getInstance("SHA-256").digest(otpPassword.toByteArray())
            .joinToString("") { "%02x".format(it) }

        firebaseAuth.createUserWithEmailAndPassword(otpEmail, otpPassword)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                if (uid != null) {
                    val user = hashMapOf(
                        "email" to otpEmail,
                        "password" to hashedPassword,
                        "firstName" to "",
                        "lastName" to "",
                        "profile" to "",
                        "role" to "user",
                        "createdAt" to Date()
                    )
                    firestore.collection("users").document(uid).set(user).addOnSuccessListener {
                        Toast.makeText(context, "Registration successful", Toast.LENGTH_SHORT)
                            .show()

                        currentStep = 1
                        isOtpSent = false
                        otpEmail = ""
                        otpPassword = ""

                        firebaseAuth.signOut()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }.addOnFailureListener { e ->
                        Toast.makeText(
                            context, "Error saving user: ${e.localizedMessage}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.addOnFailureListener { e ->
                Toast.makeText(
                    context, "Error creating user: ${e.localizedMessage}", Toast.LENGTH_LONG
                ).show()
            }
    }


    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, GOOGLE_SIGN_IN_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GOOGLE_SIGN_IN_REQUEST) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.idToken
                val email = account?.email ?: return
                val name = account.displayName ?: "User"

                handlePostGoogleSignIn(email, name)

            } catch (e: ApiException) {
                Toast.makeText(context, "Google Sign-In failed: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun sendGoogleTokenToBackend(idToken: String, displayName: String, email: String) {
        val LOCAL_HOST_IP = "192.168.1.9"
        val baseUrl = if (isEmulator()) "http://10.0.2.2:3000" else "http://$LOCAL_HOST_IP:3000"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply { put("token", idToken) }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url("$baseUrl/google-auth").post(body)
                    .addHeader("Content-Type", "application/json").build()

                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        handlePostGoogleSignIn(email, displayName)
                    } else {
                        Toast.makeText(
                            context,
                            "Backend verification failed, proceeding with local sign-in.",
                            Toast.LENGTH_SHORT
                        ).show()
                        handlePostGoogleSignIn(email, displayName)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Backend not available, proceeding with local sign-in.",
                        Toast.LENGTH_SHORT
                    ).show()
                    handlePostGoogleSignIn(email, displayName)
                }
            }
        }
    }

    private fun handlePostGoogleSignIn(email: String, name: String) {
        firestore.collection("users").whereEqualTo("email", email).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    val randomPassword = "google_${System.currentTimeMillis()}"
                    firebaseAuth.createUserWithEmailAndPassword(email, randomPassword)
                        .addOnSuccessListener { authResult ->
                            val uid = authResult.user?.uid
                            if (uid != null) {
                                val user = hashMapOf(
                                    "email" to email,
                                    "password" to randomPassword.hashCode().toString(),
                                    "firstName" to "",
                                    "lastName" to "",
                                    "profile" to "",
                                    "role" to "user",
                                    "createdAt" to Date()
                                )
                                firestore.collection("users").document(uid).set(user)
                            }
                        }.addOnFailureListener { e ->
                        }
                }

                val intent = Intent(this, CommunitySpaceActivity::class.java)
                intent.putExtra("USER_NAME", name)
                intent.putExtra("USER_EMAIL", email)
                startActivity(intent)
                finish()
            }.addOnFailureListener { e ->
                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.lowercase()
            .contains("vbox") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for x86") || Build.MANUFACTURER.contains(
            "Genymotion"
        ) || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
    }
}

@Composable
fun RegisterScreen(
    onGoogleSignInClick: () -> Unit = {},
    onSendOtp: (String, String) -> Unit = { _, _ -> },
    onVerifyOtp: (String) -> Unit = {},
    currentStep: Int = 1
) {
    val context = LocalContext.current
    val textColor = Color.Black
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFFACC15), Color(0xFFF8F8F8)), startY = 0f, endY = 1000f
    )

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

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
                        1 -> "Sign Up"
                        else -> "Enter OTP"
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
                        1 -> "Start your journey by joining us today"
                        else -> "We've sent a code to your email"
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
                        EmailField(
                            value = email, onValueChange = { email = it }, textColor = textColor
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        PasswordField(
                            value = password,
                            onValueChange = { password = it },
                            textColor = textColor,
                            passwordVisible = passwordVisible,
                            onVisibilityToggle = { passwordVisible = !passwordVisible })

                        Spacer(modifier = Modifier.height(24.dp))

                        YellowButton2("Sign Up") {
                            when {
                                email.isBlank() || password.isBlank() -> {
                                    Toast.makeText(
                                        context,
                                        "Please enter both email and password",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                password.length < 8 -> {
                                    Toast.makeText(
                                        context,
                                        "Password must be at least 8 characters long",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                !password.any { it.isDigit() } -> {
                                    Toast.makeText(
                                        context,
                                        "Password must contain at least one number",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                !password.any { it.isLetter() } -> {
                                    Toast.makeText(
                                        context,
                                        "Password must contain at least one letter",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                !password.any { !it.isLetterOrDigit() } -> {
                                    Toast.makeText(
                                        context,
                                        "Password must include at least one special character (e.g., !@#\$%^&*)",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                else -> {
                                    onSendOtp(email, password)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = "Or sign up with",
                            fontSize = 14.sp,
                            color = textColor,
                            fontFamily = InterFontFamily
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(50.dp)
                                .clickable { onGoogleSignInClick() },
                            shape = CircleShape,
                            color = Color(0xFF0F172A),
                            shadowElevation = 4.dp
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.google_logo),
                                    contentDescription = "Google Sign Up",
                                    modifier = Modifier.size(35.dp)
                                )
                            }
                        }
                    }

                    2 -> {
                        OTPInput2(
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

                        YellowButton2(
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
                                otp = ""
                                timer = 180
                                canResend = false
                                Toast.makeText(
                                    context,
                                    "A new OTP has been sent to your email.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onSendOtp(email, password)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun CommonTextFieldColors(textColor: Color): TextFieldColors {
    return TextFieldDefaults.colors(
        focusedTextColor = textColor,
        unfocusedTextColor = textColor,
        focusedIndicatorColor = textColor,
        unfocusedIndicatorColor = textColor,
        cursorColor = textColor,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent
    )
}

@Composable
fun EmailField(value: String, onValueChange: (String) -> Unit, textColor: Color) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text("Email", color = textColor, fontFamily = InterFontFamily)
        },
        singleLine = true,
        modifier = Modifier.width(300.dp),
        colors = CommonTextFieldColors(textColor),
        textStyle = TextStyle(
            fontFamily = InterFontFamily, fontSize = 16.sp
        )
    )
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    textColor: Color,
    passwordVisible: Boolean,
    onVisibilityToggle: () -> Unit
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text("Password", color = textColor, fontFamily = InterFontFamily)
        },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            val icon = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
            IconButton(onClick = onVisibilityToggle) {
                Icon(
                    imageVector = icon, contentDescription = "Toggle Password", tint = textColor
                )
            }
        },
        singleLine = true,
        modifier = Modifier.width(300.dp),
        colors = CommonTextFieldColors(textColor),
        textStyle = TextStyle(
            fontFamily = InterFontFamily, fontSize = 16.sp
        )
    )
}

@Composable
fun OTPInput2(otpText: String, onOtpChange: (String) -> Unit) {
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

@Composable
fun YellowButton2(text: String, onClick: () -> Unit) {
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

@Preview(showBackground = true)
@Composable
fun RegisterPreview() {
    ExpressoraTheme {
        RegisterScreen()
    }
}