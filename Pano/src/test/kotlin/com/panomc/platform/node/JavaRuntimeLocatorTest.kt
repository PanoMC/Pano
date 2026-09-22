package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JavaRuntimeLocatorTest {
    @Test
    fun `reads a modern java version`() {
        assertEquals(21, JavaRuntimeLocator.parseMajor("21.0.4"))
        assertEquals(17, JavaRuntimeLocator.parseMajor("17"))
        assertEquals(24, JavaRuntimeLocator.parseMajor("24.0.1+9"))
        assertEquals(11, JavaRuntimeLocator.parseMajor("11.0.25"))
    }

    @Test
    fun `reads a java 8 version, where the major is the second component`() {
        assertEquals(8, JavaRuntimeLocator.parseMajor("1.8.0_432"))
        assertEquals(8, JavaRuntimeLocator.parseMajor("1.8.0"))
        assertEquals(7, JavaRuntimeLocator.parseMajor("1.7.0_80"))
    }

    @Test
    fun `refuses to guess at a version it cannot read`() {
        assertNull(JavaRuntimeLocator.parseMajor(null))
        assertNull(JavaRuntimeLocator.parseMajor(""))
        assertNull(JavaRuntimeLocator.parseMajor("   "))
        assertNull(JavaRuntimeLocator.parseMajor("openjdk"))
        assertNull(JavaRuntimeLocator.parseMajor("1.x"))
    }

    @Test
    fun `pulls JAVA_VERSION out of a release file`() {
        val release = """
            IMPLEMENTOR="Eclipse Adoptium"
            JAVA_VERSION="21.0.4"
            JAVA_VERSION_DATE="2024-07-16"
            OS_NAME="Linux"
        """.trimIndent()

        assertEquals("21.0.4", JavaRuntimeLocator.readReleaseVersion(release))
        assertEquals(21, JavaRuntimeLocator.parseMajor(JavaRuntimeLocator.readReleaseVersion(release)))
    }

    @Test
    fun `reads a java 8 release file`() {
        val release = "JAVA_VERSION=\"1.8.0_432\"\nOS_ARCH=\"amd64\"\n"

        assertEquals(8, JavaRuntimeLocator.parseMajor(JavaRuntimeLocator.readReleaseVersion(release)))
    }

    @Test
    fun `a release file without a version is not a version`() {
        assertNull(JavaRuntimeLocator.readReleaseVersion("OS_NAME=\"Linux\"\n"))
        assertNull(JavaRuntimeLocator.readReleaseVersion(""))
        assertNull(JavaRuntimeLocator.readReleaseVersion("JAVA_VERSION=\"\""))
    }

    @Test
    fun `reads the version out of what java -version prints`() {
        val output = """
            openjdk version "21.0.4" 2024-07-16 LTS
            OpenJDK Runtime Environment Temurin-21.0.4+7 (build 21.0.4+7-LTS)
            OpenJDK 64-Bit Server VM Temurin-21.0.4+7 (build 21.0.4+7-LTS, mixed mode)
        """.trimIndent()

        assertEquals("21.0.4", JavaRuntimeLocator.readProbedVersion(output))
        assertEquals(8, JavaRuntimeLocator.parseMajor(JavaRuntimeLocator.readProbedVersion("java version \"1.8.0_432\"")))
        assertNull(JavaRuntimeLocator.readProbedVersion("command not found"))
    }

    @Test
    fun `picks the highest runtime the node can actually load`() {
        val runtimes = listOf(
            runtime(11, "/usr/lib/jvm/java-11"),
            runtime(21, "/usr/lib/jvm/java-21"),
            runtime(17, "/usr/lib/jvm/java-17")
        )

        assertEquals(21, JavaRuntimeLocator.select(runtimes)?.major)
    }

    @Test
    fun `refuses every runtime below the one the daemon is compiled for`() {
        val runtimes = listOf(runtime(8, "/usr/lib/jvm/java-8"), runtime(11, "/usr/lib/jvm/java-11"))

        assertNull(JavaRuntimeLocator.select(runtimes))
        assertNull(JavaRuntimeLocator.select(emptyList()))
    }

    @Test
    fun `takes the minimum straight from the daemon's own target`() {
        assertEquals(17, JavaRuntimeLocator.MINIMUM_MAJOR)
        assertEquals(17, JavaRuntimeLocator.select(listOf(runtime(17, "/opt/java/17")))?.major)
    }

    @Test
    fun `a configured path may name the home or the launcher inside it`(@org.junit.jupiter.api.io.TempDir tempDir: File) {
        val home = File(tempDir, "jdk-21")
        val bin = File(home, "bin")

        bin.mkdirs()

        val launcher = File(bin, JavaRuntimeLocator.executableName())

        launcher.writeText("")

        assertEquals(home, JavaRuntimeLocator.homeOf(home))
        assertEquals(home, JavaRuntimeLocator.homeOf(launcher))
    }

    @Test
    fun `finds the java this test is running on`() {
        val discovered = JavaRuntimeLocator.discover()

        assertTrue(discovered.isNotEmpty(), "the JVM running the tests is itself a candidate")
        assertTrue(
            discovered.zipWithNext().all { (first, second) -> first.major >= second.major },
            "candidates come back newest first"
        )
    }

    @Test
    fun `explains itself when a configured path is not a java installation`(@org.junit.jupiter.api.io.TempDir tempDir: File) {
        val lookup = JavaRuntimeLocator.locate(File(tempDir, "nowhere").absolutePath)

        // The tests run on JDK 21, so a bogus configured path falls back rather than failing.
        assertNotNull(lookup.runtime)
        assertTrue(lookup.runtime!!.major >= JavaRuntimeLocator.MINIMUM_MAJOR)
    }

    @Test
    fun `an empty configured path is simply no configured path`() {
        assertNotNull(JavaRuntimeLocator.locate("").runtime)
        assertNotNull(JavaRuntimeLocator.locate(null).runtime)
    }

    private fun runtime(major: Int, path: String) = JavaRuntime(major, File(path), "test")
}
