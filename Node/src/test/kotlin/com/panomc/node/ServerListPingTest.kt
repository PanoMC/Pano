package com.panomc.node

import com.panomc.node.server.ServerListPing
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The framing and the JSON, without a socket anywhere.
 *
 * Which is the whole reason [ServerListPing.Dialer] exists: a test that needed a real Minecraft
 * server to check that a VarInt is little-endian-ish and that a nested chat component flattens is
 * a test nobody would run.
 */
class ServerListPingTest {
    @Test
    fun `varint round trips every value the protocol carries`() {
        listOf(0, 1, 2, 127, 128, 255, 25565, 2_097_151, Int.MAX_VALUE, -1, Int.MIN_VALUE).forEach { value ->
            assertEquals(value, ServerListPing.VarInt.decode(ServerListPing.VarInt.encode(value)))
        }
    }

    @Test
    fun `varint is the seven-bits-a-byte encoding Minecraft documents`() {
        assertArrayEquals(byteArrayOf(0), ServerListPing.VarInt.encode(0))
        assertArrayEquals(byteArrayOf(127), ServerListPing.VarInt.encode(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ServerListPing.VarInt.encode(128))

        // -1 is the protocol version this daemon sends, so its encoding is not academic.
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
            ServerListPing.VarInt.encode(-1)
        )
    }

    @Test
    fun `a varint that never ends is refused rather than read`() {
        val endless = ByteArray(16) { 0x80.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            ServerListPing.VarInt.read(endless.inputStream())
        }

        assertThrows(IllegalArgumentException::class.java) {
            ServerListPing.VarInt.read(ByteArray(0).inputStream())
        }
    }

    @Test
    fun `the handshake asks for the status state and names the address it dialled`() {
        val written = ByteArrayOutputStream()

        ServerListPing.writeHandshake(written, "127.0.0.1", 25565)

        val bytes = written.toByteArray()
        val stream = bytes.inputStream()

        val length = ServerListPing.VarInt.read(stream)

        assertEquals(bytes.size - ServerListPing.VarInt.encode(length).size, length)
        assertEquals(ServerListPing.PACKET_HANDSHAKE, ServerListPing.VarInt.read(stream))
        assertEquals(ServerListPing.PROTOCOL_ANY, ServerListPing.VarInt.read(stream))

        val host = ByteArray(ServerListPing.VarInt.read(stream))

        assertEquals(host.size, stream.read(host))
        assertEquals("127.0.0.1", String(host, Charsets.UTF_8))

        // The one big-endian unsigned short in the packet.
        assertEquals(25565, (stream.read() shl 8) or stream.read())
        assertEquals(ServerListPing.STATE_STATUS, ServerListPing.VarInt.read(stream))
    }

    @Test
    fun `reads a whole status off a channel that is not a socket`() {
        val json = """
            {
              "version": { "name": "Paper 1.21.1", "protocol": 767 },
              "players": {
                "max": 50,
                "online": 2,
                "sample": [
                  { "name": "kahverengi", "id": "3d1b0e0a-0000-4000-8000-000000000001" },
                  { "name": "someone", "id": "3d1b0e0a-0000-4000-8000-000000000002" }
                ]
              },
              "description": { "text": "A Pano server" }
            }
        """.trimIndent()

        val sent = ByteArrayOutputStream()

        val status = ServerListPing.status("127.0.0.1", 25565, 500) { _, _, _ ->
            channel(ByteArrayInputStream(statusResponse(json)), sent)
        }

        assertNotNull(status)
        assertEquals(2, status!!.online)
        assertEquals(50, status.max)
        assertEquals("A Pano server", status.motd)
        assertEquals("Paper 1.21.1", status.version?.name)
        assertEquals(767, status.version?.protocol)
        assertEquals(listOf("kahverengi", "someone"), status.sample.map { it.name })
        assertEquals("3d1b0e0a-0000-4000-8000-000000000001", status.sample.first().id)

        // Both packets went out before anything was read back.
        assertTrue(sent.size() > 0)
    }

