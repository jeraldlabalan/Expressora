package com.example.expressora.backend

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AuthRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun loginUser(email: String, password: String): Triple<Boolean, String?, String?> {
        return try {
            println("DEBUG: Attempting Firebase Auth login for email: $email")
            // First try Firebase Auth login
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Triple(false, null, "User not found")
            println("DEBUG: Firebase Auth login successful, UID: $uid")

            val doc = firestore.collection("users").document(uid).get().await()
            if (doc.exists()) {
                val role = doc.getString("role") ?: "user"
                println("DEBUG: Found user in Firestore with role: $role")
                Triple(true, role, null)
            } else {
                println("DEBUG: User not found in Firestore, trying alternative login")
                // If user exists in Firebase Auth but not in Firestore, try alternative approach
                tryAlternativeLogin(email, password)
            }
        } catch (e: Exception) {
            println("DEBUG: Firebase Auth failed: ${e.message}, trying alternative login")
            // If Firebase Auth fails, try alternative login method
            tryAlternativeLogin(email, password)
        }
    }

    private suspend fun tryAlternativeLogin(
        email: String,
        password: String
    ): Triple<Boolean, String?, String?> {
        return try {
            // Search for user in Firestore by email
            val snapshot = firestore.collection("users")
                .whereEqualTo("email", email)
                .get().await()

            if (snapshot.isEmpty) {
                return Triple(false, null, "USER_NOT_FOUND")
            }

            val doc = snapshot.documents[0]
            val storedPasswordHash = doc.getString("password") ?: ""
            val inputPasswordHash = password.hashCode().toString()

            // Debug logging
            println("DEBUG: Stored hash: $storedPasswordHash")
            println("DEBUG: Input hash: $inputPasswordHash")
            println("DEBUG: Match: ${storedPasswordHash == inputPasswordHash}")

            if (storedPasswordHash == inputPasswordHash) {
                val role = doc.getString("role") ?: "user"
                Triple(true, role, null)
            } else {
                Triple(false, null, "INVALID_PASSWORD")
            }
        } catch (e: Exception) {
            println("DEBUG: Exception in tryAlternativeLogin: ${e.message}")
            Triple(false, null, e.message)
        }
    }
}
