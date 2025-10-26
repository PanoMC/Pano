package com.panomc.platform.util

import org.apache.commons.io.IOUtils
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

object HashUtil {
    fun InputStream.hash() =
        String.format("%064x", BigInteger(1, MessageDigest.getInstance("SHA-256").digest(IOUtils.toByteArray(this))))

    fun verifyFileHash(file: File, expectedHash: String): Boolean {
        if (!file.exists() || !file.isFile) return false

        return try {
            file.inputStream().use { input ->
                val actualHash = input.hash()
                actualHash.equals(expectedHash, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }
}