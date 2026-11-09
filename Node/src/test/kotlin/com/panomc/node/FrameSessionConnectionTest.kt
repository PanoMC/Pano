package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.crypto.Aes256GcmUtil
import com.panomc.node.net.FrameSession
import com.panomc.node.net.PlatformConnection
import com.panomc.node.util.NodeLogger
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
 * Frame sessions (protocol 9) against real sockets: a new node with a new Pano, with an old Pano,
 * and a node that has seen a new Pano refusing to fall back. "Pano" here follows the wire format of
 * Pano's `NodeFrameChannel` (tested on its own in the Pano module).
 */
class FrameSessionConnectionTest {
    private val vertx = Vertx.vertx()

    private val output = ByteArrayOutputStream()

    private val logger = NodeLogger("test", PrintStream(output, true))

    private val keyText = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private val key = Aes256GcmUtil.base64ToSecretKey(keyText)

    private var connection: PlatformConnection? = null

    private lateinit var config: NodeConfig

    /** What "Pano" decrypted from the node, in order. */
    private val received = CopyOnWriteArrayList<JsonObject>()

    /** The node-side handler calls for the test event. */
    private val handled = AtomicInteger()

    private val accepted = AtomicInteger()

    @AfterEach
    fun tearDown() {
        connection?.stop()

        vertx.close().join()
    }

    private fun startPano(onSocket: (ServerWebSocket) -> Unit): Int = vertx.createHttpServer()
        .webSocketHandler { socket ->
            accepted.incrementAndGet()

            socket.textMessageHandler { received += JsonObject(Aes256GcmUtil.decrypt(it, key)) }

            onSocket(socket)
        }
        .listen(0, "127.0.0.1")
        .join()
        .actualPort()

    private fun connect(port: Int, learned: Boolean = false): PlatformConnection {
        config = NodeConfig().apply {
            platformUrl = "http://127.0.0.1:$port"
            token = "token"
            encryptionKey = keyText
            frameSession = learned
        }

        return PlatformConnection(vertx, logger, config, sessionWaitMillis = SESSION_WAIT).also { connection ->
            this.connection = connection

            connection.on(TEST_EVENT) { handled.incrementAndGet() }
            connection.onConnected { connection.send("NODE_HELLO", JsonObject().put("protocolVersion", 9)) }
            connection.start()
        }
    }

    private fun ServerWebSocket.sendFrame(frame: JsonObject) {
        writeTextMessage(Aes256GcmUtil.encrypt(frame.encode(), key))
    }

    @Test
    fun `a new node and a new Pano seal every frame and drop replays`() {
        var pano: FrameSession? = null
        var socket: ServerWebSocket? = null

        val port = startPano { ws ->
            val client = ws.headers().get(FrameSession.HEADER)!!
            val server = FrameSession.newNonce()

            pano = FrameSession(FrameSession.sessionId(client, server), FrameSession.FROM_PANO, FrameSession.FROM_NODE)
            socket = ws

            ws.sendFrame(JsonObject().put("event", FrameSession.EVENT).put("client", client).put("server", server))
        }

        connect(port)

        awaitUntil("the sealed hello") { received.isNotEmpty() }

        val hello = received.first()

        assertEquals("NODE_HELLO", hello.getString("event"))
        assertTrue(pano!!.accept(hello), "the hello is sealed to this connection: $hello")
        assertFalse(pano!!.accept(hello), "and only accepted once")
        assertTrue(config.frameSession, "the node remembers that this Pano seals its frames")

        val command = pano!!.seal(JsonObject().put("event", TEST_EVENT))

        socket!!.sendFrame(command)

        awaitUntil("the command") { handled.get() == 1 }

        // The same frame again, an unsealed one and one of another session: none reach a handler.
        socket!!.sendFrame(command)
        socket!!.sendFrame(JsonObject().put("event", TEST_EVENT))
        socket!!.sendFrame(FrameSession("other.session", FrameSession.FROM_PANO, FrameSession.FROM_NODE).seal(JsonObject().put("event", TEST_EVENT)))

        socket!!.sendFrame(pano!!.seal(JsonObject().put("event", TEST_EVENT)))

        awaitUntil("the next fresh command") { handled.get() == 2 }

        Thread.sleep(200)

        assertEquals(2, handled.get())
    }

    @Test
    fun `a new node talks the old way to an old Pano`() {
        val port = startPano { ws ->
            // An old Pano: no answer to the header, frames as they always were.
            ws.textMessageHandler { text ->
                received += JsonObject(Aes256GcmUtil.decrypt(text, key))

                ws.sendFrame(JsonObject().put("event", TEST_EVENT))
            }
        }

        connect(port)

        awaitUntil("the hello after the wait") { received.isNotEmpty() }

        assertNull(received.first().getValue(FrameSession.ID_FIELD), "an old Pano gets the old frames")

        awaitUntil("the old Pano's command") { handled.get() == 1 }

        assertFalse(config.frameSession)
    }

    @Test
    fun `a node that has seen frame sessions never falls back`() {
        val port = startPano { ws ->
            ws.textMessageHandler { text -> received += JsonObject(Aes256GcmUtil.decrypt(text, key)) }

            ws.sendFrame(JsonObject().put("event", TEST_EVENT))
        }

        connect(port, learned = true)

        awaitUntil("the node to give the socket up") { output.toString().contains("did not open a protected connection") }

        Thread.sleep(200)

        assertEquals(0, handled.get(), "nothing from an unprotected socket is handled")
        assertTrue(received.isEmpty(), "and nothing is sent on one")
    }

    private fun <T> Future<T>.join(): T = toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000

        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail<Unit>("Timed out waiting for $what. Log:\n$output")
            }

            Thread.sleep(20)
        }
    }

    private companion object {
        const val TEST_EVENT = "TEST_EVENT"
        const val SESSION_WAIT = 500L
    }
}
