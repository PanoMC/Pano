package com.panomc.node.server

import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * The Java version a server jar actually needs, read out of the jar itself.
 *
 * [MinecraftJavaVersions] answers a different question: what Mojang's ladder says a *Minecraft
 * version* needs. That is the right answer for a vanilla or Paper jar and the wrong one for
 * everything else, because a proxy's version is not a Minecraft version at all — and the day that
 * mattered, the catalog handed over `velocity 4.2.1-SNAPSHOT`, whose classes are compiled for Java
 * 25, while the ladder said 21. The node started it on 21, the JVM refused the class file, and the
 * crash-restart loop did the rest.
 *
 * A class file says which Java it needs in its own header, and that is not a guess: four bytes of
 * `0xCAFEBABE`, two of minor version, then the major, where 45 is Java 1 and every release since
 * has added one. So the jar's `Main-Class` is looked up in the manifest, its class file is read,
 * and the major becomes the requirement.
 *
 * Multi-release jars are handled but deliberately not preferred. `META-INF/versions/<n>/` holds
 * *alternative* classes a newer runtime may use; the copy at the root is the one that decides
 * whether the jar loads at all, so it is read first and the versioned copies are only a fallback
 * for a jar that ships nothing at the root.
 *
 * Pure and quiet: anything unreadable — no manifest, no `Main-Class`, a class file that is not one
 * — comes back null, which means "no opinion" and leaves the ladder in charge. Refusing to start a
 * server because its jar could not be parsed would be a far worse failure than the one this fixes.
 */
object JarJavaRequirement {
    /** Every class file starts with these four bytes; anything else is not one. */
    const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()

    /** Class major 45 is Java 1, 52 is Java 8, 61 is Java 17, 69 is Java 25. */
    const val CLASS_MAJOR_OFFSET = 44

    private const val MIN_CLASS_MAJOR = 45

    /** Far past anything that exists, but low enough that a garbage read is not believed. */
    private const val MAX_CLASS_MAJOR = 150

    private const val MULTI_RELEASE_PREFIX = "META-INF/versions/"

    /** The Java major [jar] needs, or null when the jar does not say. */
    fun of(jar: File): Int? {
        if (!jar.isFile) {
            return null
        }

        return try {
            JarFile(jar).use { file ->
                val mainClass = file.manifest
                    ?.mainAttributes
                    ?.getValue("Main-Class")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

                val path = mainClass.replace('.', '/') + ".class"

                val entry = file.getEntry(path) ?: lowestVersionedEntry(file, path) ?: return null

                val major = file.getInputStream(entry).use { readClassMajor(it) } ?: return null

                major - CLASS_MAJOR_OFFSET
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The class major at the head of [stream], or null when what it holds is not a class file.
     *
     * Eight bytes are enough: magic, minor, major, in that order and in network byte order.
     */
    fun readClassMajor(stream: InputStream): Int? {
        val header = ByteArray(8)

        var read = 0

        while (read < header.size) {
            val count = stream.read(header, read, header.size - read)

            if (count < 0) {
                return null
            }

            read += count
        }

        val magic = (header[0].toInt() and 0xFF shl 24) or
            (header[1].toInt() and 0xFF shl 16) or
            (header[2].toInt() and 0xFF shl 8) or
            (header[3].toInt() and 0xFF)

        if (magic != CLASS_FILE_MAGIC) {
            return null
        }

        val major = (header[6].toInt() and 0xFF shl 8) or (header[7].toInt() and 0xFF)

        return major.takeIf { it in MIN_CLASS_MAJOR..MAX_CLASS_MAJOR }
    }

    /**
     * The lowest `META-INF/versions/<n>/<path>` copy of a class, for a jar with none at the root.
     *
     * The lowest, because that is the oldest runtime the jar has a copy for and therefore the
     * lowest bar it can be started against; taking the highest would report a requirement the jar
     * does not actually have.
     */
    private fun lowestVersionedEntry(file: JarFile, path: String): java.util.jar.JarEntry? = file.entries()
        .asSequence()
        .mapNotNull { entry ->
            val name = entry.name

            if (!name.startsWith(MULTI_RELEASE_PREFIX) || !name.endsWith("/$path")) {
                return@mapNotNull null
            }

            val release = name.removePrefix(MULTI_RELEASE_PREFIX).substringBefore('/').toIntOrNull()
                ?: return@mapNotNull null

            release to entry
        }
        .minByOrNull { it.first }
        ?.second
}
