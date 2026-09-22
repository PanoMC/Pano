package com.panomc.node

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Writes tar archives byte by byte, for the tests of the pure-JVM tar reader (SM-63).
 *
 * Hand-rolled on purpose: the point of those tests is the header formats real JRE tarballs use
 * (ustar with a prefix, GNU long names, pax records), and a builder that only knew one of them
 * could not produce the others. No `tar` process is started, per the `:Node:test` rule.
 */
class TarBuilder {
    private val out = ByteArrayOutputStream()

    /** A regular file. [gnuMagic] writes GNU tar's `ustar  ` magic instead of POSIX `ustar\u0000`. */
    fun file(name: String, content: String, mode: Int = 0b110_100_100, gnuMagic: Boolean = false, prefix: String = ""): TarBuilder {
        val bytes = content.toByteArray()

        header(name, mode, bytes.size.toLong(), '0', "", gnuMagic, prefix)
        data(bytes)

        return this
    }

    fun directory(name: String, mode: Int = 0b111_101_101): TarBuilder {
        header(name, mode, 0, '5', "")

        return this
    }

    fun symlink(name: String, target: String): TarBuilder {
        header(name, 0b111_111_111, 0, '2', target)

        return this
    }

    fun hardlink(name: String, target: String): TarBuilder {
        header(name, 0b110_100_100, 0, '1', target)

        return this
    }

    /** A GNU `L` record carrying [longName] for the entry that follows. */
    fun gnuLongName(longName: String): TarBuilder {
        val bytes = (longName + "\u0000").toByteArray()

        header("././@LongLink", 0, bytes.size.toLong(), 'L', "")
        data(bytes)

        return this
    }

    /** A pax `x` (or `g` when [global]) record with [records] for the entry that follows. */
    fun pax(records: Map<String, String>, global: Boolean = false): TarBuilder {
        val body = StringBuilder()

        records.forEach { (key, value) ->
            val payload = " $key=$value\n"

            // The length counts itself, so find the fixed point.
            var length = payload.toByteArray().size + 1

            while (length.toString().length + payload.toByteArray().size != length) {
                length = length.toString().length + payload.toByteArray().size
            }

            body.append(length).append(payload)
        }

        val bytes = body.toString().toByteArray()

        header("PaxHeaders/entry", 0b110_100_100, bytes.size.toLong(), if (global) 'g' else 'x', "")
        data(bytes)

        return this
    }

    /** The finished archive, with its two empty end blocks. */
    fun bytes(): ByteArray {
        out.write(ByteArray(1024))

        return out.toByteArray()
    }

    fun writeTarGz(file: File): File {
        GZIPOutputStream(file.outputStream()).use { it.write(bytes()) }

        return file
    }

    private fun data(bytes: ByteArray) {
        out.write(bytes)

        val padding = (512 - bytes.size % 512) % 512

        out.write(ByteArray(padding))
    }

    private fun header(
        name: String,
        mode: Int,
        size: Long,
        type: Char,
        link: String,
        gnuMagic: Boolean = false,
        prefix: String = ""
    ) {
        val block = ByteArray(512)

        put(block, 0, 100, name.toByteArray())
        put(block, 100, 8, octal(mode.toLong(), 7))
        put(block, 108, 8, octal(0, 7))
        put(block, 116, 8, octal(0, 7))
        put(block, 124, 12, octal(size, 11))
        put(block, 136, 12, octal(0, 11))
        block[156] = type.code.toByte()
        put(block, 157, 100, link.toByteArray())

        if (gnuMagic) {
            put(block, 257, 8, "ustar  \u0000".toByteArray())
        } else {
            put(block, 257, 6, "ustar\u0000".toByteArray())
            put(block, 263, 2, "00".toByteArray())
            put(block, 345, 155, prefix.toByteArray())
        }

        // The checksum is computed with its own field as spaces.
        for (index in 148 until 156) {
            block[index] = ' '.code.toByte()
        }

        val sum = block.sumOf { it.toInt() and 0xFF }

        put(block, 148, 8, (octal(sum.toLong(), 6).toList() + listOf(0.toByte(), ' '.code.toByte())).toByteArray())

        out.write(block)
    }

    private fun octal(value: Long, digits: Int): ByteArray =
        (value.toString(8).padStart(digits, '0') + "\u0000").toByteArray()

    private fun put(block: ByteArray, offset: Int, length: Int, bytes: ByteArray) {
        System.arraycopy(bytes, 0, block, offset, minOf(length, bytes.size))
    }
}
