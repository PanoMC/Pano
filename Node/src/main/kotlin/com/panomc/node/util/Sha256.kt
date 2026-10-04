package com.panomc.node.util

import java.io.File
import java.security.MessageDigest

/** Hex SHA-256 of a file, streamed so a 200 MB jar never lands in memory. */
object Sha256 {
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

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
}
