package com.panomc.platform.model

/**
 * A progress event of a stream (`{"status":"progress","progress":0.42}`). A download also carries
 * `downloadedBytes` and `totalBytes`, so the panel can show the size, the speed and the time left.
 */
class Progress(
    val progress: Double,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null
) : Successful(
    buildMap {
        put("status", "progress")
        put("progress", progress)
        downloadedBytes?.let { put("downloadedBytes", it) }
        totalBytes?.takeIf { it > 0 }?.let { put("totalBytes", it) }
    }
)
