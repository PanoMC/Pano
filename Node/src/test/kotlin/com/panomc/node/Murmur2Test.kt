package com.panomc.node

import com.panomc.node.util.Murmur2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The fingerprint is the only handle CurseForge gives on "what is this jar", and it is a number
 * with no forgiveness in it: an implementation that is one byte or one shift out matches nothing
 * at all and looks exactly like a jar CurseForge has never seen. So the arithmetic is pinned
 * against a reference implementation written out in the test itself, rather than against the
 * production code's own opinion of what MurmurHash2 is.
 */
class Murmur2Test {
    @TempDir
    lateinit var directory: File

    /**
     * MurmurHash2 (32-bit) as the reference C implementation writes it, transliterated.
     *
     * Deliberately a second implementation: it reads the length up front and walks whole blocks
     * with a `while (len >= 4)` loop, where the production one is fed a byte at a time so it can
     * stream a 200 MB jar. Two shapes that have to agree is the whole point.
     */
    private fun reference(data: ByteArray, seed: Int): Int {
        val m = 0x5bd1e995.toInt()
        var length = data.size
        var h = seed xor length
        var index = 0

        while (length >= 4) {
            var k = (data[index].toInt() and 0xFF) or
                ((data[index + 1].toInt() and 0xFF) shl 8) or
                ((data[index + 2].toInt() and 0xFF) shl 16) or
                ((data[index + 3].toInt() and 0xFF) shl 24)

            k *= m
            k = k xor (k ushr 24)
            k *= m

            h *= m
            h = h xor k

            index += 4
            length -= 4
        }

        if (length >= 3) {
            h = h xor ((data[index + 2].toInt() and 0xFF) shl 16)
        }

        if (length >= 2) {
            h = h xor ((data[index + 1].toInt() and 0xFF) shl 8)
        }

        if (length >= 1) {
            h = h xor (data[index].toInt() and 0xFF)
            h *= m
        }

        h = h xor (h ushr 13)
        h *= m
        h = h xor (h ushr 15)

        return h
    }

    /**
     * The fixed vector, cross-checked outside this repository.
     *
     * `MurmurHash2("hello", seed 1)` is 2788266382 by Apache Commons Codec's `MurmurHash2.hash32`
     * and by the reference C implementation alike. It is written out rather than computed so that
     * a change to both implementations at once still fails.
     */
    @Test
    fun `matches the published vector for hello`() {
        assertEquals(2788266382u.toInt(), Murmur2.hash("hello".toByteArray(), 1))
        assertEquals("2788266382", Murmur2.fingerprint("hello".toByteArray()))
    }

    @Test
    fun `agrees with the reference implementation at every tail length`() {
        // Every remainder mod 4 is a different branch of the tail switch, and a payload long
        // enough to run the block loop more than once.
        listOf("", "a", "ab", "abc", "abcd", "abcde", "The quick brown fox jumps over the lazy dog")
            .forEach { sample ->
                val bytes = sample.toByteArray()

                assertEquals(reference(bytes, 1), Murmur2.hash(bytes, 1), "seed 1 differs for \"$sample\"")
                assertEquals(reference(bytes, 0), Murmur2.hash(bytes, 0), "seed 0 differs for \"$sample\"")
            }
    }

    @Test
    fun `drops exactly the four bytes CurseForge ignores`() {
        // Tab, LF, CR and space go; nothing else does, including other control characters.
        assertEquals("2788266382", Murmur2.fingerprint(" he\tl\nlo\r ".toByteArray()))
        assertEquals("2788266382", Murmur2.fingerprint("\r\n h e l l o \t".toByteArray()))

        val withVerticalTab = Murmur2.fingerprint("hel\u000Blo".toByteArray())

        org.junit.jupiter.api.Assertions.assertNotEquals("2788266382", withVerticalTab)
    }

    @Test
    fun `hashes a file exactly as it hashes the same bytes`() {
        val file = File(directory, "sample.jar")

        // Large enough to cross the streaming buffer and end on a partial block.
        val payload = buildString {
            repeat(9_000) { index ->
                append("plugin ")
                append(index)
                append('\n')
            }
        }.toByteArray()

        file.writeBytes(payload)

        val streamed = Murmur2.fingerprint(file)

        assertNotNull(streamed)
        assertEquals(Murmur2.fingerprint(payload), streamed)
    }

    @Test
    fun `an unreadable file costs the fingerprint and nothing else`() {
        assertEquals(null, Murmur2.fingerprint(File(directory, "missing.jar")))
    }
}
