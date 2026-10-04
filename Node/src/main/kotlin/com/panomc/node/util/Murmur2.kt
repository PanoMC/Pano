package com.panomc.node.util

import java.io.File

/**
 * CurseForge's file fingerprint: 32-bit MurmurHash2, seed 1, over the file with whitespace removed.
 *
 * This is the only way to ask CurseForge "what is this jar" — it publishes no hash lookup, only
 * `POST /v1/fingerprints`, and the number it wants is this one. The whitespace rule (drop every
 * tab, line feed, carriage return and space *byte*, anywhere in the file, text or not) is not a
 * normalisation anybody would design; it is what CurseForge's own client does, and a fingerprint
 * that does anything else simply matches nothing.
 *
 * Streamed in two passes rather than read into memory, because a modpack's jars run to hundreds of
 * megabytes and the hash needs the normalised length before it can start: the first pass counts,
 * the second hashes. Reading a file twice is cheap next to holding it.
 */
object Murmur2 {
    /** The seed CurseForge fingerprints with. */
    const val SEED = 1

    /** The bytes CurseForge drops before hashing: tab, LF, CR and space. */
    private val WHITESPACE = setOf(0x09, 0x0A, 0x0D, 0x20)

    private const val M = 0x5bd1e995.toInt()
    private const val R = 24

    /** Plain MurmurHash2 over [bytes], with nothing removed. */
    fun hash(bytes: ByteArray, seed: Int = SEED): Int {
        val digest = Digest(bytes.size, seed)

        bytes.forEach { digest.update(it) }

        return digest.finish()
    }

    /** [bytes] without the four whitespace bytes CurseForge ignores. */
    fun strip(bytes: ByteArray): ByteArray = bytes.filter { (it.toInt() and 0xFF) !in WHITESPACE }.toByteArray()

    /** The fingerprint of [bytes], as the unsigned decimal string the API expects. */
    fun fingerprint(bytes: ByteArray): String = hash(strip(bytes)).toUInt().toString()

    /**
     * The fingerprint of [file], streamed.
     *
     * Null rather than an exception for a file that cannot be read: this is one column of one row
     * of an answer about a directory, and an unreadable jar should cost that column and nothing
     * else.
     */
    fun fingerprint(file: File): String? = try {
        val length = normalisedLength(file)

        val digest = Digest(length, SEED)

        read(file) { byte -> digest.update(byte) }

        digest.finish().toUInt().toString()
    } catch (_: Exception) {
        null
    }

    private fun normalisedLength(file: File): Int {
        var length = 0

        read(file) { length++ }

        return length
    }

    /** Feeds every non-whitespace byte of [file] to [consume]. */
    private inline fun read(file: File, consume: (Byte) -> Unit) {
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)

            while (true) {
                val count = input.read(buffer)

                if (count <= 0) {
                    break
                }

                for (index in 0 until count) {
                    val byte = buffer[index]

                    if ((byte.toInt() and 0xFF) !in WHITESPACE) {
                        consume(byte)
                    }
                }
            }
        }
    }

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * MurmurHash2 fed one byte at a time.
     *
     * Incremental rather than array-at-once so the streaming and in-memory versions are the same
     * arithmetic: the length is mixed into the seed up front and whole four-byte blocks are
     * consumed as they complete, which is exactly what the reference implementation's `while (len
     * >= 4)` loop does, leaving the same one to three bytes for the tail.
     */
    private class Digest(length: Int, seed: Int) {
        private var h = seed xor length
        private val tail = ByteArray(4)
        private var tailSize = 0

        fun update(byte: Byte) {
            tail[tailSize] = byte
            tailSize++

            if (tailSize < 4) {
                return
            }

            var k = (tail[0].toInt() and 0xFF) or
                ((tail[1].toInt() and 0xFF) shl 8) or
                ((tail[2].toInt() and 0xFF) shl 16) or
                ((tail[3].toInt() and 0xFF) shl 24)

            k *= M
            k = k xor (k ushr R)
            k *= M

            h *= M
            h = h xor k

            tailSize = 0
        }

        fun finish(): Int {
            if (tailSize >= 3) {
                h = h xor ((tail[2].toInt() and 0xFF) shl 16)
            }

            if (tailSize >= 2) {
                h = h xor ((tail[1].toInt() and 0xFF) shl 8)
            }

            if (tailSize >= 1) {
                h = h xor (tail[0].toInt() and 0xFF)
                h *= M
            }

            h = h xor (h ushr 13)
            h *= M
            h = h xor (h ushr 15)

            return h
        }
    }
}
