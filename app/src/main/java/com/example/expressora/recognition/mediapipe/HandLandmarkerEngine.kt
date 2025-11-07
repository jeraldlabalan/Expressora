package com.example.expressora.recognition.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandLandmarkerEngine(
    context: Context,
    maxHands: Int = 2,
) {

    var onResult: ((HandLandmarkerResult) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    private val landmarker: HandLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(maxHands)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                onResult?.invoke(result)
            }
            .setErrorListener { error ->
                onError?.invoke(error)
            }
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detectAsync(frame: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()) {
        try {
            val mpImage = BitmapImageBuilder(frame).build()
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (throwable: Throwable) {
            onError?.invoke(throwable)
        }
    }

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"
    }
}

