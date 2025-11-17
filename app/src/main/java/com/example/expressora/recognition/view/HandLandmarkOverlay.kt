package com.example.expressora.recognition.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.expressora.recognition.config.PerformanceConfig
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Custom view to draw hand landmarks on top of camera preview.
 * Shows visual feedback of detected hands with landmarks and connections.
 * Throttles redraws to MAX_OVERLAY_FPS (default 15 Hz) to reduce UI thread load.
 */
class HandLandmarkOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    init {
        // Enable hardware acceleration for smoother rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private var handLandmarkerResult: HandLandmarkerResult? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var lastLandmarkCount = 0
    
    // Throttling to limit redraw rate
    private var lastRedrawTime = 0L
    private val minRedrawIntervalMs = (1000f / PerformanceConfig.MAX_OVERLAY_FPS).toLong()
    
    // Paint objects for drawing
    private val landmarkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeWidth = 2f
    }
    
    private val connectionPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
    }
    
    // Hand landmark connections (MediaPipe standard)
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
    
    /**
     * Update the landmarks to display
     * Only invalidates if landmarks actually changed AND enough time has passed (throttling)
     */
    fun setHandLandmarks(result: HandLandmarkerResult?, imageWidth: Int, imageHeight: Int) {
        val newCount = result?.landmarks()?.size ?: 0
        val now = System.currentTimeMillis()
        val timeSinceLastRedraw = now - lastRedrawTime
        
        // Check if landmarks changed
        val landmarksChanged = this.handLandmarkerResult != result || 
            this.imageWidth != imageWidth || 
            this.imageHeight != imageHeight ||
            newCount != lastLandmarkCount
        
        // Only invalidate if something changed AND we're not redrawing too frequently
        if (landmarksChanged && timeSinceLastRedraw >= minRedrawIntervalMs) {
            this.handLandmarkerResult = result
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
            this.lastLandmarkCount = newCount
            lastRedrawTime = now
            invalidate() // Trigger redraw only when needed and throttled
        } else if (landmarksChanged) {
            // Landmarks changed but throttled - just update state without redrawing
            this.handLandmarkerResult = result
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
            this.lastLandmarkCount = newCount
        }
    }
    
    /**
     * Clear all landmarks
     */
    fun clear() {
        handLandmarkerResult = null
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val result = handLandmarkerResult ?: return
        if (imageWidth == 0 || imageHeight == 0) return
        
        val landmarks = result.landmarks()
        val handednesses = result.handednesses()
        
        if (landmarks.isEmpty()) return
        
        // Mirror the canvas horizontally to fix front camera mirroring
        canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f)
        
        // Draw each detected hand
        for (handIndex in landmarks.indices) {
            val handLandmarks = landmarks[handIndex]
            
            // Get raw handedness from MediaPipe
            val rawHandedness = handednesses.getOrNull(handIndex)?.firstOrNull()
            val rawCategory = rawHandedness?.categoryName() ?: "Unknown"
            val rawScore = rawHandedness?.score() ?: 0f
            
            // FIX: MediaPipe's handedness labels are reversed relative to the person's actual hands
            // When MediaPipe says "Left", it's actually the person's right hand, and vice versa
            // Determine hand color (left = green, right = blue) with reversed mapping
            val isLeftHand = rawCategory.equals("Right", ignoreCase = true) // MediaPipe "Right" = person's actual left
            
            // CRITICAL: Log displayed handedness label
            val displayedLabel = if (isLeftHand) "Left" else "Right"
            Log.d("ExpressoraHandedness", "Displayed handedness: handIndex=$handIndex, " +
                    "displayLabel='$displayedLabel' (from raw='$rawCategory', score=$rawScore) " +
                    "[MediaPipe '$rawCategory' → person's actual $displayedLabel hand]")
            
            val handColor = if (isLeftHand) {
                Color.rgb(76, 175, 80) // Green for left hand
            } else {
                Color.rgb(33, 150, 243) // Blue for right hand
            }
            
            val confidence = handednesses.getOrNull(handIndex)
                ?.firstOrNull()?.score() ?: 1f
            
            // Set transparency based on confidence
            val alpha = (confidence * 255).toInt().coerceIn(128, 255)
            connectionPaint.color = handColor
            connectionPaint.alpha = alpha
            landmarkPaint.color = handColor
            landmarkPaint.alpha = alpha
            
            // Draw connections first (so they appear behind landmarks)
            for (connection in handConnections) {
                val startIdx = connection.first
                val endIdx = connection.second
                
                if (startIdx < handLandmarks.size && endIdx < handLandmarks.size) {
                    val start = handLandmarks[startIdx]
                    val end = handLandmarks[endIdx]
                    
                    val startX = start.x() * width
                    val startY = start.y() * height
                    val endX = end.x() * width
                    val endY = end.y() * height
                    
                    canvas.drawLine(startX, startY, endX, endY, connectionPaint)
                }
            }
            
            // Draw landmarks
            for (landmark in handLandmarks) {
                val x = landmark.x() * width
                val y = landmark.y() * height
                
                // Draw landmark as circle
                canvas.drawCircle(x, y, 8f, landmarkPaint)
                
                // Draw white center for visibility
                landmarkPaint.color = Color.WHITE
                landmarkPaint.alpha = alpha
                canvas.drawCircle(x, y, 4f, landmarkPaint)
                
                // Reset color for next landmark
                landmarkPaint.color = handColor
                landmarkPaint.alpha = alpha
            }
            
            // Draw hand label
            if (handLandmarks.isNotEmpty()) {
                val wrist = handLandmarks[0]
                val labelX = wrist.x() * width
                val labelY = wrist.y() * height - 20
                
                val handLabel = if (isLeftHand) "Left" else "Right"
                val confidenceText = "(${(confidence * 100).toInt()}%)"
                
                // Draw text background
                val textWidth = textPaint.measureText("$handLabel $confidenceText")
                val backgroundPaint = Paint().apply {
                    color = Color.BLACK
                    setAlpha(180)
                    style = Paint.Style.FILL
                }
                canvas.drawRect(
                    labelX - 5, labelY - 30,
                    labelX + textWidth + 5, labelY + 5,
                    backgroundPaint
                )
                
                // Draw text
                val labelPaint = Paint(textPaint).apply {
                    color = handColor
                }
                canvas.drawText("$handLabel $confidenceText", labelX, labelY, labelPaint)
            }
        }
        
        // Restore canvas state
        canvas.restore()
    }
}