    @Test
    fun `a legacy or hung server is a null rather than an exception`() {
        // What a pre-1.7 server answers: a 0xFF kick packet, which is not a status at all.
        val legacy = byteArrayOf(0xFF.toByte(), 0x00, 0x01)

        assertNull(
            ServerListPing.status("127.0.0.1", 25565, 500) { _, _, _ ->
                channel(ByteArrayInputStream(legacy), ByteArrayOutputStream())
            }
        )

        assertNull(
            ServerListPing.status("127.0.0.1", 25565, 500) { _, _, _ ->
                throw java.net.ConnectException("Connection refused")
            }
        )
    }

    @Test
    fun `a port that cannot be a port is never dialled`() {
        var dialled = false

        assertNull(
            ServerListPing.status("127.0.0.1", 0, 500) { _, _, _ ->
                dialled = true

                channel(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
            }
        )

        assertFalse(dialled)
    }

    @Test
    fun `a description is a string on some servers and a component tree on others`() {
        assertEquals("Plain", ServerListPing.parse("""{"description":"Plain"}""")?.motd)

        assertEquals(
            "Welcome to Pano!",
            ServerListPing.parse(
                """{"description":{"text":"Welcome ","extra":[{"text":"to "},{"text":"Pano!","color":"gold"}]}}"""
            )?.motd
        )

        // Legacy colour codes are formatting, and the panel wants the sentence.
        assertEquals(
            "Survival",
            ServerListPing.parse("""{"description":{"text":"§aSurvival§r"}}""")?.motd
        )
    }

    @Test
    fun `a proxy with nobody online still answers with its counts`() {
        val status = ServerListPing.parse(
            """{"version":{"name":"Velocity 3.4.0","protocol":-1},"players":{"online":0,"max":500}}"""
        )

        assertNotNull(status)
        assertEquals(0, status!!.online)
        assertEquals(500, status.max)
        assertTrue(status.sample.isEmpty())
        assertNull(status.motd)
    }

    @Test
    fun `a sample bigger than the panel shows is cut, not refused`() {
        val names = (1..30).joinToString(",") { """{"name":"player$it","id":"id$it"}""" }

        val status = ServerListPing.parse("""{"players":{"online":30,"max":100,"sample":[$names]}}""")

        assertEquals(ServerListPing.MAX_SAMPLE, status?.sample?.size)
        assertEquals("player1", status?.sample?.first()?.name)
    }

    @Test
    fun `anything that is not a status comes back as nothing`() {
        assertNull(ServerListPing.parse(""))
        assertNull(ServerListPing.parse("not json at all"))
        assertNull(ServerListPing.parse("[]"))
        assertNull(ServerListPing.parse("{}"))
        assertNull(ServerListPing.parse("""{"favicon":"data:image/png;base64,AAA"}"""))
    }

    @Test
    fun `a status packet longer than the limit is not allocated`() {
        val oversized = ByteArrayOutputStream()

        ServerListPing.VarInt.write(oversized, ServerListPing.MAX_PACKET_BYTES + 1)

        assertThrows(IllegalArgumentException::class.java) {
            ServerListPing.readStatusJson(oversized.toByteArray().inputStream())
        }
    }

    /** One status response, framed the way a server frames it. */
    private fun statusResponse(json: String): ByteArray {
        val body = ByteArrayOutputStream()

        ServerListPing.VarInt.write(body, ServerListPing.PACKET_STATUS_RESPONSE)

        val text = json.toByteArray(Charsets.UTF_8)

        ServerListPing.VarInt.write(body, text.size)

        body.write(text)

        val packet = ByteArrayOutputStream()

        ServerListPing.VarInt.write(packet, body.size())

        packet.write(body.toByteArray())

        return packet.toByteArray()
    }

    private fun channel(from: InputStream, to: OutputStream): ServerListPing.Channel =
        object : ServerListPing.Channel {
            override val input: InputStream get() = from
            override val output: OutputStream get() = to

            override fun close() = Unit
        }
}
