package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.net.PlatformConnection
import com.panomc.node.util.NodeLogger
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.net.NetSocket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The liveness watchdog against real sockets.
 *
 * "Pano" is a WebSocket server that accepts the node and never says a word, so the only thing that
 * ever arrives on an idle link is the pong Vert.x answers the node's pings with. Between the two
 * sits a TCP relay that can go silent the way a dead ngrok tunnel does: the connection stays open
 * and nothing comes back through it, in either direction.
 */
class PlatformConnectionLivenessTest {
    private val vertx = Vertx.vertx()

    private val output = ByteArrayOutputStream()

    private val logger = NodeLogger("test", PrintStream(output, true))

    /** WebSocket upgrades "Pano" accepted, one per connection the node made. */
    private val accepted = AtomicInteger()

    private val relays = CopyOnWriteArrayList<Relay>()

    private var connection: PlatformConnection? = null

    private class Relay(val node: NetSocket) {
        @Volatile
        var silent = false
    }

    @AfterEach
    fun tearDown() {
        connection?.stop()

        vertx.close().join()
    }

    @Test
    fun `keeps an idle link whose pings are answered and reconnects once it goes silent`() {
        val relayPort = startRelay(startPano())

        val config = NodeConfig().apply {
            platformUrl = "http://127.0.0.1:$relayPort"
            token = "token"
            encryptionKey = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        }

        val connection = PlatformConnection(
            vertx,
            logger,
            config,
            pingIntervalMillis = PING_INTERVAL_MILLIS,
            livenessTimeoutMillis = LIVENESS_TIMEOUT_MILLIS
        ).also { this.connection = it }

        connection.start()

        awaitUntil("the first connection") { accepted.get() == 1 && connection.isConnected() }

        // Twice the timeout with no message at all: only the pongs are keeping this link alive.
        Thread.sleep(LIVENESS_TIMEOUT_MILLIS * 2)

        assertEquals(1, accepted.get())
        assertTrue(connection.isConnected())
        assertEquals(0, count("No answer from Pano"))

        // The tunnel dies without closing anything.
        val dead = relays.toList()

        dead.forEach { it.silent = true }

        awaitUntil("the watchdog to give the socket up") { count("No answer from Pano") == 1 }

        assertFalse(connection.isConnected())

        // Now the abandoned socket really closes, while the watchdog's reconnect is still waiting
        // out its backoff. Its close handler must not schedule a second one.
        dead.forEach { it.node.close() }

        awaitUntil("the reconnect") { accepted.get() == 2 && connection.isConnected() }

        // Past the point where a second reconnect timer, backed off to six seconds, would have fired.
        Thread.sleep(4_000)

        assertEquals(2, accepted.get())
        assertTrue(connection.isConnected())
        assertEquals(1, count("No answer from Pano"))
        assertEquals(0, count("Lost the connection"))
        assertEquals(1, count("[WARN]"), log())
    }

    private fun startPano(): Int = vertx.createHttpServer()
        .webSocketHandler { accepted.incrementAndGet() }
        .listen(0, "127.0.0.1")
        .join()
        .actualPort()

    private fun startRelay(panoPort: Int): Int {
        val client = vertx.createNetClient()

        return vertx.createNetServer()
            .connectHandler { node ->
                node.pause()

                client.connect(panoPort, "127.0.0.1").onSuccess { pano ->
                    val relay = Relay(node)

                    relays += relay

                    node.handler { if (!relay.silent) pano.write(it) }
                    pano.handler { if (!relay.silent) node.write(it) }

                    node.closeHandler { pano.close() }
                    pano.closeHandler { node.close() }

                    node.resume()
                }
            }
            .listen(0, "127.0.0.1")
            .join()
            .actualPort()
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS

        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail<Unit>("Timed out waiting for $what. Log:\n${log()}")
            }

            Thread.sleep(20)
        }
    }

    private fun log(): String = output.toString(Charsets.UTF_8)

    private fun count(needle: String) = log().lines().count { it.contains(needle) }

    private fun <T> Future<T>.join(): T = toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

    companion object {
        private const val PING_INTERVAL_MILLIS = 100L
        private const val LIVENESS_TIMEOUT_MILLIS = 1_000L

        private const val AWAIT_MILLIS = 15_000L
    }
}
