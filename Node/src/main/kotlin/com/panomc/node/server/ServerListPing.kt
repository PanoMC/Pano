package com.panomc.node.server

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What a Minecraft server answers the server list with, asked over its own game port (§2.4.17 B).
 *
 * This is the node's answer to "who is online" on a server with no Pano plugin in it. Every
 * Minecraft server since 1.7 -- and every proxy, which is the case that matters most, because a
 * Velocity or BungeeCord network answers for the whole network -- serves the status handshake to
 * anybody who connects, without logging in and without a mod. So the daemon asks the same question
 * the player's server list asks, on loopback, and Pano gets a player count, a sample of names, the
 * MOTD and the version out of a server it cannot otherwise see inside.
 *
 * The protocol is three packets long and all of it is VarInt-framed:
 *
 * 1. handshake -- packet 0x00, protocol version, address, port, next state `1` (status)
 * 2. status request -- packet 0x00, empty
 * 3. status response -- packet 0x00, one JSON string
 *
 * [PROTOCOL_ANY] is sent as the protocol version on purpose: the status ping is version
 * independent, and a real number would make this daemon claim to be a client build it is not.
 * Servers answer -1 exactly as they answer anything else.
 *
 * The socket lives behind [Dialer] so the framing and the JSON can be exercised without one. A
 * legacy (pre-1.7) server answers with a kick packet this cannot read, a stopped one refuses the
 * connection, and both come back as null rather than as an exception: a failed ping is one less
 * number on a panel, never a broken metrics tick.
 */
object ServerListPing {
    /** One player out of the status sample, which servers cap at a handful of names themselves. */
    data class Player(val name: String, val id: String?)

    data class Version(val name: String?, val protocol: Int?)

    data class Status(
        val online: Int?,
        val max: Int?,
        val sample: List<Player>,
        val version: Version?,
        /** The MOTD as plain text: chat components flattened, legacy colour codes removed. */
        val motd: String?
    )

    /** The two streams of one open connection, so a test can supply bytes instead of a socket. */
    interface Channel : Closeable {
        val input: InputStream
        val output: OutputStream
    }

    fun interface Dialer {
        fun open(host: String, port: Int, timeoutMillis: Int): Channel
    }

    /**
     * Pings `host:port` and returns what it said, or null for anything that did not work.
     *
     * Blocking, so it belongs on a worker: it is called from the metrics timer's blocking block
     * and nowhere else.
     */
    fun status(
        host: String,
        port: Int,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        dialer: Dialer = SocketDialer
    ): Status? {
        if (host.isBlank() || port !in 1..65535) {
            return null
        }

        return try {
            dialer.open(host, port, timeoutMillis).use { channel ->
                writeHandshake(channel.output, host, port)
                writeStatusRequest(channel.output)

                channel.output.flush()

                // A socket timeout bounds one read, not the exchange: a server dripping a byte
                // every one and a half seconds would hold this worker for as long as it cared to.
                parse(readStatusJson(untilDeadline(channel.input, System.nanoTime() + timeoutMillis * 1_000_000L)))
            }
        } catch (_: Exception) {
            null
        }
    }

    /** [input], refusing to read once [at] has passed. */
    private fun untilDeadline(input: InputStream, at: Long): InputStream = object : FilterInputStream(input) {
        override fun read(): Int {
            checkDeadline()

            return super.read()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkDeadline()

            return super.read(bytes, offset, length)
        }

        private fun checkDeadline() {
            require(System.nanoTime() <= at) { "The ping ran out of time." }
        }
    }

    /** The handshake that tells the server this connection is asking for a status, not a login. */
    fun writeHandshake(output: OutputStream, host: String, port: Int) {
        val body = ByteArrayOutputStream()

        VarInt.write(body, PACKET_HANDSHAKE)
        VarInt.write(body, PROTOCOL_ANY)
        writeString(body, host)

        // The one field that is not a VarInt: an unsigned short, big endian.
        body.write((port ushr 8) and 0xFF)
        body.write(port and 0xFF)

        VarInt.write(body, STATE_STATUS)

        writePacket(output, body.toByteArray())
    }

    /** The empty packet that asks for the status JSON. */
    fun writeStatusRequest(output: OutputStream) {
        val body = ByteArrayOutputStream()

        VarInt.write(body, PACKET_STATUS_REQUEST)

        writePacket(output, body.toByteArray())
    }

    /**
     * Reads one status response and returns the JSON string inside it.
     *
     * Bounded twice -- the frame length and the string length are both checked before anything is
     * allocated -- because the other end of this socket is a process the operator installed, not
     * necessarily one that is behaving.
     */
    fun readStatusJson(input: InputStream): String {
        val length = VarInt.read(input)

        require(length in 1..MAX_PACKET_BYTES) { "Status packet length $length is out of range." }

        val packet = readFully(input, length).inputStream()

        val packetId = VarInt.read(packet)

        require(packetId == PACKET_STATUS_RESPONSE) { "Expected a status response, got packet $packetId." }

        return readString(packet)
    }

    /**
     * Turns the status JSON into a [Status], or null when it is not a status at all.
     *
     * Every field is optional. A proxy with no players online sends no sample, a heavily modded
     * server sends a description built out of nested components, and some send `players` and
     * nothing else -- all of which are answers, so none of them is allowed to lose the rest.
     */
    fun parse(json: String): Status? {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return null
        }

        if (root == null || !root.isJsonObject) {
            return null
        }

        val objectRoot = root.asJsonObject

