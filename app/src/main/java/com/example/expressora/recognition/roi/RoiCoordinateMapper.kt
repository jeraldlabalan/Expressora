package com.example.expressora.recognition.roi

import android.graphics.Rect
import com.example.expressora.recognition.feature.LandmarkFeatureExtractor.Point3

/**
 * Utility for mapping normalized landmarks from ROI coordinates back to full image coordinates.
 */
object RoiCoordinateMapper {
    /**
     * Map a Point3 landmark from ROI coordinates to full image coordinates.
     * 
     * @param point Point3 with normalized coordinates (0-1) relative to cropped ROI
     * @param roiOffset ROI bounding box in full image coordinates, or null if no ROI was used
     * @param croppedWidth Width of the cropped ROI bitmap
     * @param croppedHeight Height of the cropped ROI bitmap
     * @param fullWidth Width of the full original image
     * @param fullHeight Height of the full original image
     * @return New Point3 with normalized coordinates (0-1) relative to full image, or original if no ROI
     */
    fun mapPoint(
        point: Point3,
        roiOffset: Rect?,
        croppedWidth: Int,
        croppedHeight: Int,
        fullWidth: Int,
        fullHeight: Int
    ): Point3 {
        if (roiOffset == null || fullWidth <= 0 || fullHeight <= 0) {
            // No ROI was used or invalid dimensions, coordinates are already correct
            return point
        }

        // Convert normalized ROI coordinates to pixel coordinates in cropped image
        val xInCropped = point.x * croppedWidth
        val yInCropped = point.y * croppedHeight
        val zInCropped = point.z * croppedWidth // Z is typically in same units as X

        // Map to full image pixel coordinates
        val xInFull = xInCropped + roiOffset.left
        val yInFull = yInCropped + roiOffset.top
        val zInFull = zInCropped // Z doesn't need offset

        // Normalize back to full image coordinates (0-1)
        val xNormalized = xInFull / fullWidth
        val yNormalized = yInFull / fullHeight
        val zNormalized = zInFull / fullWidth

        return Point3(xNormalized, yNormalized, zNormalized)
    }
}

