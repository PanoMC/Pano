package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import com.panomc.node.config.NodePortRange
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executors

/**
 * A node's port range from the command line to the hello, and the install that asks for a port
 * outside it.
 *
 * The bug this closes: a Coolify node's container published 25660-25669, nothing told the node,
 * Pano walked up from 25565, and a managed Paper bound 25565 inside the container -- healthy in
 * every panel and unreachable from outside.
 */
class NodePortRangeTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val vertx = Vertx.vertx()

    @AfterEach
    fun closeVertx() {
        vertx.close()
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private val sent = mutableListOf<Pair<String, JsonObject>>()

    private fun resolver(reservations: PortReservations, range: IntRange) = PortResolver(
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener),
        reservations,
        PlatformConnection(vertx, logger, NodeConfig()),
        logger,
        // Every port is free: only the range decides here, whatever this machine is running.
        isFree = { true },
        send = { event, payload -> sent.add(event to payload) }
    ) { range }

    @Test
    fun `parses start-end and formats it back`() {
        assertEquals(25660..25669, NodePortRange.parse("25660-25669"))
        assertEquals(25660..25669, NodePortRange.parse(" 25660 - 25669 "))
        assertEquals("25660-25669", NodePortRange.format(25660..25669))
        assertNull(NodePortRange.parse("25669-25660"))
        assertNull(NodePortRange.parse("25660"))
        assertNull(NodePortRange.parse("1-65536"))
    }

    @Test
    fun `the hello field is start and end, both inclusive`() {
        val field = NodePortRange.toHello(NodeConfig(portRangeStart = 25660, portRangeEnd = 25669).portRange())

        assertEquals(JsonObject().put("start", 25660).put("end", 25669), field)
        assertEquals(setOf("start", "end"), field.fieldNames())
    }

    @Test
    fun `a port range given on start is written into config_conf and survives the round trip`() {
        val config = NodeConfig()

        Main.applyOverrides(config, NodeCli.parse(arrayOf("--port-range", "25660-25669")) { null }, null)

        assertEquals(25660, config.portRangeStart)
        assertEquals(25669, config.portRangeEnd)

        val reloaded = NodeConfigStore.parse(NodeConfigStore.render(config))

        assertEquals(25660..25669, reloaded.portRange())

        // A later start without the flag keeps it.
        Main.applyOverrides(reloaded, NodeCli.parse(emptyArray()) { null }, null)

        assertEquals(25660..25669, reloaded.portRange())
    }

    @Test
    fun `a port Pano asks for outside the range is moved into it and reported as SERVER_PORT_CHANGED`() {
        val reservations = PortReservations()

        reservations.reserve("server-a", 25565)

        val port = resolver(reservations, 25660..25669).resolve("server-a", requested = 25565)

        assertEquals(25660, port)
        // Claimed under the new number, so a concurrent task walks around it.
        assertEquals(setOf(25660), reservations.ports())

        assertEquals(1, sent.size)

        val (event, payload) = sent.single()

        assertEquals(NodeProtocol.Outbound.SERVER_PORT_CHANGED, event)
        assertEquals("server-a", payload.getString("serverUuid"))
        assertEquals(25565, payload.getInteger("requestedPort"))
        assertEquals(25660, payload.getInteger("port"))
        assertTrue(payload.getString("reason").contains("25660-25669"), payload.getString("reason"))
    }

    @Test
    fun `a port inside the range is kept and nothing is reported`() {
        val port = resolver(PortReservations(), 25660..25669).resolve("server-a", requested = 25665)

        assertEquals(25665, port)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an adopted directory keeps its own port when Pano left the choice open`() {
        val port = resolver(PortReservations(), 25660..25669).resolve("server-a", requested = 0, detected = 25565)

        assertEquals(25565, port)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a request outside a full range fails rather than binding outside it`() {
        val reservations = PortReservations()

        reservations.reserve("other", 25660)

        assertNull(resolver(reservations, 25660..25660).resolve("server-a", requested = 25565))
        assertTrue(sent.isEmpty())
    }
}
