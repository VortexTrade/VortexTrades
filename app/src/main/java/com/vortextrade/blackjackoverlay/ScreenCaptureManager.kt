package com.vortextrade.blackjackoverlay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection

/**
 * Wraps a [MediaProjection] and continuously mirrors the screen into an [ImageReader].
 * Call [captureLatest] to grab a single frame on demand — nothing is sent anywhere until
 * the user asks for it, which is what keeps API usage (and cost) tied to explicit taps.
 */
class ScreenCaptureManager(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {

    private val imageReader: ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "BlackjackOverlayCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
    }

    /**
     * Returns the most recent frame as a cropped [Bitmap], or null if no frame is ready yet.
     * Handles the row-stride padding ImageReader adds so the bitmap isn't skewed.
     */
    fun captureLatest(): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)

            // Crop off the stride padding on the right edge.
            if (rowPadding == 0) padded
            else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader.close()
    }
}
