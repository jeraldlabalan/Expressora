package com.example.expressora.recognition.accumulator

data class AccumulatorState(
    val tokens: List<String> = emptyList(),
    val currentWord: String = "",
    val lastTokenTime: Long = 0L,
    val isAlphabetMode: Boolean = false
) {
    fun hasSpace(): Boolean = tokens.size < 7
    
    fun canAddToken(): Boolean = hasSpace()
    
    fun getDisplayText(): String {
        val tokenText = tokens.joinToString(" ")
        return if (currentWord.isNotEmpty()) {
            if (tokenText.isNotEmpty()) "$tokenText [$currentWord]" else "[$currentWord]"
        } else {
            tokenText
        }
    }
}

