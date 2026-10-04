package com.panomc.platform.server.files

/**
 * The files the panel's file manager may show in the browser instead of downloading.
 *
 * A preview is served from the panel's own origin, so what is on this list is decided by one
 * question: can the browser run anything from it? Images, video and audio cannot. SVG and HTML are
 * left off on purpose even though a browser would happily display them — both can carry script,
 * and a script running on the panel's origin runs with the session of whoever opened the preview.
 *
 * The type comes from the extension and from nothing else. The file lives on someone else's
 * machine, so neither its contents nor anything the source says about it may choose how the panel
 * renders it.
 */
object InlinePreviewTypes {
    /** Lower-case extension to the `Content-Type` it is previewed with. */
    val TYPES: Map<String, String> = mapOf(
        // Images
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "avif" to "image/avif",
        // Video
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "ogv" to "video/ogg",
        "mov" to "video/quicktime",
        // Audio
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "opus" to "audio/opus"
    )

    /**
     * The type [path] is previewed with, or null when it may not be previewed at all.
     *
     * Only the last segment's extension counts, case-insensitively, so `Screenshot.PNG` previews
     * and `image.png.html` does not.
     */
    fun contentTypeOf(path: String): String? {
        val name = path.substringAfterLast('/')

        if (!name.contains('.')) {
            return null
        }

        return TYPES[name.substringAfterLast('.').lowercase()]
    }

    /** Whether [path] may be previewed in the browser. */
    fun isPreviewable(path: String): Boolean = contentTypeOf(path) != null
}