        val players = objectRoot.get("players")?.takeIf { it.isJsonObject }?.asJsonObject
        val versionObject = objectRoot.get("version")?.takeIf { it.isJsonObject }?.asJsonObject

        val sample = players
            ?.get("sample")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                val entry = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = entry.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                Player(stripColours(name), entry.string("id"))
            }
            ?.take(MAX_SAMPLE)
            .orEmpty()

        val version = versionObject?.let {
            Version(it.string("name")?.let(::stripColours), it.int("protocol"))
        }

        val status = Status(
            online = players?.int("online"),
            max = players?.int("max"),
            sample = sample,
            version = version,
            motd = objectRoot.get("description")?.let { flatten(it) }?.takeIf { it.isNotBlank() }
        )

        // Something that parsed as JSON but carries none of the four facts is not a status, and
        // reporting an empty one would tell the panel the ping worked.
        if (status.online == null && status.max == null && status.version == null && status.motd == null) {
            return null
        }

        return status
    }

    /**
     * A chat component as the plain text a person reads.
     *
     * A description is a string on some servers, `{ "text": ... }` on most, and a tree of `extra`
     * parts on anything that colours its MOTD. All three flatten to the same sentence, which is
     * the only part of it Pano shows.
     */
    fun flatten(element: JsonElement?): String {
        if (element == null || element.isJsonNull) {
            return ""
        }

        if (element.isJsonPrimitive) {
            return stripColours(element.asString)
        }

        if (element.isJsonArray) {
            return element.asJsonArray.joinToString("") { flatten(it) }
        }

        if (!element.isJsonObject) {
            return ""
        }

        val objectElement = element.asJsonObject

        return buildString {
            objectElement.string("text")?.let { append(stripColours(it)) }

            // A translated component has no text of its own; its key is the closest thing to one.
            if (isEmpty()) {
                objectElement.string("translate")?.let { append(stripColours(it)) }
            }

            objectElement.get("extra")?.let { append(flatten(it)) }
        }
    }

    /** `§a` and friends removed: they are formatting, and this is the text without it. */
    fun stripColours(value: String): String = COLOUR_CODE.replace(value, "")

    private fun writePacket(output: OutputStream, body: ByteArray) {
        VarInt.write(output, body.size)

        output.write(body)
    }

    private fun writeString(output: OutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)

        VarInt.write(output, bytes.size)

        output.write(bytes)
    }

    private fun readString(input: InputStream): String {
        val length = VarInt.read(input)

        require(length in 0..MAX_PACKET_BYTES) { "String length $length is out of range." }

        return String(readFully(input, length), Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0

        while (read < length) {
            val count = input.read(bytes, read, length - read)

            require(count > 0) { "The connection ended after $read of $length bytes." }

            read += count
        }

        return bytes
    }

    private fun com.google.gson.JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonObject.int(key: String): Int? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (_: Exception) {
        null
    }

    /**
     * Minecraft's own integer encoding: seven bits a byte, high bit means "another follows".
     *
     * Five bytes maximum, and a sixth is refused rather than read: a malformed stream would
     * otherwise be able to keep this reader going for as long as it kept sending 0x80.
     */
    object VarInt {
        const val MAX_BYTES = 5

        fun write(output: OutputStream, value: Int) {
            var remaining = value

            while (true) {
                if (remaining and 0x7F.inv() == 0) {
                    output.write(remaining)

                    return
                }

                output.write((remaining and 0x7F) or 0x80)

                remaining = remaining ushr 7
            }
        }

        fun read(input: InputStream): Int {
            var result = 0
            var position = 0

            while (position < MAX_BYTES) {
                val current = input.read()

                require(current >= 0) { "The connection ended inside a VarInt." }

                result = result or ((current and 0x7F) shl (position * 7))

                if (current and 0x80 == 0) {
                    return result
                }

                position++
            }

            throw IllegalArgumentException("VarInt is longer than $MAX_BYTES bytes.")
        }

        /** The bytes [value] encodes to, which is what a test asserts against. */
        fun encode(value: Int): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

        /** [encode] backwards, for a byte array rather than a stream. */
        fun decode(bytes: ByteArray): Int = read(bytes.inputStream())
    }

    /** The real thing: one TCP connection, with the timeout applied to connect and to every read. */
    private object SocketDialer : Dialer {
        override fun open(host: String, port: Int, timeoutMillis: Int): Channel {
            val socket = Socket()

            try {
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
            } catch (exception: Exception) {
                socket.close()

                throw exception
            }

            return object : Channel {
                override val input: InputStream get() = socket.getInputStream()
                override val output: OutputStream get() = socket.getOutputStream()

                override fun close() {
                    socket.close()
                }
            }
        }
    }

    /** How long a whole ping may take, connect included. */
    const val DEFAULT_TIMEOUT_MILLIS = 2_000

    /** Names Pano is given out of one sample, which is as many as a server list would show. */
    const val MAX_SAMPLE = 12

    /** Nothing a status response carries is bigger than this, and nothing bigger is read. */
    const val MAX_PACKET_BYTES = 64 * 1024

    /** "I am not claiming to be any particular client", which is what a status ping means. */
    const val PROTOCOL_ANY = -1

    const val PACKET_HANDSHAKE = 0x00
    const val PACKET_STATUS_REQUEST = 0x00
    const val PACKET_STATUS_RESPONSE = 0x00

    /** Next state `1` is status; `2` would be a login, which this never asks for. */
    const val STATE_STATUS = 1

    private val COLOUR_CODE = Regex("§[0-9A-FK-ORXa-fk-orx]")
}
