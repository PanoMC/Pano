package com.panomc.node.host

import java.io.File

/**
 * Everything that differs between Linux, Windows and macOS, in one place.
 *
 * The jar is the same everywhere; what changes is how a service is registered, how a process is
 * asked to stop and where a Java runtime is likely to be found. Keeping the differences behind
 * this object is what lets the rest of the daemon be written once, and it is why the OS and
 * architecture Pano is told about are normalised names rather than whatever the JVM's own
 * properties happened to say on that host.
 */
object HostPlatform {
    val rawOsName: String = System.getProperty("os.name", "").lowercase()

    val rawOsArch: String = System.getProperty("os.arch", "").lowercase()

    /** One of `linux`, `windows`, `macos`, or the raw value when it is none of those. */
    val os: String = normaliseOs(rawOsName)

    /** One of `x64`, `arm64`, `arm32`, or the raw value. */
    val arch: String = normaliseArch(rawOsArch)

    val isWindows get() = os == "windows"

    val isMac get() = os == "macos"

    val isLinux get() = os == "linux"

    /** The name of the java launcher on this host. */
    val javaExecutable: String get() = if (isWindows) "java.exe" else "java"

    val hostname: String by lazy {
        try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            System.getenv(if (isWindows) "COMPUTERNAME" else "HOSTNAME") ?: "unknown"
        }
    }

    /** Directories a system-wide JDK install is conventionally found in. */
    fun systemJavaRoots(): List<File> = when {
        isLinux -> listOf(File("/usr/lib/jvm"), File("/usr/java"), File("/opt/java"))
        isMac -> listOf(File("/Library/Java/JavaVirtualMachines"), File("/opt/homebrew/opt"))
        isWindows -> listOfNotNull(
            System.getenv("ProgramFiles")?.let { File(it, "Java") },
            System.getenv("ProgramFiles")?.let { File(it, "Eclipse Adoptium") }
        )

        else -> emptyList()
    }

    internal fun normaliseOs(raw: String): String = normaliseLowercasedOs(raw.lowercase())

    private fun normaliseLowercasedOs(value: String): String = when {
        value.contains("win") -> "windows"
        value.contains("mac") || value.contains("darwin") || value.contains("osx") -> "macos"
        value.isBlank() -> "unknown"
        else -> "linux"
    }

    internal fun normaliseArch(raw: String): String = normaliseLowercasedArch(raw.lowercase())

    private fun normaliseLowercasedArch(value: String): String = when {
        value.contains("amd64") || value.contains("x86_64") || value == "x64" -> "x64"
        value.contains("aarch64") || value.contains("arm64") -> "arm64"
        value.startsWith("arm") -> "arm32"
        value.isBlank() -> "unknown"
        else -> value
    }
}
