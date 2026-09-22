package com.panomc.node.util

import java.io.File
import java.security.MessageDigest

/**
 * Hex digests of a file under whichever algorithm an upstream happens to publish.
 *
 * Modrinth hands out SHA-512 and SHA-1, CurseForge SHA-1, Hangar SHA-256; the daemon verifies
 * whatever it was given rather than insisting on one of them, because "we could not check this
 * download" is a worse outcome than checking it with the weaker hash the author chose.
 *
 * Streamed for the same reason [Sha256] is: a plugin jar can be tens of megabytes and there is no
 * reason for any of it to be resident.
 */
object FileHash {
    fun of(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)

        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val read = input.read(buffer)

                if (read <= 0) {
                    break
                }

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Whether [file] hashes to [expected] under [algorithm], comparing case-insensitively. */
    fun matches(file: File, algorithm: String, expected: String): Boolean =
        of(file, algorithm).equals(expected.trim(), ignoreCase = true)
}
