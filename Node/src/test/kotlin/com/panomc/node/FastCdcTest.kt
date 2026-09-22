package com.panomc.node

import com.panomc.node.backup.snapshot.FastCdc
import com.panomc.node.backup.snapshot.SnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FastCdcTest {
    /**
     * Bytes nobody has to ship as a fixture: the low byte of every SplitMix64 draw from [seed].
     *
     * The golden boundaries below were computed from the same stream by an independent Python
     * transcription of the chunker's documented algorithm, which is what makes them a check of the
     * spec rather than of this implementation against itself.
     */
    private fun pattern(size: Int, seed: Long = 1): ByteArray {
        var state = seed

        return ByteArray(size) {
            state += 0x9E3779B97F4A7C15uL.toLong()

            var z = state

            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()

            (z xor (z ushr 31)).toByte()
        }
    }

    private fun sizes(chunker: FastCdc, input: InputStream): List<Int> {
        val result = mutableListOf<Int>()

        chunker.split(input) { _, _, length -> result.add(length) }

        return result
    }

    private fun ids(chunker: FastCdc, data: ByteArray): List<String> {
        val result = mutableListOf<String>()

        chunker.split(ByteArrayInputStream(data)) { buffer, offset, length ->
            result.add(SnapshotRepository.sha256Hex(buffer, offset, length))
        }

        return result
    }

    /** A stream that hands out a few bytes per read, the way a slow disk or a pipe does. */
    private class TrickleStream(private val data: ByteArray) : InputStream() {
        private var position = 0
        private var step = 0

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) {
                return -1
            }

            val count = minOf(length, 1 + (step++ % 7), data.size - position)

            System.arraycopy(data, position, buffer, offset, count)

            position += count

            return count
        }
    }

    @Test
    fun `the gear table is the documented SplitMix64 draw`() {
        assertEquals(0xa8ae1000d0bd373duL.toLong(), FastCdc.GEAR[0])
        assertEquals(0x8fcb7d6fe5acf2c3uL.toLong(), FastCdc.GEAR[1])
        assertEquals(0xb9e4d3f76ba7d4f7uL.toLong(), FastCdc.GEAR[255])
        assertEquals(256, FastCdc.GEAR.distinct().size)
    }

    @Test
    fun `a known pattern cuts at known places with the default sizes`() {
        val data = pattern(8 * 1024 * 1024)

        assertEquals(
            listOf(322031, 1397605, 1284441, 2030690, 1552390, 588164, 1213287),
            sizes(FastCdc(), ByteArrayInputStream(data))
        )
    }

    @Test
    fun `a known pattern cuts at known places with small sizes`() {
        val data = pattern(256 * 1024)

        val expected = listOf(
            4422, 4526, 2811, 4234, 4747, 5033, 1127, 5286, 4966, 4131, 4407, 4148, 4149, 7765, 5419,
            7718, 6809, 1097, 2440, 5278, 1065, 1412, 1751, 4573, 5280, 4140, 6926, 4447, 4193, 5023,
            6510, 4826, 5699, 5318, 4712, 4541, 2588, 4851, 4614, 4431, 4223, 5283, 4690, 5608, 4626,
            4738, 4312, 4518, 5299, 2160, 4895, 8241, 4266, 2017, 3888, 3845, 7489, 4633
        )

        assertEquals(expected, sizes(FastCdc(1024, 4096, 16384), ByteArrayInputStream(data)))
    }

    @Test
    fun `boundaries do not depend on how the stream is read`() {
        val data = pattern(300 * 1024, seed = 7)
        val chunker = FastCdc(1024, 4096, 16384)

        assertEquals(sizes(chunker, ByteArrayInputStream(data)), sizes(chunker, TrickleStream(data)))
    }

    @Test
    fun `every chunk but the last is between min and max`() {
        val chunker = FastCdc(1024, 4096, 16384)
        val sizes = sizes(chunker, ByteArrayInputStream(pattern(512 * 1024, seed = 3)))

        assertEquals(512 * 1024, sizes.sum())

        sizes.dropLast(1).forEach { size -> assertTrue(size in 1025..16384, "chunk of $size bytes") }
    }

    @Test
    fun `a stream of one repeated byte is cut at max`() {
        val chunker = FastCdc(1024, 4096, 16384)
        val sizes = sizes(chunker, ByteArrayInputStream(ByteArray(40_000)))

        assertEquals(40_000, sizes.sum())
        assertTrue(sizes.dropLast(1).all { it == 16384 || it in 1025..16384 })
    }

    @Test
    fun `a file no larger than min is one chunk, and an empty one is none`() {
        val chunker = FastCdc()

        assertEquals(listOf(1000), sizes(chunker, ByteArrayInputStream(ByteArray(1000))))
        assertEquals(listOf(FastCdc.DEFAULT_MIN), sizes(chunker, ByteArrayInputStream(pattern(FastCdc.DEFAULT_MIN))))
        assertEquals(emptyList<Int>(), sizes(chunker, ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `bytes inserted at the start only cost the first chunks`() {
        val chunker = FastCdc()
        val original = pattern(16 * 1024 * 1024, seed = 11)
        val shifted = pattern(100, seed = 99) + original

        val before = ids(chunker, original)
        val after = ids(chunker, shifted)

        val reused = after.count { it in before.toSet() }

        assertTrue(before.size >= 8, "expected a real number of chunks, got ${before.size}")
        assertTrue(reused >= before.size - 2, "only $reused of ${before.size} chunks were reused")
    }

    @Test
    fun `the same bytes give the same chunks every time`() {
        val data = pattern(6 * 1024 * 1024, seed = 5)

        assertEquals(ids(FastCdc(), data), ids(FastCdc(), data))
    }
}
