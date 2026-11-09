package com.panomc.platform.update

import java.util.Base64

/**
 * How the JVM options and program arguments Pano was started with reach `pano-updater`, so the
 * relaunched Pano runs with the same `-Xmx`, `-D…` properties, agents and flags as before.
 *
 * Each list travels as one `--key=<base64>` argument (entries joined by NUL), so spaces, quotes and
 * leading dashes survive the updater's simple argument parser unchanged.
 */
object UpdaterLaunchArgs {
    const val JVM_ARGS = "--jvm-args-b64"
    const val PROGRAM_ARGS = "--launch-args-b64"

    /** Flags the updater sets itself from the current mode. */
    private val HANDLED_PROGRAM_FLAGS = setOf("-nogui", "-bg")

    fun encode(values: List<String>): String =
        Base64.getEncoder().encodeToString(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))

    /**
     * JVM options worth keeping: a debugger agent is dropped, since its port or `suspend=y` would
     * stop the relaunched Pano from coming up on its own.
     */
    fun jvmArgs(inputArguments: List<String>): List<String> = inputArguments.filterNot {
        it.startsWith("-agentlib:jdwp") || it.startsWith("-Xrunjdwp") || it.startsWith("-Xdebug")
    }

    fun programArgs(startupArgs: List<String>): List<String> = startupArgs.filterNot { it in HANDLED_PROGRAM_FLAGS }

    fun arguments(inputArguments: List<String>, startupArgs: List<String>): List<String> = listOf(
        "$JVM_ARGS=" + encode(jvmArgs(inputArguments)),
        "$PROGRAM_ARGS=" + encode(programArgs(startupArgs))
    )
}
