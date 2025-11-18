package com.example.expressora.recognition.roi

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Interface for hand region of interest (ROI) detection.
 * Detects approximate hand location to enable cropping before MediaPipe processing.
 */
interface HandRoiDetector {
    /**
     * Detect hand ROI in the given bitmap.
     * @param bitmap Input bitmap (full frame)
     * @return Bounding box with padding, or null if no hand detected
     */
    fun detectRoi(bitmap: Bitmap): Rect?

    /**
     * Check if the detector is available and ready to use.
     */
    fun isAvailable(): Boolean

    /**
     * Release resources and close the detector.
     */
    fun close()
}

