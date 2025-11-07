package com.example.expressora.recognition.tflite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LabelMap {
    fun load(context: Context, assetName: String): List<String> {
        return runCatching {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }.trim()
            if (text.startsWith("{")) {
                val arr = JSONObject(text).optJSONArray("labels") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            } else {
                val arr = JSONArray(text)
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrElse { emptyList() }
    }
}