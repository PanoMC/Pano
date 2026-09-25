package com.panomc.node

import com.panomc.node.host.HostPlatform
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.PlatformEndpoint
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProperties
import com.panomc.node.server.ServerSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ServerLaunchTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `builds a launch command as a list with no shell in sight`() {
        val spec = ServerSpec(software = "paper", memoryMb = 4096, jvmArgs = listOf("-XX:+UseG1GC", ""))

        val command = ServerProcess.buildCommand("/usr/bin/java", "server.jar", spec)

        assertEquals(
            listOf("/usr/bin/java", "-Xms742M", "-Xmx2970M", "-XX:+UseG1GC", "-jar", "server.jar", "nogui"),
            command
        )
    }

    @Test
    fun `leaves nogui off a proxy`() {
        val command = ServerProcess.buildCommand("java", "velocity.jar", ServerSpec(software = "velocity"))

        assertFalse(command.contains("nogui"))
    }

    @Test
    fun `strips the jvm tool options the node was started with out of a server's environment`() {
        val environment = mutableMapOf(
            "JAVA_TOOL_OPTIONS" to "-Dpano.node.jar=/opt/pano/pano-node.jar",
            "_JAVA_OPTIONS" to "-Xmx512m",
            "JDK_JAVA_OPTIONS" to "--enable-preview",
            "CLASSPATH" to "/opt/pano/pano-node.jar",
            "PATH" to "/usr/bin",
            "LANG" to "tr_TR.UTF-8",
            "LC_ALL" to "tr_TR.UTF-8"
        )

        ServerProcess.sanitizeChildEnvironment(environment)

        // Locale survives: it is what the server's own log lines are rendered with.
        assertEquals(mapOf("PATH" to "/usr/bin", "LANG" to "tr_TR.UTF-8", "LC_ALL" to "tr_TR.UTF-8", "MALLOC_ARENA_MAX" to "2"), environment)
    }

    @Test
    fun `leaves an environment that never had them alone`() {
        val environment = mutableMapOf("PATH" to "/usr/bin")

        // The allocator setting is the one thing every server process gets (JvmHeap).
        assertEquals(mapOf("PATH" to "/usr/bin", "MALLOC_ARENA_MAX" to "2"), ServerProcess.sanitizeChildEnvironment(environment))
    }

    @Test
    fun `merges server properties without losing what is already there`() {
        val file = File(directory, "server.properties")

        file.writeText("#comment\nmotd=old\nview-distance=10\n")

        ServerProperties.merge(file, mapOf("motd" to "new", "server-port" to "25570"))

        val read = ServerProperties.read(file)

        assertEquals("new", read["motd"])
        assertEquals("10", read["view-distance"])
        assertEquals("25570", read["server-port"])
    }

    @Test
    fun `refuses a property value carrying a newline`() {
        assertEquals("a b", ServerProperties.sanitiseValue("a\nb"))
        assertFalse(ServerProperties.isSafeKey("a=b"))
    }

    @Test
    fun `reads a java major out of both version shapes`() {
        assertEquals(8, JavaRuntimeLocator.parseMajor("1.8.0_432"))
        assertEquals(21, JavaRuntimeLocator.parseMajor("21.0.5"))
        assertEquals(17, JavaRuntimeLocator.parseMajor("17"))
        assertEquals(null, JavaRuntimeLocator.parseMajor("unknown"))
    }

    @Test
    fun `normalises the operating system and architecture names`() {
        assertEquals("windows", HostPlatform.normaliseOs("Windows 11"))
        assertEquals("macos", HostPlatform.normaliseOs("Mac OS X"))
        assertEquals("linux", HostPlatform.normaliseOs("Linux"))
        assertEquals("x64", HostPlatform.normaliseArch("amd64"))
        assertEquals("arm64", HostPlatform.normaliseArch("aarch64"))
    }

    @Test
    fun `never downgrades a public platform url to plain http`() {
        assertTrue(PlatformEndpoint.parse("panel.example.com").ssl)
        assertFalse(PlatformEndpoint.parse("127.0.0.1").ssl)
        assertFalse(PlatformEndpoint.parse("http://127.0.0.1:8080").ssl)
        assertEquals(8080, PlatformEndpoint.parse("http://127.0.0.1:8080").port)
        assertEquals(443, PlatformEndpoint.parse("https://panel.example.com").port)
    }

    @Test
    fun `knows when a proxy is up as well as when a game server is`() {
        assertTrue(ServerProcess.isReadyLine("[12:00:01 INFO]: Done (3.214s)! For help, type \"help\""))
        assertTrue(ServerProcess.isReadyLine("12:00:01 [INFO] Listening on /0.0.0.0:25577"))
        assertFalse(ServerProcess.isReadyLine("[12:00:00 INFO]: Starting minecraft server version 1.21.8"))
    }
}
