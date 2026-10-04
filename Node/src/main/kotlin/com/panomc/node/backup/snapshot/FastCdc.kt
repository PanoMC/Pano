package com.panomc.node.backup.snapshot

import java.io.InputStream

/**
 * Content-defined chunking: FastCDC with a gear hash and normalized chunking.
 *
 * The point of cutting files by their content rather than at fixed offsets is what happens when a
 * byte is inserted near the start of a 2 GB world file: fixed-size blocks all shift by one and
 * every single one of them is "new", while content-defined boundaries move with the bytes they
 * were found at, so everything after the first one or two chunks is the same chunk it was
 * yesterday and is not stored twice. That is the whole of what makes a snapshot of a world where
 * a player walked around for an hour cost megabytes instead of gigabytes.
 *
 * The algorithm, exactly, because pano-node and pano-mc-plugin must cut the same bytes at the same
 * places (a repository is only deduplicated against itself, but the format is shared and a
 * divergent chunker would silently make every snapshot a full one):
 *
 * - The gear table is 256 64-bit values drawn in order from a SplitMix64 generator seeded with
 *   [GEAR_SEED] (`0x50414E4F`, "PANO"): `state += 0x9E3779B97F4A7C15`, then
 *   `z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9`, `z = (z xor (z ushr 27)) * 0x94D049BB133111EB`,
 *   `z xor (z ushr 31)`. `GEAR[0]` is the first value drawn.
 * - A chunk starts with `fp = 0`. When at most `min` bytes remain, they are one chunk. Otherwise
 *   the first `min` bytes are skipped without hashing, and from index `i = min` on every byte is
 *   rolled in as `fp = (fp shl 1) + GEAR[b and 0xFF]` (64-bit, wrapping).
 * - Normalized chunking, level 2: while `i < avg` the cut test is `fp and MASK_S == 0`, from `avg`
 *   on it is `fp and MASK_L == 0`, where `MASK_S` is the top `log2(avg) + 2` bits of the 64-bit
 *   word set and `MASK_L` the top `log2(avg) - 2` bits. Top bits rather than low bits because a
 *   shift-left gear hash only mixes the last 64 bytes into its highest bit — the low bits would
 *   depend on a window of a handful of bytes.
 * - A cut after byte `i` makes the chunk `i + 1` bytes long. A chunk that reaches `max` bytes
 *   without a cut is cut there; a stream that ends first ends the chunk.
 *
 * With the defaults (256 KiB / 1 MiB / 4 MiB) chunks cluster tightly around 1 MiB, which is large
 * enough that a repository of a big network holds tens of thousands of chunk files rather than
 * millions, and small enough that a region file touched in one corner costs one chunk.
 */
class FastCdc(
    val minSize: Int = DEFAULT_MIN,
    val avgSize: Int = DEFAULT_AVG,
    val maxSize: Int = DEFAULT_MAX
) {
    private val maskS: Long
    private val maskL: Long

    init {
        require(minSize in 64..avgSize) { "The minimum chunk size must be at least 64 and at most the average." }
        require(avgSize <= maxSize) { "The average chunk size may not exceed the maximum." }
        require(avgSize and (avgSize - 1) == 0) { "The average chunk size must be a power of two." }

        val bits = Integer.numberOfTrailingZeros(avgSize)

        require(bits in 3..61) { "The average chunk size is out of range." }

        maskS = topBits(bits + NORMALIZATION_LEVEL)
        maskL = topBits(bits - NORMALIZATION_LEVEL)
    }

    /**
     * Where the chunk starting at [offset] in [buffer] ends, given [length] usable bytes from there.
     *
     * Returns the chunk's length. [length] is everything the caller has, so a caller that has not
     * reached the end of its stream must hand over at least [maxSize] bytes, or the cut would be
     * decided on a shorter view than the one a later reader will see.
     */
    fun cut(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= minSize) {
            return length
        }

        val end = minOf(length, maxSize)
        val normal = minOf(avgSize, end)

        var fp = 0L
        var i = minSize

        while (i < normal) {
            fp = (fp shl 1) + GEAR[buffer[offset + i].toInt() and 0xFF]

            if (fp and maskS == 0L) {
                return i + 1
            }

            i++
        }

        while (i < end) {
            fp = (fp shl 1) + GEAR[buffer[offset + i].toInt() and 0xFF]

            if (fp and maskL == 0L) {
                return i + 1
            }

            i++
        }

        return end
    }

    /**
     * Cuts all of [input] into chunks and hands each one to [onChunk] as `(buffer, offset, length)`.
     *
     * The buffer is reused for the next chunk as soon as [onChunk] returns, so whatever the
     * consumer wants to keep it copies. Only one window of [maxSize] bytes is ever held, whatever
     * the size of the stream — a 3 GB region folder goes through the same 4 MiB. Returns the total
     * number of bytes read. [input] is not closed.
     */
    fun split(input: InputStream, onChunk: (ByteArray, Int, Int) -> Unit): Long {
        val buffer = ByteArray(maxSize)
        var filled = 0
        var eof = false
        var total = 0L

        while (true) {
            while (!eof && filled < buffer.size) {
                val read = input.read(buffer, filled, minOf(READ_SIZE, buffer.size - filled))

                if (read < 0) {
                    eof = true
                } else {
                    filled += read
                    total += read
                }
            }

            if (filled == 0) {
                return total
            }

            var start = 0

            // Every chunk that is decided on a full window can be emitted; the tail is carried over
            // to the next refill unless the stream has ended, because a boundary that was not found
            // in the bytes seen so far might still be found once more of them arrive.
            while (filled - start > 0 && (eof || filled - start >= maxSize)) {
                val size = cut(buffer, start, filled - start)

                onChunk(buffer, start, size)

                start += size
            }

            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, filled - start)

                filled -= start
            }

            if (eof && filled == 0) {
                return total
            }
        }
    }

    companion object {
        const val DEFAULT_MIN = 256 * 1024
        const val DEFAULT_AVG = 1024 * 1024
        const val DEFAULT_MAX = 4 * 1024 * 1024

        /** "PANO" in ASCII: the SplitMix64 seed the gear table is drawn from. */
        const val GEAR_SEED = 0x50414E4FL

        private const val NORMALIZATION_LEVEL = 2
        private const val READ_SIZE = 64 * 1024

        /** The 256 gear values, identical in every Pano implementation of this chunker. */
        val GEAR: LongArray = gearTable(GEAR_SEED)

        /** The table as SplitMix64 draws it from [seed]; see the class documentation. */
        fun gearTable(seed: Long): LongArray {
            var state = seed

            return LongArray(256) {
                state += 0x9E3779B97F4A7C15uL.toLong()

                var z = state

                z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
                z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()

                z xor (z ushr 31)
            }
        }

        private fun topBits(count: Int): Long = if (count <= 0) 0L else -1L shl (64 - count)
    }
}
