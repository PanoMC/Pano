package com.panomc.node

import com.panomc.node.server.JarJavaRequirement
import com.panomc.node.server.MinecraftJavaVersions
import com.panomc.node.task.ServerInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Built with real class-file headers rather than real classes: the header is the whole contract,
 * and compiling something for Java 25 inside a test would need a Java 25 compiler.
 *
 * The bug: `velocity 4.2.1-SNAPSHOT` is compiled for Java 25 (class major 69) and the Minecraft
 * ladder said 21, so the node started it on 21, the JVM threw UnsupportedClassVersionError, and
 * the crash-restart loop kept doing it.
 */
class JarJavaRequirementTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `reads the major out of the main class`() {
        val jar = writeJar("server.jar", "com.velocitypowered.proxy.Velocity", classFile(69))

        assertEquals(25, JarJavaRequirement.of(jar))
    }

    @Test
    fun `reads the majors of the runtimes people actually have`() {
        assertEquals(8, JarJavaRequirement.of(writeJar("eight.jar", "Main", classFile(52))))
        assertEquals(17, JarJavaRequirement.of(writeJar("seventeen.jar", "Main", classFile(61))))
        assertEquals(21, JarJavaRequirement.of(writeJar("twentyone.jar", "Main", classFile(65))))
    }

    @Test
    fun `has no opinion when the jar does not say`() {
        assertNull(JarJavaRequirement.of(writeJar("nomain.jar", null, classFile(65), "Other.class")))
        assertNull(JarJavaRequirement.of(writeJar("missing.jar", "Main", null)))
        assertNull(JarJavaRequirement.of(File(directory, "absent.jar")))

        // A "class file" that is not one, and one truncated before its version.
        assertNull(JarJavaRequirement.of(writeJar("notclass.jar", "Main", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))))
        assertNull(JarJavaRequirement.of(writeJar("short.jar", "Main", byteArrayOf(0xCA.toByte(), 0xFE.toByte()))))
    }

    @Test
    fun `prefers the copy at the root of a multi-release jar`() {
        val jar = writeJar("multi.jar", "Main", classFile(61))

        addEntry(jar, "META-INF/versions/25/Main.class", classFile(69))

        assertEquals(17, JarJavaRequirement.of(jar))
    }

    @Test
    fun `falls back to the lowest versioned copy when there is none at the root`() {
        val jar = writeJar("versioned.jar", "Main", null)

        addEntry(jar, "META-INF/versions/21/Main.class", classFile(65))
        addEntry(jar, "META-INF/versions/25/Main.class", classFile(69))

        assertEquals(21, JarJavaRequirement.of(jar))
    }

    @Test
    fun `a jar requirement raises the floor the minecraft ladder set`() {
        // 1.21.8 asks for 21; the jar asks for 25, and 21 is then not an answer at all.
        assertEquals(25, MinecraftJavaVersions.pick(listOf(17, 21, 25), "1.21.8", 25))
        assertNull(MinecraftJavaVersions.pick(listOf(17, 21), "1.21.8", 25))

        // Without one, nothing about the existing ladder moves.
        assertEquals(21, MinecraftJavaVersions.pick(listOf(17, 21, 25), "1.21.8"))
        assertEquals(21, MinecraftJavaVersions.pick(listOf(17, 21, 25), "1.21.8", 17))
    }

    @Test
    fun `a jar requirement beats the ladder's ceiling for an old version`() {
        // 1.16.5 is capped at Java 16, but a jar compiled for 17 cannot load on 16 whatever the
        // version number is -- so the jar wins rather than the server refusing to start at all.
        assertEquals(17, MinecraftJavaVersions.pick(listOf(8, 16, 17), "1.16.5", 17))
    }

    @Test
    fun `an import takes the higher of the two requirements`() {
        val jar = writeJar("import.jar", "Main", classFile(69))

        assertEquals(25, ServerInspection.javaMajorFor("1.21.8", jar))
        assertEquals(21, ServerInspection.javaMajorFor("1.21.8", writeJar("paper.jar", "Main", classFile(65))))
        assertEquals(25, ServerInspection.javaMajorFor(null, jar))
        assertNull(ServerInspection.javaMajorFor(null, null))
    }

    /** A class-file header: the magic, a minor version, and the major that decides everything. */
    private fun classFile(major: Int): ByteArray = byteArrayOf(
        0xCA.toByte(),
        0xFE.toByte(),
        0xBA.toByte(),
        0xBE.toByte(),
        0,
        0,
        (major shr 8).toByte(),
        (major and 0xFF).toByte(),
        // Whatever a real class file holds after the header; nothing here reads it.
        0,
        0
    )

    private fun writeJar(name: String, mainClass: String?, body: ByteArray?, entryName: String? = null): File {
        val file = File(directory, name)

        val manifest = Manifest()

        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"

        mainClass?.let { manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = it }

        JarOutputStream(file.outputStream(), manifest).use { stream ->
            if (body == null) {
                return@use
            }

            val path = entryName ?: ((mainClass ?: "Main").replace('.', '/') + ".class")

            stream.putNextEntry(JarEntry(path))
            stream.write(body)
            stream.closeEntry()
        }

        return file
    }

    /** Rewrites [jar] with one more entry, since a jar stream cannot be reopened for append. */
    private fun addEntry(jar: File, path: String, body: ByteArray) {
        val existing = LinkedHashMap<String, ByteArray>()

        var manifest: Manifest? = null

        java.util.jar.JarFile(jar).use { file ->
            manifest = file.manifest

            file.entries().asSequence().filterNot { it.isDirectory || it.name == "META-INF/MANIFEST.MF" }
                .forEach { entry -> existing[entry.name] = file.getInputStream(entry).readBytes() }
        }

        existing[path] = body

        val stream = manifest?.let { JarOutputStream(jar.outputStream(), it) } ?: JarOutputStream(jar.outputStream())

        stream.use { out ->
            existing.forEach { (name, bytes) ->
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }
}
