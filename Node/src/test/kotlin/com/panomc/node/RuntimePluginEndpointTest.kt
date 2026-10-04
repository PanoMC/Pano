package com.panomc.node

import com.panomc.node.net.PlatformEndpoint
import com.panomc.node.server.DockerCommands
import com.panomc.node.server.DockerRuntime
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.io.PrintStream

/**
 * What address an auto-installed Pano plugin is told to reach Pano on, per runtime.
 *
 * The bug this pins down was found on a real VPS: the node reached Pano at `127.0.0.1:18088`, the
 * plugin config for a containerised server was written with that verbatim, and inside the
 * container it named the container.
 */
class RuntimePluginEndpointTest {
    private val docker = DockerRuntime(NodeLogger("test", PrintStream(OutputStream.nullOutputStream()))) {
        DockerRuntime.CommandResult(true, "")
    }

    @Test
    fun `a process runtime hands back the address the node itself uses`() {
        val endpoint = PlatformEndpoint("127.0.0.1", 18088, false)

        assertEquals(endpoint, ProcessRuntime().pluginEndpoint(endpoint))
    }

    @Test
    fun `a container is pointed at the host instead of its own loopback`() {
        val mapped = docker.pluginEndpoint(PlatformEndpoint("127.0.0.1", 18088, false))

        assertEquals(PlatformEndpoint(DockerCommands.HOST_ALIAS, 18088, false), mapped)
        assertEquals("host.docker.internal", mapped?.host)
    }

    @Test
    fun `the port and the scheme survive the rewrite untouched`() {
        assertEquals(
            PlatformEndpoint(DockerCommands.HOST_ALIAS, 8443, true),
            docker.pluginEndpoint(PlatformEndpoint("localhost", 8443, true))
        )
    }

    @Test
    fun `every loopback address is rewritten`() {
        listOf("127.0.0.1", "localhost", "::1", "0.0.0.0").forEach { host ->
            assertEquals(
                DockerCommands.HOST_ALIAS,
                docker.pluginEndpoint(PlatformEndpoint(host, 80, false))?.host,
                host
            )
        }
    }

    @Test
    fun `a Pano on another LAN host is reachable through NAT and is left alone`() {
        listOf("10.0.0.5", "192.168.1.20", "172.17.0.1").forEach { host ->
            assertEquals(host, docker.pluginEndpoint(PlatformEndpoint(host, 80, false))?.host, host)
        }
    }

    @Test
    fun `a Pano on a public address is left exactly as it is`() {
        val endpoint = PlatformEndpoint("panomc.com", 443, true)

        assertEquals(endpoint, docker.pluginEndpoint(endpoint))
    }

    @Test
    fun `no address stays no address, so the installer falls back to Pano's own guess`() {
        assertNull(docker.pluginEndpoint(null))
        assertNull(ProcessRuntime().pluginEndpoint(null))
    }
}
