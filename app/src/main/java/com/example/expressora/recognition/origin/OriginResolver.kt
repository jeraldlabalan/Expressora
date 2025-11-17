package com.example.expressora.recognition.origin

import android.content.Context
import android.util.Log
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
    private const val TAG = "OriginResolver"
    
    fun initialize(context: Context) {
        Log.d(TAG, "Initializing OriginResolver: loading origin stats")
        // Load origin stats for fallback
        LabelMap.loadOriginStats(context)
        Log.d(TAG, "OriginResolver initialized")
    }
    
    /**
     * Resolve origin badge from multi-head model output
     */
    fun resolveFromMultiHead(
        originLabels: List<String>,
        originProbs: FloatArray
    ): OriginBadge {
        Log.v(TAG, "resolveFromMultiHead: labels=${originLabels.size}, probs=${originProbs.size}")
        
        if (originProbs.isEmpty()) {
            Log.w(TAG, "Empty origin probabilities, returning UNKNOWN")
            return OriginBadge("UNKNOWN", 0f, false)
        }
        
        val maxIndex = originProbs.indices.maxByOrNull { originProbs[it] } ?: 0
        val maxConf = originProbs[maxIndex]
        val originLabel = originLabels.getOrElse(maxIndex) { "UNKNOWN" }
        
        Log.d(TAG, "Multi-head origin: label='$originLabel', conf=$maxConf, threshold=${RecognitionProvider.ORIGIN_CONFIDENCE_THRESHOLD}")
        
        return if (maxConf >= RecognitionProvider.ORIGIN_CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Origin resolved (multi-head): '$originLabel' (conf=$maxConf)")
            OriginBadge(originLabel, maxConf, false)
        } else {
            Log.w(TAG, "Origin confidence below threshold, returning UNKNOWN")
            OriginBadge("UNKNOWN", maxConf, false)
        }
    }
    
    /**
     * Resolve origin badge from single-head model using prior statistics
     */
    fun resolveFromPriors(glossLabel: String): OriginBadge {
        Log.v(TAG, "resolveFromPriors: glossLabel='$glossLabel'")
        
        val stats = LabelMap.getOriginStats(glossLabel)
        
        if (stats == null) {
            Log.w(TAG, "No origin stats found for gloss '$glossLabel', returning UNKNOWN")
            return OriginBadge("UNKNOWN", 0f, true)
        }
        
        val majorityOrigin = stats.getMajorityOrigin()
        val confidence = stats.getConfidence()
        
        Log.d(TAG, "Origin stats for '$glossLabel': majority='$majorityOrigin', confidence=$confidence")
        
        return if (majorityOrigin != null) {
            Log.d(TAG, "Origin resolved (priors): '$majorityOrigin' (conf=$confidence, estimated)")
            OriginBadge(majorityOrigin, confidence, true)
        } else {
            Log.w(TAG, "No majority origin found, returning UNKNOWN")
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
        Log.v(TAG, "resolve: glossLabel='$glossLabel', hasMultiHead=$hasMultiHead, " +
                "originLabels=${originLabels?.size}, originProbs=${originProbs?.size}")
        
        return if (hasMultiHead && originProbs != null && originLabels != null) {
            Log.d(TAG, "Using multi-head resolution")
            resolveFromMultiHead(originLabels, originProbs)
        } else {
            Log.d(TAG, "Using prior-based resolution")
            resolveFromPriors(glossLabel)
        }
    }
}

