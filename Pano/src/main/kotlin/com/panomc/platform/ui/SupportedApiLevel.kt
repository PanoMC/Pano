package com.panomc.platform.ui

/**
 * The API levels this Pano accepts from a front-end (doc 04 §7): `min` and `current` of the
 * classpath resource `pano-api-level.properties` that the build writes, both 1 until it exists.
 */
object SupportedApiLevel {
    val min: Int by lazy { read("min") }
    val current: Int by lazy { read("current") }

    private fun read(key: String): Int =
        runCatching {
            SupportedApiLevel::class.java.getResourceAsStream("/pano-api-level.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty(key)?.trim()?.toInt()
            }
        }.getOrNull() ?: 1
}
