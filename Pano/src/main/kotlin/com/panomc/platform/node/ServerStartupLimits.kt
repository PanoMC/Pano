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
     * class. Flags that make the JVM run a command, load an agent or native code, read more
     * options from a file or write to a path of their own are refused too: the startup permission
     * can be granted for a single server and must never become a shell on the node's machine.
     * Heap flags are left alone on purpose -- memory is a startup setting of the same permission,
     * and an `-Xmx` set by hand is meant to win.
     */
    fun launchChangingJvmArg(args: List<String>): String? = args.firstOrNull { isLaunchChanging(it) }

    private fun isLaunchChanging(arg: String): Boolean {
        if (!arg.startsWith("-")) {
            return true
        }

        if (CODE_LOADING_PREFIXES.any { arg.startsWith(it) }) {
            return true
        }

        if (arg.startsWith("-XX:")) {
            val name = arg.removePrefix("-XX:").trimStart('+', '-').substringBefore('=')

            return name in UNSAFE_XX_OPTIONS
        }

        if (arg.startsWith("-Xlog") && arg.contains("file=")) {
            return true
        }

        if (arg.startsWith("-D")) {
            val property = arg.removePrefix("-D").substringBefore('=')

            return property in UNSAFE_PROPERTIES || property.startsWith("com.sun.management.")
        }

        val flag = arg.substringBefore('=').substringBefore(':')

        return flag in LAUNCH_CHANGING_FLAGS
    }

    private val LAUNCH_CHANGING_FLAGS = setOf(
        "-jar", "-cp", "-classpath", "--class-path", "-p", "--module-path", "-m", "--module",
        "--upgrade-module-path", "-Xbootclasspath", "-Xbootclasspath/a", "-Xbootclasspath/p",
        "--patch-module"
    )

    /** `-javaagent:`, `-agentpath:`, `-agentlib:` and the old `-Xrun<lib>` load code into the JVM. */
    private val CODE_LOADING_PREFIXES = listOf("-javaagent", "-agentpath", "-agentlib", "-Xrun")

    /** `-XX:` options that run a command, read options or code from a file, or write to any path. */
    private val UNSAFE_XX_OPTIONS = setOf(
        "OnError", "OnOutOfMemoryError", "VMOptionsFile", "Flags", "ErrorFile", "HeapDumpPath",
        "UnlockDiagnosticVMOptions", "CompileCommandFile", "CompilerDirectivesFile", "SharedArchiveFile",
        "ArchiveClassesAtExit", "AutoCreateSharedArchive", "EnableJVMCI", "UseJVMCICompiler",
        "JVMCILibPath", "StartFlightRecording", "FlightRecorderOptions", "LogFile", "LogVMOutput",
        "DumpLoadedClassList", "PerfDataSaveFile"
    )

    /** System properties that make the JVM load classes or native code from another path, or open JMX. */
    private val UNSAFE_PROPERTIES = setOf(
        "java.ext.dirs", "java.endorsed.dirs", "java.library.path", "java.system.class.loader",
        "java.security.manager", "java.security.policy", "jdk.module.path", "jdk.module.upgrade.path",
        "jdk.module.patch", "jdk.module.addmods", "jvmci.Compiler", "sun.boot.library.path"
    )

    /** [memoryMb] within the bounds, or null when there is none. */
    fun memoryMb(memoryMb: Int?): Int? = memoryMb?.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)
}
