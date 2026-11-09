package com.panomc.platform.util

import org.apache.tika.Tika

object MimeTypeUtil {
    private val tika by lazy {
        Tika()
    }

    fun getMimeTypeFromFileName(fileName: String): String {
        val split = fileName.split(".")

        val extension = split[split.size - 1]

        if (extension == "mjs" || extension == "js") {
            return "text/javascript"
        }

        return tika.detect(fileName)
    }

    /** Image types an uploaded favicon / logo may be served as; SVG is left out on purpose (it can run script). */
    private val SAFE_IMAGE_TYPES = setOf(
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/x-icon", "image/vnd.microsoft.icon"
    )

    /**
     * The type of an uploaded image file by its name, or `application/octet-stream` for anything
     * that is not a plain raster image, so a stored file is never served as a page or a script.
     */
    fun imageMimeTypeOrBinary(fileName: String): String =
        getMimeTypeFromFileName(fileName).takeIf { it in SAFE_IMAGE_TYPES } ?: "application/octet-stream"
}
