package com.example.expressora.backend

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AuthRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun loginUser(email: String, password: String): Triple<Boolean, String?, String?> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Triple(false, null, "User not found")

            val doc = firestore.collection("users").document(uid).get().await()
            if (doc.exists()) {
                val role = doc.getString("role") ?: "user"
                Triple(true, role, null)
            } else {
                Triple(false, null, "User record not found in Firestore")
            }
        } catch (e: Exception) {
            Triple(false, null, e.message)
        }
    }
}
