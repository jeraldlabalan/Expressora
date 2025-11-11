package com.example.expressora.recognition.origin

import android.content.Context
import com.example.expressora.recognition.di.RecognitionProvider
import com.example.expressora.recognition.tflite.LabelMap

data class OriginBadge(
    val origin: String,
    val confidence: Float,
    val isEstimate: Boolean
) {
    fun getDisplayString(): String {
        return if (isEstimate) "$origin~" else origin
    }
}

object OriginResolver {
    
    fun initialize(context: Context) {
        // Load origin stats for fallback
        LabelMap.loadOriginStats(context)
    }
    
    /**
     * Resolve origin badge from multi-head model output
     */
    fun resolveFromMultiHead(
        originLabels: List<String>,
        originProbs: FloatArray
    ): OriginBadge {
        if (originProbs.isEmpty()) {
            return OriginBadge("UNKNOWN", 0f, false)
        }
        
        val maxIndex = originProbs.indices.maxByOrNull { originProbs[it] } ?: 0
        val maxConf = originProbs[maxIndex]
        val originLabel = originLabels.getOrElse(maxIndex) { "UNKNOWN" }
        
        return if (maxConf >= RecognitionProvider.ORIGIN_CONFIDENCE_THRESHOLD) {
            OriginBadge(originLabel, maxConf, false)
        } else {
            OriginBadge("UNKNOWN", maxConf, false)
        }
    }
    
    /**
     * Resolve origin badge from single-head model using prior statistics
     */
    fun resolveFromPriors(glossLabel: String): OriginBadge {
        val stats = LabelMap.getOriginStats(glossLabel)
        
        if (stats == null) {
            return OriginBadge("UNKNOWN", 0f, true)
        }
        
        val majorityOrigin = stats.getMajorityOrigin()
        val confidence = stats.getConfidence()
        
        return if (majorityOrigin != null) {
            OriginBadge(majorityOrigin, confidence, true)
        } else {
            OriginBadge("UNKNOWN", 0f, true)
        }
    }
    
    /**
     * Main resolution method that decides between multi-head and prior-based
     */
    fun resolve(
        glossLabel: String,
        hasMultiHead: Boolean,
        originLabels: List<String>? = null,
        originProbs: FloatArray? = null
    ): OriginBadge {
        return if (hasMultiHead && originProbs != null && originLabels != null) {
            resolveFromMultiHead(originLabels, originProbs)
        } else {
            resolveFromPriors(glossLabel)
        }
    }
}

