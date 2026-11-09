package com.panomc.platform.server

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Channel v2 between Pano and a Minecraft server plugin (see MC-PROTOCOL-SPEC.md).
 *
 * Negotiated per connection on the WebSocket upgrade: the plugin sends a random nonce, Pano answers
 * with its own. Both sides derive one key per direction from the server's shared AES key and the
 * two nonces (HKDF-SHA256), so a frame of one connection is worthless on another and a frame can
 * never be reflected back to its sender. Every frame carries a sequence number that is also the
 * GCM associated data and must be exactly the next one, so a replayed, dropped or reordered frame
 * is refused and the connection closed.
 *
 * Frame: Base64( seq (8 bytes, big endian) || IV (12 bytes) || AES-256-GCM ciphertext+tag ).
 */
class SecureChannel(private val sendKey: SecretKey, private val receiveKey: SecretKey) {
    private var sendSeq = 0L
    private var receiveSeq = 0L

    /** Encrypts [text] as the next outgoing frame. Callers must write frames in the order sealed. */
    @Synchronized
    fun seal(text: String): String {
        val seq = sendSeq++
        val seqBytes = ByteBuffer.allocate(SEQ_SIZE).putLong(seq).array()
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(seqBytes)
        val sealed = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(seqBytes + iv + sealed)
    }

    /** Decrypts the next incoming frame; throws [SecurityException] for a replayed, reordered or forged one. */
    @Synchronized
    fun open(frame: String): String {
        val bytes = try {
            Base64.getDecoder().decode(frame)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Frame is not Base64.")
        }

        if (bytes.size <= SEQ_SIZE + IV_SIZE) throw SecurityException("Frame is too short.")

        val seqBytes = bytes.copyOfRange(0, SEQ_SIZE)
        val seq = ByteBuffer.wrap(seqBytes).long

        if (seq != receiveSeq) throw SecurityException("Unexpected frame sequence $seq, expected $receiveSeq.")

        val iv = bytes.copyOfRange(SEQ_SIZE, SEQ_SIZE + IV_SIZE)

        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(seqBytes)
            cipher.doFinal(bytes, SEQ_SIZE + IV_SIZE, bytes.size - SEQ_SIZE - IV_SIZE)
        } catch (e: Exception) {
            throw SecurityException("Frame does not authenticate.")
        }

        receiveSeq++

        return String(plain, Charsets.UTF_8)
    }

    companion object {
        const val CAPABILITIES_HEADER = "X-Pano-Capabilities"
        const val NONCE_HEADER = "X-Pano-Channel-Nonce"

        /** Encrypted channel with per-direction keys and sequence numbers. */
        const val CAP_CHANNEL_V2 = "channel-v2"

        /** Capabilities this Pano offers, in the order it answers them. */
        val SUPPORTED = listOf(CAP_CHANNEL_V2)

        const val NONCE_SIZE = 32

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEQ_SIZE = 8
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        private const val INFO_PLUGIN_TO_PLATFORM = "pano channel v2 plugin->platform"
        private const val INFO_PLATFORM_TO_PLUGIN = "pano channel v2 platform->plugin"

        private val random = SecureRandom()


        /**
         * The capabilities both sides support. Channel v2 needs a valid 32-byte plugin nonce; without one
         * it is not agreed (and no platform nonce is sent), whatever the plugin listed.
         */
        fun negotiate(capabilitiesHeader: String?, nonceHeader: String?): ChannelNegotiation {
            val offered = parseCapabilities(capabilitiesHeader)
            val agreed = SUPPORTED.filter { it in offered }.toMutableList()

            var pluginNonce: ByteArray? = null

            if (CAP_CHANNEL_V2 in agreed) {
                pluginNonce = try {
                    nonceHeader?.let { Base64.getDecoder().decode(it.trim()) }
                } catch (_: IllegalArgumentException) {
                    null
                }?.takeIf { it.size == NONCE_SIZE }

                if (pluginNonce == null) agreed.remove(CAP_CHANNEL_V2)
            }

            val platformNonce = if (pluginNonce != null) newNonce() else null

            return ChannelNegotiation(agreed.toSet(), pluginNonce, platformNonce)
        }

        fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

        /** Parses a capability header value: comma separated tokens, case-insensitive, blanks ignored. */
        fun parseCapabilities(header: String?): Set<String> =
            header.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        /**
         * The two direction keys for a connection: (plugin->platform, platform->plugin).
         *
         * HKDF-SHA256 (RFC 5869) with IKM = the server's 32-byte AES key, salt = pluginNonce ||
         * platformNonce, info = the direction label, L = 32.
         */
        fun deriveKeys(sharedKey: ByteArray, pluginNonce: ByteArray, platformNonce: ByteArray): Pair<SecretKey, SecretKey> {
            require(sharedKey.size == 32) { "shared key must be 32 bytes" }
            require(pluginNonce.size == NONCE_SIZE && platformNonce.size == NONCE_SIZE) { "nonces must be $NONCE_SIZE bytes" }

            val prk = hmac(pluginNonce + platformNonce, sharedKey)

            fun expand(info: String): SecretKey =
                SecretKeySpec(hmac(prk, info.toByteArray(Charsets.US_ASCII) + byteArrayOf(1)), "AES")

            return expand(INFO_PLUGIN_TO_PLATFORM) to expand(INFO_PLATFORM_TO_PLUGIN)
        }

        /** The platform's end of a channel: it receives plugin->platform and sends platform->plugin. */
        fun forPlatform(sharedKey: ByteArray, pluginNonce: ByteArray, platformNonce: ByteArray): SecureChannel {
            val (pluginToPlatform, platformToPlugin) = deriveKeys(sharedKey, pluginNonce, platformNonce)
            return SecureChannel(sendKey = platformToPlugin, receiveKey = pluginToPlatform)
        }

        /** The plugin's end; used by tests (and mirrors what the plugin implements). */
        fun forPlugin(sharedKey: ByteArray, pluginNonce: ByteArray, platformNonce: ByteArray): SecureChannel {
            val (pluginToPlatform, platformToPlugin) = deriveKeys(sharedKey, pluginNonce, platformNonce)
            return SecureChannel(sendKey = pluginToPlatform, receiveKey = platformToPlugin)
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}

/** What one upgrade request negotiated; nonces only when channel v2 was agreed. */
class ChannelNegotiation(val capabilities: Set<String>, val pluginNonce: ByteArray?, val platformNonce: ByteArray?)
