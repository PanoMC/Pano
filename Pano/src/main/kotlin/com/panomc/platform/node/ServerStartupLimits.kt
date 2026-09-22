package com.panomc.platform.node

/**
 * The bounds a managed server's startup settings are kept within, wherever they come from: the
 * create-server API, the startup-settings API, and a Pano Agent's first-run answers (SM-76), which
 * must never let through what the panel would have refused.
 */
object ServerStartupLimits {
    const val MIN_MEMORY_MB = 256
    const val MAX_MEMORY_MB = 1024 * 1024
    const val MAX_JVM_ARGS = 64
    const val MAX_JVM_ARG_LENGTH = 256

    /** [values] as the APIs store Java arguments: trimmed, blanks dropped, at most 64 of 256 chars. */
    fun jvmArgs(values: List<Any?>?): List<String> = values.orEmpty()
        .mapNotNull { it as? String }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_JVM_ARGS)
        .map { it.take(MAX_JVM_ARG_LENGTH) }

    /** [memoryMb] within the bounds, or null when there is none. */
    fun memoryMb(memoryMb: Int?): Int? = memoryMb?.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)
}
