package com.panomc.node.java

import com.panomc.node.host.HostPlatform
import java.io.File

/**
 * The (os, arch, libc) triple a Java download has to match, and how each source spells it.
 *
 * [HostPlatform] already normalises the JVM's own properties into `linux` / `windows` / `macos`
 * and `x64` / `arm64` / `arm32`; what it cannot say is which C library a Linux host has, and that
 * is the one question a wrong answer to is fatal for: a glibc JRE on Alpine fails with a bare
 * `not found` for a file that is plainly there, because the loader it names does not exist.
 *
 * Temurin and Zulu disagree about every spelling (`mac` vs `macos`, `alpine-linux` vs
 * `linux_musl`, `x32` vs `i686`), so both are derived here and nowhere else. A value neither source
 * has a name for comes back null, which the resolver reports as "not available for this host"
 * rather than guessing at a URL.
 */
data class JavaTarget(
    /** [HostPlatform.os]: `linux`, `windows`, `macos`, or whatever the JVM said. */
    val os: String,
    /** [HostPlatform.arch]: `x64`, `arm64`, `arm32`, or the raw value (`x86`, `i386`, …). */
    val arch: String,
    /** Whether this is a Linux host whose C library is musl rather than glibc. */
    val musl: Boolean = false
) {
    /** `glibc` or `musl` on Linux, null anywhere the question does not exist. */
    val libc: String? get() = if (os == "linux") (if (musl) "musl" else "glibc") else null

    /** What Temurin calls this operating system, or null when it has no build for it at all. */
    val temurinOs: String?
        get() = when (os) {
            "linux" -> if (musl) "alpine-linux" else "linux"
            "windows" -> "windows"
            "macos" -> "mac"
            else -> null
        }

    /** What Temurin calls this architecture. */
    val temurinArch: String?
        get() = when {
            arch == "x64" -> "x64"
            arch == "arm64" -> "aarch64"
            arch == "arm32" -> "arm"
            isX86 -> "x32"
            else -> null
        }

    /** What Azul's metadata API calls this operating system. */
    val zuluOs: String?
        get() = when (os) {
            "linux" -> if (musl) "linux_musl" else "linux"
            "windows" -> "windows"
            "macos" -> "macos"
            else -> null
        }

    /** What Azul's metadata API calls this architecture. */
    val zuluArch: String?
        get() = when {
            arch == "x64" -> "x64"
            arch == "arm64" -> "aarch64"
            arch == "arm32" -> "arm"
            isX86 -> "i686"
            else -> null
        }

    /** `zip` on Windows, where that is what both sources ship, `tar.gz` everywhere else. */
    val archive: String get() = if (os == "windows") ZIP else TAR_GZ

    private val isX86: Boolean
        get() = arch == "x86" || arch == "x32" || arch == "i386" || arch == "i486" || arch == "i586" || arch == "i686"

    override fun toString(): String = listOfNotNull(os, arch, libc?.takeIf { musl }).joinToString("/")

    companion object {
        const val TAR_GZ = "tar.gz"
        const val ZIP = "zip"

        /** This host. Worked out once: the C library does not change while the daemon runs. */
        val current: JavaTarget by lazy {
            JavaTarget(HostPlatform.os, HostPlatform.arch, HostPlatform.isLinux && detectMusl())
        }

        /**
         * Whether this Linux host runs on musl.
         *
         * Two cheap facts rather than running `ldd`: Alpine says so in `/etc/alpine-release`, and
         * every musl system has its dynamic loader at `/lib/ld-musl-<arch>.so.1`. A glibc host
         * has neither, and a mixed host (glibc with a musl toolchain installed alongside) keeps
         * its musl loader somewhere other than the system `lib` directories checked here.
         */
        fun detectMusl(
            alpineRelease: File = File("/etc/alpine-release"),
            libDirs: List<File> = listOf(File("/lib"), File("/usr/lib"))
        ): Boolean {
            if (alpineRelease.isFile) {
                return true
            }

            return libDirs.any { dir ->
                dir.listFiles()?.any { it.name.startsWith("ld-musl-") } == true
            }
        }
    }
}
