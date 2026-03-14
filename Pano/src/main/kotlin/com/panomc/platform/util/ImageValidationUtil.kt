package com.panomc.platform.util

/**
 * Utility for validating image data URLs and content types.
 * Used to prevent XSS attacks via SVG and other dangerous formats.
 */
object ImageValidationUtil {
    /**
     * Whitelist of safe raster image MIME types.
     * SVG is intentionally excluded because it can contain embedded JavaScript.
     */
    private val ALLOWED_IMAGE_MIME_TYPES = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif"
    )

    /**
     * Extended whitelist that also includes icon formats (for website favicon uploads).
     */
    private val ALLOWED_FAVICON_MIME_TYPES = ALLOWED_IMAGE_MIME_TYPES + setOf(
        "image/x-icon",
        "image/vnd.microsoft.icon"
    )

    /**
     * Validates a data URL contains only safe raster image formats.
     * Rejects SVG and any non-image data URLs.
     *
     * @return true if the data URL is a safe image format
     */
    fun isAllowedImageDataUrl(dataUrl: String): Boolean {
        if (!dataUrl.startsWith("data:")) return false

        val mimeEnd = dataUrl.indexOf(';')
        if (mimeEnd == -1) return false

        val mimeType = dataUrl.substring(5, mimeEnd).lowercase()
        return mimeType in ALLOWED_IMAGE_MIME_TYPES
    }

    /**
     * Sanitizes a favicon data URL - returns null if format is not allowed.
     */
    fun sanitizeFaviconDataUrl(dataUrl: String?): String? {
        if (dataUrl.isNullOrBlank()) return null
        return if (isAllowedImageDataUrl(dataUrl)) dataUrl else null
    }
}
