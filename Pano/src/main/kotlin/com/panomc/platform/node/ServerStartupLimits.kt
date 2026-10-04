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

    /**
     * The first of [args] that would change *what* is launched rather than how, or null.
     *
     * Pano writes `java <these> -jar <server jar>`: a `-jar` of the admin's own, a class path, a
     * module or an `@argfile` swaps the program, and a bare word is read by `java` as the main
     * class. Heap flags are left alone on purpose -- memory is a startup setting of the same
     * permission, and an `-Xmx` set by hand is meant to win.
     */
    fun launchChangingJvmArg(args: List<String>): String? = args.firstOrNull { isLaunchChanging(it) }

    private fun isLaunchChanging(arg: String): Boolean {
        if (!arg.startsWith("-")) {
            return true
        }

        val flag = arg.substringBefore('=').substringBefore(':')

        return flag in LAUNCH_CHANGING_FLAGS
    }

    private val LAUNCH_CHANGING_FLAGS = setOf(
        "-jar", "-cp", "-classpath", "--class-path", "-p", "--module-path", "-m", "--module",
        "--upgrade-module-path", "-Xbootclasspath", "-Xbootclasspath/a", "-Xbootclasspath/p"
    )

    /** [memoryMb] within the bounds, or null when there is none. */
    fun memoryMb(memoryMb: Int?): Int? = memoryMb?.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)
}
