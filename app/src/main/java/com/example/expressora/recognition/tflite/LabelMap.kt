package com.example.expressora.recognition.tflite

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class OriginStats(
    val label: String,
    val aslCount: Int,
    val fslCount: Int
) {
    fun getMajorityOrigin(): String? {
        return when {
            aslCount > fslCount -> "ASL"
            fslCount > aslCount -> "FSL"
            aslCount == fslCount && aslCount > 0 -> "ASL" // tie-breaker
            else -> null
        }
    }
    
    fun getConfidence(): Float {
        val total = aslCount + fslCount
        if (total == 0) return 0f
        val max = maxOf(aslCount, fslCount)
        return max.toFloat() / total
    }
}

object LabelMap {
    private const val TAG = "LabelMap"
    
    private var rawLabels: List<String> = emptyList()
    private var mappedLabels: List<String> = emptyList()
    private var originStatsMap: Map<String, OriginStats> = emptyMap()
    
    fun load(context: Context, assetName: String): List<String> {
        if (rawLabels.isNotEmpty()) return rawLabels
        
        rawLabels = runCatching {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }.trim()
            if (text.startsWith("{")) {
                val arr = JSONObject(text).optJSONArray("labels") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            } else {
                val arr = JSONArray(text)
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrElse { emptyList() }
        
        return rawLabels
    }
    
    fun loadMapped(context: Context, assetName: String): List<String> {
        if (mappedLabels.isNotEmpty()) return mappedLabels
        
        mappedLabels = runCatching {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }.trim()
            if (text.startsWith("{")) {
                val arr = JSONObject(text).optJSONArray("labels") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            } else {
                val arr = JSONArray(text)
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrElse { 
            Log.w(TAG, "Failed to load mapped labels, falling back to raw labels")
            rawLabels
        }
        
        return mappedLabels
    }
    
    fun loadOriginStats(context: Context, assetName: String = "recognition/label_origin_stats_v11.json"): Map<String, OriginStats> {
        if (originStatsMap.isNotEmpty()) return originStatsMap
        
        originStatsMap = runCatching {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val map = mutableMapOf<String, OriginStats>()
            
            obj.keys().forEach { label ->
                val stats = obj.getJSONObject(label)
                val asl = stats.optInt("ASL", 0)
                val fsl = stats.optInt("FSL", 0)
                map[label] = OriginStats(label, asl, fsl)
            }
            
            map
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load origin stats: ${error.message}")
            emptyMap()
        }
        
        return originStatsMap
    }
    
    fun getOriginStats(label: String): OriginStats? {
        return originStatsMap[label]
    }
    
    fun getMappedLabel(index: Int): String {
        return mappedLabels.getOrElse(index) { 
            rawLabels.getOrElse(index) { "CLASS_$index" }
        }
    }
    
    fun getRawLabel(index: Int): String {
        return rawLabels.getOrElse(index) { "CLASS_$index" }
    }
    
    fun isAlphabetLetter(label: String): Boolean {
        return label.length == 1 && label[0] in 'a'..'z'
    }
}
