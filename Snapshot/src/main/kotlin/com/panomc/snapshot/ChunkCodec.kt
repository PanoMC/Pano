package com.panomc.snapshot

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The chunk file as pano-node's `SnapshotRepository` writes it: one header byte — [HEADER_RAW] and
 * the bytes, or [HEADER_DEFLATE] and a zlib (not nowrap) `Deflater` level-6 stream, kept only when
 * it is at most 95 % of the raw size. Region files are already compressed and stay raw; configs,
 * player data and logs shrink a lot. A chunk's id is the lowercase hex SHA-256 of its raw bytes.
 *
 * An instance holds a deflater and its buffers, so it is not thread-safe: one per worker, closed
 * when done. [decode] and [sha256Hex] are stateless.
 */
class ChunkCodec : AutoCloseable {
    private val deflater = Deflater(DEFLATE_LEVEL)
    private val compressed = ByteArrayOutputStream(FastCdc.DEFAULT_MAX)
    private val deflateBuffer = ByteArray(IO_BUFFER)

    /** The chunk file bytes for `buffer[offset, offset + length)`. */
    fun encode(buffer: ByteArray, offset: Int, length: Int): ByteArray {
        if (deflate(buffer, offset, length)) {
            return compressed.toByteArray()
        }

        val out = ByteArray(1 + length)

        out[0] = HEADER_RAW
        System.arraycopy(buffer, offset, out, 1, length)

        return out
    }

    fun encode(bytes: ByteArray) = encode(bytes, 0, bytes.size)

    /**
     * Leaves the header byte and the deflate stream in [compressed] and says whether that was worth it.
     *
     * Gives up as soon as the output passes 95 % of the input, which on an already-compressed
     * region file is early.
     */
    private fun deflate(buffer: ByteArray, offset: Int, length: Int): Boolean {
        val limit = length.toLong() * DEFLATE_KEEP_PERCENT / 100

        compressed.reset()
        compressed.write(HEADER_DEFLATE.toInt())
        deflater.reset()
        deflater.setInput(buffer, offset, length)
        deflater.finish()

        while (!deflater.finished()) {
            val count = deflater.deflate(deflateBuffer)

            compressed.write(deflateBuffer, 0, count)

            if (compressed.size() - 1 > limit) {
                return false
            }
        }

        return compressed.size() - 1 <= limit
    }

    override fun close() = deflater.end()

    companion object {
        const val HEADER_RAW: Byte = 0x00
        const val HEADER_DEFLATE: Byte = 0x01

        private const val DEFLATE_LEVEL = 6
        private const val DEFLATE_KEEP_PERCENT = 95
        private const val IO_BUFFER = 64 * 1024

        /** No chunk is ever larger than the chunker's maximum; a payload inflating past this is not one of ours. */
        const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

        /** Lowercase hex SHA-256 of a slice, with a digest of its own. */
        fun sha256Hex(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): String {
            val digest = MessageDigest.getInstance("SHA-256")

            digest.update(buffer, offset, length)

            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * The raw bytes of chunk [id] from its [stored] chunk file bytes, checked against the id.
         *
         * An unusable id, an empty file, an unknown header, a bad zlib stream, a payload over
         * [MAX_INFLATED_BYTES] or a hash mismatch throws [CorruptChunkException].
         */
        fun decode(id: String, stored: ByteArray): ByteArray {
            if (!SnapshotFormat.isChunkId(id) || stored.isEmpty()) {
                throw CorruptChunkException(id)
            }

            val raw = when (stored[0]) {
                HEADER_RAW -> stored.copyOfRange(1, stored.size)
                HEADER_DEFLATE -> inflate(id, stored)
                else -> throw CorruptChunkException(id)
            }

            if (sha256Hex(raw) != id) {
                throw CorruptChunkException(id)
            }

            return raw
        }

        private fun inflate(id: String, stored: ByteArray): ByteArray {
            val inflater = Inflater()

            try {
                inflater.setInput(stored, 1, stored.size - 1)

                val output = ByteArrayOutputStream(stored.size * 2)
                val buffer = ByteArray(IO_BUFFER)

                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)

                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw CorruptChunkException(id)
                    }

                    output.write(buffer, 0, count)

                    if (output.size() > MAX_INFLATED_BYTES) {
                        throw CorruptChunkException(id)
                    }
                }

                return output.toByteArray()
            } catch (_: DataFormatException) {
                throw CorruptChunkException(id)
            } finally {
                inflater.end()
            }
        }
    }
}
