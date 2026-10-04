package com.panomc.node

import com.panomc.node.java.TarGzExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** The pure-JVM tar reader the Java installer unpacks JREs with (SM-63, §2.4.28). */
class TarGzExtractorTest {
    @TempDir
    lateinit var dir: File

    private val target get() = File(dir, "out")

    private fun extract(builder: TarBuilder) = TarGzExtractor.extract(ByteArrayInputStream(builder.bytes()), target)

    private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @Test
    fun `extracts files and directories from a gzipped archive`() {
        val archive = TarBuilder()
            .directory("jdk-21/")
            .directory("jdk-21/bin/")
            .file("jdk-21/bin/java", "#!/bin/sh\n", mode = 0b111_101_101)
            .file("jdk-21/release", "JAVA_VERSION=\"21.0.12\"\n")
            .writeTarGz(File(dir, "a.tar.gz"))

        TarGzExtractor.extract(archive, target)

        assertEquals("#!/bin/sh\n", File(target, "jdk-21/bin/java").readText())
        assertEquals("JAVA_VERSION=\"21.0.12\"\n", File(target, "jdk-21/release").readText())
    }

    @Test
    fun `reads GNU long names`() {
        val long = "jdk-21/legal/" + "a".repeat(150) + "/LICENSE"

        extract(TarBuilder().gnuLongName(long).file("truncated-name", "licence", gnuMagic = true))

        assertEquals("licence", File(target, long).readText())
        assertFalse(File(target, "truncated-name").exists())
    }

    @Test
    fun `reads the ustar prefix field`() {
        extract(TarBuilder().file("LICENSE", "text", prefix = "jdk-21/legal/java.base"))

        assertEquals("text", File(target, "jdk-21/legal/java.base/LICENSE").readText())
    }

    @Test
    fun `reads pax path and linkpath records`() {
        val leaf = "b".repeat(120) + ".so"
        val long = "jdk-21/lib/$leaf"

        extract(
            TarBuilder()
                .pax(mapOf("path" to long, "mtime" to "1700000000.5"))
                .file("short", "native")
                .pax(mapOf("linkpath" to leaf))
                .symlink("jdk-21/lib/alias.so", "placeholder")
        )

        assertEquals("native", File(target, long).readText())

        assertEquals("native", File(target, "jdk-21/lib/alias.so").readText())
    }

    @Test
    fun `a global pax header applies to the entries after it`() {
        extract(TarBuilder().pax(mapOf("comment" to "built by CI"), global = true).file("jdk/a", "1").file("jdk/b", "2"))

        assertEquals("1", File(target, "jdk/a").readText())
        assertEquals("2", File(target, "jdk/b").readText())
    }

    @Test
    fun `symlinks inside the runtime are kept`() {
        extract(
            TarBuilder()
                .file("jdk/lib/libjli.so", "jli")
                .symlink("jdk/bin/libjli.so", "../lib/libjli.so")
        )

        val link = File(target, "jdk/bin/libjli.so")

        assertEquals("jli", link.readText())
    }

    @Test
    fun `a symlink escaping the runtime is refused`() {
        assertThrows(IllegalStateException::class.java) {
            extract(TarBuilder().symlink("jdk/bin/evil", "../../../etc/passwd"))
        }

        assertThrows(IllegalStateException::class.java) {
            extract(TarBuilder().symlink("jdk/bin/evil2", "/etc/passwd"))
        }
    }

    @Test
    fun `absolute and climbing entry paths are refused`() {
        assertThrows(IllegalStateException::class.java) {
            extract(TarBuilder().file("/etc/cron.d/evil", "x"))
        }

        assertThrows(IllegalStateException::class.java) {
            extract(TarBuilder().file("jdk/../../evil", "x"))
        }

        assertFalse(File(dir, "evil").exists())
    }

    @Test
    fun `writing through an earlier symlink cannot leave the runtime`() {
        // The link itself points inside; the file after it goes through a *directory* link that
        // was made to look inside but would land outside once resolved.
        val outside = File(dir, "outside").apply { mkdirs() }

        assumeTrue(runCatching {
            Files.createSymbolicLink(File(dir, "probe").toPath(), outside.toPath())
        }.isSuccess)

        target.mkdirs()
        Files.createSymbolicLink(File(target, "jdk").toPath(), outside.toPath())

        assertThrows(IllegalArgumentException::class.java) {
            extract(TarBuilder().file("jdk/planted", "x"))
        }

        assertFalse(File(outside, "planted").exists())
    }

    @Test
    fun `hard links become copies`() {
        extract(TarBuilder().file("jdk/lib/a.so", "same").hardlink("jdk/lib/b.so", "jdk/lib/a.so"))

        assertEquals("same", File(target, "jdk/lib/b.so").readText())
        assertFalse(Files.isSymbolicLink(File(target, "jdk/lib/b.so").toPath()))
    }

    @Test
    fun `unix modes are applied`() {
        assumeTrue(posix)

        extract(
            TarBuilder()
                .file("jdk/bin/java", "bin", mode = 0b111_101_101)
                .file("jdk/lib/jspawnhelper", "helper", mode = 0b111_101_101)
                .file("jdk/conf/readonly", "conf", mode = 0b100_100_100)
                .directory("jdk/conf/", mode = 0b101_101_101)
        )

        val java = Files.getPosixFilePermissions(File(target, "jdk/bin/java").toPath())

        assertTrue(PosixFilePermission.OWNER_EXECUTE in java)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in java)
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(File(target, "jdk/lib/jspawnhelper").toPath()))

        // Read-only in the archive, but the node must be able to delete it again.
        val conf = Files.getPosixFilePermissions(File(target, "jdk/conf/readonly").toPath())

        assertTrue(PosixFilePermission.OWNER_WRITE in conf)
        assertFalse(PosixFilePermission.OWNER_EXECUTE in conf)

        assertTrue(PosixFilePermission.OWNER_WRITE in Files.getPosixFilePermissions(File(target, "jdk/conf").toPath()))
    }

    @Test
    fun `a truncated archive is an error`() {
        val bytes = TarBuilder().file("jdk/big", "x".repeat(2000)).bytes()

        assertThrows(Exception::class.java) {
            TarGzExtractor.extract(ByteArrayInputStream(bytes.copyOf(900)), target)
        }
    }

    @Test
    fun `something that is not a tar is an error`() {
        assertThrows(IllegalStateException::class.java) {
            TarGzExtractor.extract(ByteArrayInputStream("<html>not found</html>".padEnd(1024).toByteArray()), target)
        }
    }

    @Test
    fun `parses pax records`() {
        val parsed = TarGzExtractor.parsePax("31 path=jdk/some/long/name.txt\n15 size=123456\n".toByteArray())

        assertEquals("jdk/some/long/name.txt", parsed["path"])
        assertEquals("123456", parsed["size"])
    }
}
