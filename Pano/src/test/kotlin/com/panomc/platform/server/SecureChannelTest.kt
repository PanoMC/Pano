package com.panomc.platform.server

import com.panomc.platform.util.Aes256GcmUtil
import io.vertx.core.Vertx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.concurrent.TimeUnit

class SecureChannelTest {
    private val key = ByteArray(32) { it.toByte() }
    private val pluginNonce = ByteArray(32) { 1 }
    private val platformNonce = ByteArray(32) { 2 }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `key derivation matches the spec test vector`() {
        val (p2s, s2p) = SecureChannel.deriveKeys(key, pluginNonce, platformNonce)
        assertEquals("1b89dc9581fd9a6c5c297f7fe8de651e772cb75e96faa7a5680e983315994f30", hex(p2s.encoded))
        assertEquals("691864b6ecdb99d504ae5cc1d0f6f9991b5b02cd6da18ae870eaff2279faa149", hex(s2p.encoded))
    }

    @Test
    fun `new plugin and new platform talk in both directions`() {
        val platform = SecureChannel.forPlatform(key, pluginNonce, platformNonce)
        val plugin = SecureChannel.forPlugin(key, pluginNonce, platformNonce)

        repeat(3) { i ->
            assertEquals("""{"event":"PING","n":$i}""", platform.open(plugin.seal("""{"event":"PING","n":$i}""")))
            assertEquals("""{"r":$i}""", plugin.open(platform.seal("""{"r":$i}""")))
        }
    }

    @Test
    fun `a replayed frame is refused`() {
        val platform = SecureChannel.forPlatform(key, pluginNonce, platformNonce)
        val plugin = SecureChannel.forPlugin(key, pluginNonce, platformNonce)

        val frame = plugin.seal("""{"event":"CHANGE_PASSWORD"}""")
        platform.open(frame)

        assertThrows<SecurityException> { platform.open(frame) }
    }

    @Test
    fun `reordered and dropped frames are refused`() {
        val platform = SecureChannel.forPlatform(key, pluginNonce, platformNonce)
        val plugin = SecureChannel.forPlugin(key, pluginNonce, platformNonce)

        plugin.seal("first")
        val second = plugin.seal("second")

        assertThrows<SecurityException> { platform.open(second) }
    }

    @Test
    fun `a frame cannot be reflected back to its sender`() {
        val platform = SecureChannel.forPlatform(key, pluginNonce, platformNonce)

        val own = platform.seal("""{"event":"x"}""")

        assertThrows<SecurityException> { platform.open(own) }
    }

    @Test
    fun `a frame from another connection is refused`() {
        val otherPlugin = SecureChannel.forPlugin(key, pluginNonce, ByteArray(32) { 3 })
        val platform = SecureChannel.forPlatform(key, pluginNonce, platformNonce)

        assertThrows<SecurityException> { platform.open(otherPlugin.seal("hello")) }
    }

    @Test
    fun `a v1 frame is not accepted on a v2 connection`() {
        val platform = SecureChannel.forPlatform(key, pluginNonce, platformNonce)
        val v1 = Aes256GcmUtil.encrypt("""{"event":"PING"}""", Aes256GcmUtil.base64ToSecretKey(Base64.getEncoder().encodeToString(key)))

        assertThrows<SecurityException> { platform.open(v1) }
    }

    @Test
    fun `an old plugin that offers nothing gets the original protocol`() {
        val negotiated = SecureChannel.negotiate(null, null)

        assertTrue(negotiated.capabilities.isEmpty())
        assertNull(negotiated.platformNonce)
    }

    @Test
    fun `channel v2 is agreed only with a valid nonce`() {
        val nonce = Base64.getEncoder().encodeToString(pluginNonce)

        val ok = SecureChannel.negotiate("channel-v2, something-new", nonce)
        assertEquals(setOf("channel-v2"), ok.capabilities)
        assertEquals(32, ok.platformNonce!!.size)

        val noNonce = SecureChannel.negotiate("channel-v2", null)
        assertTrue(noNonce.capabilities.isEmpty())
        assertNull(noNonce.platformNonce)

        val shortNonce = SecureChannel.negotiate("Channel-V2", Base64.getEncoder().encodeToString(ByteArray(8)))
        assertTrue(shortNonce.capabilities.isEmpty())
    }

    @Test
    fun `the upgrade response carries the answer headers to a Vert_x client`() {
        val vertx = Vertx.vertx()

        try {
            val server = vertx.createHttpServer().requestHandler { request ->
                // Same order as ServerConnectAPI: headers on the response, then the upgrade.
                request.pause()
                request.response().putHeader(SecureChannel.CAPABILITIES_HEADER, "channel-v2")
                request.response().putHeader(SecureChannel.NONCE_HEADER, "abc")
                request.resume()
                request.toWebSocket()
            }.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

            val connected = java.util.concurrent.CompletableFuture<io.vertx.core.http.WebSocket>()
            // Held in a val and closed at the end: an unreachable Vert.x client is closed by the GC.
            val client = vertx.createWebSocketClient(io.vertx.core.http.WebSocketClientOptions())
            client.connect(server.actualPort(), "127.0.0.1", "/socket")
                .onComplete { if (it.succeeded()) connected.complete(it.result()) else connected.completeExceptionally(it.cause()) }
            val socket = connected.get(10, TimeUnit.SECONDS)

            assertEquals("channel-v2", socket.headers().get(SecureChannel.CAPABILITIES_HEADER))
            assertEquals("abc", socket.headers().get(SecureChannel.NONCE_HEADER))

            client.close()
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }
}
