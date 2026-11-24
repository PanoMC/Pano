package com.panomc.node.archive

import org.apache.commons.compress.archivers.tar.TarUtils
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Tells archive formats apart by their first bytes.
 *
 * Never by extension: a renamed file, a browser that appended `.zip`, or an HTML error page saved
 * under an archive name must all be judged by what they are. For compressed tarballs the head of
 * the *decompressed* stream is inspected as well, because `x.gz` holding one file and `x.tar.gz`
 * holding a tree need different extractors and their outer magic is identical.
 *
 * Detection runs on live upload bodies, so it never consumes anything: the head is read under a
 * [BufferedInputStream.mark] and the stream is reset before returning. The head is decompressed
 * from a private copy, so no decompressor ever touches (or re-marks) the caller's stream.
 */
object ArchiveFormats {
    /** Enough for every outer magic and a tar header. */
    private const val SMALL_HEAD = 64 * 1024

    /**
     * Enough compressed input for a decompressor to produce its first output even in the worst
     * case: bzip2 emits nothing before a whole block (up to 900 kB, slightly more when it could not
     * compress) has been read.
     */
    const val MAX_HEAD = 2 * 1024 * 1024

    private const val TAR_HEADER = 512
    private const val USTAR_OFFSET = 257

    private val ZIP_LOCAL = bytes(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = bytes(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SPANNED = bytes(0x50, 0x4B, 0x07, 0x08)
    private val GZIP = bytes(0x1F, 0x8B)
    private val BZIP2 = bytes(0x42, 0x5A, 0x68)
    private val XZ = bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val SEVEN_Z = bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
    private val RAR = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
    private val USTAR = "ustar".toByteArray(Charsets.US_ASCII)

    /**
     * The format of the stream [input] is positioned at; [input] is left where it was.
     *
     * [fileNameHint] is only a tie-breaker: an old v7 tar (no `ustar` magic, only a valid header
     * checksum) is accepted when the name also says tar.
     */
    fun detect(input: BufferedInputStream, fileNameHint: String? = null): ArchiveFormat {
        val head = peek(input, SMALL_HEAD)

        return when {
            startsWith(head, ZIP_LOCAL) || startsWith(head, ZIP_EMPTY) || startsWith(head, ZIP_SPANNED) -> ArchiveFormat.ZIP
            startsWith(head, SEVEN_Z) -> ArchiveFormat.SEVEN_Z
            startsWith(head, RAR) && isRarVersion(head) -> ArchiveFormat.RAR
            startsWith(head, XZ) -> inner(input, head) { XZCompressorInputStream(it) }
                ?.let { if (isTar(it, fileNameHint)) ArchiveFormat.TAR_XZ else ArchiveFormat.UNKNOWN }
                ?: ArchiveFormat.UNKNOWN
            startsWith(head, BZIP2) -> inner(input, head) { BZip2CompressorInputStream(it, true) }
                ?.let { if (isTar(it, fileNameHint)) ArchiveFormat.TAR_BZ2 else ArchiveFormat.UNKNOWN }
                ?: ArchiveFormat.UNKNOWN
            startsWith(head, GZIP) -> inner(input, head) { GzipCompressorInputStream(it, true) }
                ?.let { if (isTar(it, fileNameHint)) ArchiveFormat.TAR_GZ else ArchiveFormat.GZ }
                ?: ArchiveFormat.UNKNOWN
            isTar(head, fileNameHint) -> ArchiveFormat.TAR
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /** The format of [file], or UNKNOWN when it is not a readable regular file. */
    fun detect(file: File): ArchiveFormat {
        if (!file.isFile) {
            return ArchiveFormat.UNKNOWN
        }

        return try {
            BufferedInputStream(file.inputStream(), SMALL_HEAD).use { detect(it, file.name) }
        } catch (_: java.io.IOException) {
            ArchiveFormat.UNKNOWN
        }
    }

    /** RAR4 continues `00`, RAR5 `01 00`; anything else after `Rar!\x1A\x07` is not RAR. */
    private fun isRarVersion(head: ByteArray): Boolean = when {
        head.size > 6 && head[6] == 0x00.toByte() -> true
        head.size > 7 && head[6] == 0x01.toByte() && head[7] == 0x00.toByte() -> true
        else -> false
    }

    /**
     * The first decompressed bytes, or null when not even a tar header's worth came out. A head too
     * short for the decompressor is retried once with [MAX_HEAD] of input.
     */
    private fun inner(input: BufferedInputStream, smallHead: ByteArray, open: (InputStream) -> InputStream): ByteArray? {
        var head = smallHead

        while (true) {
            val decompressed = decompressHead(head, open)

            if (decompressed.size >= TAR_HEADER || head.size < SMALL_HEAD || head.size >= MAX_HEAD) {
                // Short output from the whole stream is still an answer (a tiny .gz).
                return decompressed.takeIf { it.isNotEmpty() }
            }

            head = peek(input, MAX_HEAD)
        }
    }

    private fun decompressHead(head: ByteArray, open: (InputStream) -> InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        try {
            open(ByteArrayInputStream(head)).use { stream ->
                val buffer = ByteArray(TAR_HEADER)

                while (out.size() < TAR_HEADER) {
                    val read = stream.read(buffer, 0, TAR_HEADER - out.size())

                    if (read < 0) {
                        break
                    }

                    out.write(buffer, 0, read)
                }
            }
        } catch (_: Exception) {
            // A truncated head (or a corrupt stream) simply yields what came out before it.
        }

        return out.toByteArray()
    }

    private fun isTar(head: ByteArray, fileNameHint: String?): Boolean {
        if (head.size < TAR_HEADER) {
            return false
        }

        val header = head.copyOf(TAR_HEADER)

        if ((0 until USTAR.size).all { header[USTAR_OFFSET + it] == USTAR[it] }) {
            return true
        }

        // A v7 tar has nothing but a checksum to go by; only believe it when the name agrees.
        val hint = fileNameHint?.lowercase() ?: return false
        val looksTar = hint.endsWith(".tar") || hint.contains(".tar.") || hint.endsWith(".tgz")

        return looksTar && header[0] != 0.toByte() && try {
            TarUtils.verifyCheckSum(header)
        } catch (_: Exception) {
            false
        }
    }

    /** Up to [size] bytes from the current position, with [input] reset to it afterwards. */
    private fun peek(input: BufferedInputStream, size: Int): ByteArray {
        input.mark(size + 1)

        try {
            val buffer = ByteArray(size)
            var total = 0

            while (total < size) {
                val read = input.read(buffer, total, size - total)

                if (read < 0) {
                    break
                }

                total += read
            }

            return buffer.copyOf(total)
        } finally {
            input.reset()
        }
    }

    private fun startsWith(head: ByteArray, magic: ByteArray): Boolean =
        head.size >= magic.size && magic.indices.all { head[it] == magic[it] }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
