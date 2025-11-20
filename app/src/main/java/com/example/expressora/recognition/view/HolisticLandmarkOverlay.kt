package com.example.expressora.recognition.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.expressora.recognition.config.PerformanceConfig
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult

/**
 * Custom view to draw holistic landmarks (hands, face) on top of camera preview.
 * Shows visual feedback of detected hands and face with landmarks and connections.
 * Throttles redraws to MAX_OVERLAY_FPS (default 15 Hz) to reduce UI thread load.
 */
class HolisticLandmarkOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    init {
        // Enable hardware acceleration for smoother rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // Make overlay transparent so camera preview shows through
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Ensure view is visible
        visibility = VISIBLE
    }

    private var holisticResult: HolisticLandmarkerResult? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var lastInferenceTime: Long = 0L  // Timestamp when inference completed
    
    // Throttling to limit redraw rate
    private var lastRedrawTime = 0L
    private val minRedrawIntervalMs = (1000f / PerformanceConfig.MAX_OVERLAY_FPS).toLong()
    private val STALE_DATA_THRESHOLD_MS = 5000L  // Increased from 1000ms to 5000ms to survive device freezes (1.4s+)
    
    // Performance monitoring
    private var drawCount = 0
    private var lastPerformanceLogTime = 0L
    private val PERFORMANCE_LOG_INTERVAL_MS = 5000L  // Log every 5 seconds
    
    // Paint objects for drawing
    private val handLandmarkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeWidth = 2f
    }
    
    private val handConnectionPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val faceLandmarkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeWidth = 1f
    }
    
    private val faceConnectionPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    
    // Hand landmark connections (MediaPipe standard - 21 points)
    private val handConnections = listOf(
        // Thumb
        Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
        // Index finger
        Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
        // Middle finger
        Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
        // Ring finger
        Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
        // Pinky
        Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
        // Palm
        Pair(5, 9), Pair(9, 13), Pair(13, 17)
    )
    
    // Face key feature connections (simplified - key points for eyes, nose, mouth)
    // MediaPipe Face has 468 points, we'll draw key features
    private val faceKeyPoints = listOf(
        // Left eye outline (approximate indices)
        33, 7, 163, 144, 145, 153, 154, 155, 133,
        // Right eye outline
        362, 382, 381, 380, 374, 373, 390, 249, 263,
        // Nose
        1, 2, 5, 4, 6, 19, 20, 94, 125, 141, 235, 236, 3, 51, 48, 115, 131, 134, 102, 49, 220, 305, 281, 363, 360,
        // Mouth outline
        61, 146, 91, 181, 84, 17, 314, 405, 320, 307, 375, 321, 308, 324, 318
    )
    
    // Face connections (simplified mesh - key features)
    private val faceConnections = listOf(
        // Left eyebrow
        Pair(107, 55), Pair(55, 65), Pair(65, 52), Pair(52, 53), Pair(53, 46),
        // Right eyebrow
        Pair(336, 296), Pair(296, 334), Pair(334, 293), Pair(293, 300), Pair(300, 276),
        // Left eye
        Pair(33, 7), Pair(7, 163), Pair(163, 144), Pair(144, 145), Pair(145, 153), Pair(153, 154), Pair(154, 155), Pair(155, 133), Pair(133, 33),
        // Right eye
        Pair(362, 382), Pair(382, 381), Pair(381, 380), Pair(380, 374), Pair(374, 373), Pair(373, 390), Pair(390, 249), Pair(249, 263), Pair(263, 362),
        // Nose bridge
        Pair(1, 2), Pair(2, 5), Pair(5, 4), Pair(4, 6),
        // Nose outline
        Pair(19, 20), Pair(20, 94), Pair(94, 125), Pair(125, 141), Pair(141, 235), Pair(235, 236),
        // Mouth outer
        Pair(61, 146), Pair(146, 91), Pair(91, 181), Pair(181, 84), Pair(84, 17), Pair(17, 314), Pair(314, 405), Pair(405, 320), Pair(320, 307), Pair(307, 375), Pair(375, 321), Pair(321, 308), Pair(308, 324), Pair(324, 318), Pair(318, 61)
    )
    
    /**
     * Update the holistic landmarks to display
     * @param result The holistic landmarker result
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param inferenceTime Timestamp when inference completed (in milliseconds)
     */
    fun setHolisticLandmarks(
        result: HolisticLandmarkerResult?, 
        imageWidth: Int, 
        imageHeight: Int,
        inferenceTime: Long = System.currentTimeMillis()
    ) {
        val currentTime = System.currentTimeMillis()
        
        // If the inference result is older than 1 second, ignore it.
        // Otherwise, draw it (even if it's 500ms old, it's better than a blank screen).
        if (result != null && (currentTime - inferenceTime) > STALE_DATA_THRESHOLD_MS) {
            Log.v("HolisticLandmarkOverlay", "Discarding stale overlay data: age=${currentTime - inferenceTime}ms > ${STALE_DATA_THRESHOLD_MS}ms")
            return
        }
        
        // Log when valid data is received (for debugging)
        if (result != null) {
            val age = currentTime - inferenceTime
            Log.v("HolisticLandmarkOverlay", "✅ Overlay data accepted: age=${age}ms, hands=${result.leftHandLandmarks()?.size ?: 0}L/${result.rightHandLandmarks()?.size ?: 0}R")
        }
        
        val now = System.currentTimeMillis()
        val timeSinceLastRedraw = now - lastRedrawTime
        
        val landmarksChanged = this.holisticResult != result || 
            this.imageWidth != imageWidth || 
            this.imageHeight != imageHeight
        
        // Only invalidate if something changed AND we're not redrawing too frequently
        if (landmarksChanged && timeSinceLastRedraw >= minRedrawIntervalMs) {
            this.holisticResult = result
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
            this.lastInferenceTime = inferenceTime
            lastRedrawTime = now
            // Use postInvalidate() ensures the redraw happens on the next UI frame
            postInvalidate()
        } else if (landmarksChanged) {
            // Landmarks changed but throttled - just update state without redrawing
            this.holisticResult = result
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
            this.lastInferenceTime = inferenceTime
            Log.v("HolisticLandmarkOverlay", "Landmarks updated but throttled (will redraw on next interval)")
        } else if (result == null) {
            // Explicitly clear when result is null
            this.holisticResult = null
            this.lastInferenceTime = 0L
            postInvalidate()  // Clear the overlay
        }
    }
    
    /**
     * Clear all landmarks
     */
    fun clear() {
        holisticResult = null
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val result = holisticResult ?: return
        if (imageWidth == 0 || imageHeight == 0) {
            Log.v("HolisticLandmarkOverlay", "Skipping draw: imageWidth=$imageWidth, imageHeight=$imageHeight")
            return
        }
        
        // Additional check: if data is stale, don't draw
        val currentTime = System.currentTimeMillis()
        val dataAge = if (lastInferenceTime > 0) currentTime - lastInferenceTime else 0L
        
        // Debug logging to verify drawing attempts
        val leftHand = result.leftHandLandmarks()
        val rightHand = result.rightHandLandmarks()
        Log.v("HolisticLandmarkOverlay", "🎨 onDraw: Attempting to paint mesh, dataAge=${dataAge}ms, hands=${leftHand?.size ?: 0}L/${rightHand?.size ?: 0}R")
        
        if (lastInferenceTime > 0 && dataAge > STALE_DATA_THRESHOLD_MS) {
            Log.v("HolisticLandmarkOverlay", "Skipping draw: stale data (age=${dataAge}ms)")
            return  // Don't draw stale data
        }
        
        // Performance monitoring: track draw frequency and data age
        drawCount++
        if (currentTime - lastPerformanceLogTime > PERFORMANCE_LOG_INTERVAL_MS) {
            val drawFps = (drawCount * 1000f / (currentTime - lastPerformanceLogTime)).coerceAtMost(60f)
            Log.i("HolisticLandmarkOverlay", "📊 Performance: drawFPS=${String.format("%.1f", drawFps)}, avgDataAge=${dataAge}ms, draws=$drawCount")
            drawCount = 0
            lastPerformanceLogTime = currentTime
        }
        val hasHands = (leftHand != null && leftHand.isNotEmpty()) || (rightHand != null && rightHand.isNotEmpty())
        if (hasHands && PerformanceConfig.VERBOSE_LOGGING) {
            Log.v("HolisticLandmarkOverlay", "🎨 Drawing overlay: L=${leftHand?.size ?: 0}, R=${rightHand?.size ?: 0}, dataAge=${dataAge}ms, viewSize=${width}x${height}")
        }
        
        // Mirror the canvas horizontally to fix front camera mirroring
        canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f)
        
        // Draw face (middle layer)
        drawFaceLandmarks(canvas, result)
        
        // Draw hands last (foreground layer)
        drawHandLandmarks(canvas, result)
        
        // Restore canvas state
        canvas.restore()
    }
    
    private fun drawHandLandmarks(canvas: Canvas, result: HolisticLandmarkerResult) {
        val leftHand = result.leftHandLandmarks()
        val rightHand = result.rightHandLandmarks()
        
        // Draw left hand (green)
        if (leftHand != null && leftHand.isNotEmpty()) {
            drawHand(canvas, leftHand, Color.rgb(76, 175, 80), "L")
        }
        
        // Draw right hand (blue)
        if (rightHand != null && rightHand.isNotEmpty()) {
            drawHand(canvas, rightHand, Color.rgb(33, 150, 243), "R")
        }
    }
    
    private fun drawHand(canvas: Canvas, landmarks: List<NormalizedLandmark>, color: Int, label: String) {
        handConnectionPaint.color = color
        handConnectionPaint.alpha = 200
        handLandmarkPaint.color = color
        handLandmarkPaint.alpha = 200
        
        // Draw connections
        for (connection in handConnections) {
            val startIdx = connection.first
            val endIdx = connection.second
            
            if (startIdx < landmarks.size && endIdx < landmarks.size) {
                val start = landmarks[startIdx]
                val end = landmarks[endIdx]
                
                val startX = start.x() * width
                val startY = start.y() * height
                val endX = end.x() * width
                val endY = end.y() * height
                
                canvas.drawLine(startX, startY, endX, endY, handConnectionPaint)
            }
        }
        
        // Draw landmarks
        for (landmark in landmarks) {
            val x = landmark.x() * width
            val y = landmark.y() * height
            
            canvas.drawCircle(x, y, 6f, handLandmarkPaint)
            
            // White center for visibility
            handLandmarkPaint.color = Color.WHITE
            handLandmarkPaint.alpha = 200
            canvas.drawCircle(x, y, 2f, handLandmarkPaint)
            
            handLandmarkPaint.color = color
        }
        
        // Draw hand label
        if (landmarks.isNotEmpty()) {
            val wrist = landmarks[0]
            val labelX = wrist.x() * width
            val labelY = wrist.y() * height - 15
            
            val textPaint = Paint().apply {
                this.color = color
                textSize = 24f
                isAntiAlias = true
            }
            canvas.drawText(label, labelX, labelY, textPaint)
        }
    }
    
    private fun drawFaceLandmarks(canvas: Canvas, result: HolisticLandmarkerResult) {
        val faceLandmarks = result.faceLandmarks() ?: return
        if (faceLandmarks.isEmpty()) return
        
        faceConnectionPaint.color = Color.rgb(255, 235, 59) // Yellow
        faceConnectionPaint.alpha = 150
        faceLandmarkPaint.color = Color.rgb(255, 235, 59)
        faceLandmarkPaint.alpha = 150
        
        // Draw face connections (simplified mesh)
        for (connection in faceConnections) {
            val startIdx = connection.first
            val endIdx = connection.second
            
            if (startIdx < faceLandmarks.size && endIdx < faceLandmarks.size) {
                val start = faceLandmarks[startIdx]
                val end = faceLandmarks[endIdx]
                
                val startX = start.x() * width
                val startY = start.y() * height
                val endX = end.x() * width
                val endY = end.y() * height
                
                canvas.drawLine(startX, startY, endX, endY, faceConnectionPaint)
            }
        }
        
        // Draw key face landmarks (not all 468, just key points for performance)
        for (idx in faceKeyPoints) {
            if (idx < faceLandmarks.size) {
                val landmark = faceLandmarks[idx]
                val x = landmark.x() * width
                val y = landmark.y() * height
                
                canvas.drawCircle(x, y, 3f, faceLandmarkPaint)
            }
        }
    }
}

