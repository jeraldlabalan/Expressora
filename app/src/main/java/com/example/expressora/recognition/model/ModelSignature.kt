package com.example.expressora.recognition.model

data class ModelSignature(
    val model_type: String,
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>
) {
    fun isMultiHead(): Boolean = outputs.size > 1
    
    fun hasOriginHead(): Boolean = outputs.any { 
        it.name.contains("origin", ignoreCase = true) 
    }
    
    fun getGlossOutputIndex(): Int = outputs.indexOfFirst { 
        it.name.contains("gloss", ignoreCase = true) || outputs.size == 1
    }.let { if (it < 0) 0 else it }
    
    fun getOriginOutputIndex(): Int = outputs.indexOfFirst { 
        it.name.contains("origin", ignoreCase = true) 
    }
}

data class InputSpec(
    val name: String,
    val index: Int,
    val shape: List<Int>,
    val dtype: String
)

data class OutputSpec(
    val name: String,
    val index: Int,
    val shape: List<Int>,
    val dtype: String
)

