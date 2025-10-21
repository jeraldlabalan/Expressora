package com.example.expressora.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expressora.backend.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "", val password: String = "", val isLoading: Boolean = false
)

class LoginViewModel(private val repo: AuthRepository = AuthRepository()) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun login(
        onSuccess: (String) -> Unit, onError: (String) -> Unit
    ) {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password.trim()

        if (email.isEmpty() || password.isEmpty()) {
            onError("Please fill in all fields")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            val (success, role, error) = repo.loginUser(email, password)
            _uiState.value = _uiState.value.copy(isLoading = false)
            if (success) onSuccess(role ?: "user")
            else onError(error ?: "Login failed")
        }
    }
}
