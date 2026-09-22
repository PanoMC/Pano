package com.panomc.node.java

import com.panomc.node.host.JavaRuntime
import com.panomc.node.server.MinecraftJavaVersions
import java.io.File

/**
 * Which Java a server needs, and whether that means a download (SM-63, §2.4.28).
 *
 * The rule is the contract's, in one place for the install path and the start path alike: the
 * pinned `javaMajor` when Pano sent one, otherwise the Minecraft ladder's minimum for the version
 * -- in both cases raised to what the jar's own class files demand ([com.panomc.node.server.JarJavaRequirement]),
 * because a jar compiled for Java 25 does not load on 21 whatever anybody pinned.
 *
 * A download is wanted exactly when that major is not installed. Not "when nothing installed can
 * run it": a host with only Java 26 *can* run a 26.1 server (which asks for 25), and that is the
 * shutdown-crash case [MinecraftJavaVersions.choose] exists to avoid when it has the choice. With
 * downloads on, the node gives itself the choice.
 */
object JavaNeed {
    /** The major a server should run on, before looking at what is installed. */
    fun neededMajor(pinnedMajor: Int?, minecraftVersion: String?, jarRequirement: Int?): Int {
        val floor = jarRequirement?.takeIf { it > 0 } ?: 0

        val wanted = pinnedMajor?.takeIf { it > 0 } ?: MinecraftJavaVersions.minimumFor(minecraftVersion)

        return maxOf(wanted, floor)
    }

    /** What to do about [needed] on a host with [installed] majors. */
    fun decide(needed: Int, installed: Collection<Int>, autoDownload: Boolean): Decision = when {
        needed in installed -> Decision.PRESENT
        autoDownload -> Decision.DOWNLOAD
        else -> Decision.DISABLED
    }

    enum class Decision {
        /** Exactly that major is installed; nothing to do. */
        PRESENT,

        /** It is missing and the node may download it. */
        DOWNLOAD,

        /** It is missing and `java-auto-download` is off; resolve from what is installed, or fail. */
        DISABLED
    }

    /** The sentence a start that found no usable Java ends with, per §2.4.28. */
    fun missingReason(major: Int, autoDownload: Boolean, downloadError: String? = null): String = when {
        !autoDownload -> "No Java $major runtime on this host; automatic Java download is disabled"
        downloadError != null -> "No Java $major runtime on this host; downloading it failed: $downloadError"
        else -> "No Java $major runtime on this host"
    }
}

/**
 * What the removal check needs to know about one managed server, without the process behind it.
 *
 * [javaHome] is the runtime its live process was launched from (null when it is not running or
 * nobody knows); [pinnedMajor] is its spec's `javaMajor`, null for automatic.
 */
data class JavaUser(
    val serverUuid: String,
    val alive: Boolean,
    val javaHome: String?,
    val pinnedMajor: Int?
)

/**
 * When a managed runtime may be deleted (§2.4.28 "Removal").
 *
 * Refused while a live server was launched from it -- deleting the files under a running JVM is a
 * crash waiting for its next class load -- and while a server pins that major and no other
 * runtime of the major would be left, because that server's next start would then have to
 * download the very thing that was just removed.
 */
object JavaRemoval {
    /** The servers standing in the way of removing [targets] out of [installed]; empty means go. */
    fun blockers(targets: List<JavaRuntime>, installed: List<JavaRuntime>, users: List<JavaUser>): List<String> {
        if (targets.isEmpty()) {
            return emptyList()
        }

        val paths = targets.map { canonical(it.path) }.toSet()
        val majors = targets.map { it.major }.toSet()

        val running = users.filter { user ->
            user.alive && user.javaHome != null && canonical(user.javaHome) in paths
        }

        val pinned = majors.flatMap { major ->
            val remaining = installed.any { it.major == major && canonical(it.path) !in paths }

            if (remaining) emptyList() else users.filter { it.pinnedMajor == major }
        }

        return (running + pinned).map { it.serverUuid }.distinct()
    }

    /** The servers [runtime] is in use by, for the catalog's `usedBy`. */
    fun usedBy(runtime: JavaRuntime, installed: List<JavaRuntime>, users: List<JavaUser>): List<String> {
        val path = canonical(runtime.path)

        // A pinned server would be started on the newest runtime of its major, so that one is the
        // runtime it uses; an older one of the same major is not what it depends on.
        val newestOfMajor = installed.filter { it.major == runtime.major }
            .maxWithOrNull(compareBy(JavaVersionOrder) { it.version })

        return users.filter { user ->
            (user.alive && user.javaHome != null && canonical(user.javaHome) == path) ||
                (user.pinnedMajor == runtime.major && newestOfMajor != null && canonical(newestOfMajor.path) == path)
        }.map { it.serverUuid }.distinct()
    }

    private fun canonical(path: String): String = try {
        File(path).canonicalPath
    } catch (_: Exception) {
        File(path).absolutePath
    }
}
