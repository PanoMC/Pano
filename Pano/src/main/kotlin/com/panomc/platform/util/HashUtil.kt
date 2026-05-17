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

    /**
     * Default files to skip when computing a theme's stable file fingerprint. Must stay in
     * lock-step with the JavaScript port at
     *   themes/<theme>/scripts/license/file-fingerprint.js (DEFAULT_EXCLUDED_FILES)
     * and the theme runtime helper at
     *   themes/<theme>/src/lib/server/license-runtime.js (DEFAULT_FINGERPRINT_EXCLUDE).
     *
     * `manifest.json` is excluded because it's where the expected fingerprint is written;
     * a value cannot be part of its own input.
     */
    val DEFAULT_THEME_FINGERPRINT_EXCLUDES = setOf("manifest.json")

    /**
     * Stable cumulative SHA-256 of every regular file under [dir] (recursively), with
     * `excludeRelative` paths removed. The algorithm is documented in detail in the
     * JavaScript port at file-fingerprint.js; the short version is:
     *
     *   sha256(
     *     files.sortedBy { rel }.map { "$rel:${sha256(content)}" }.joinToString("\n")
     *   )
     *
     * Returned as a 64-character lowercase hex string. Returns an empty string when
     * [dir] does not exist (so callers can treat "empty == mismatch" with the same code
     * path as a value mismatch).
     */
    fun computeStableFileFingerprint(
        dir: File,
        excludeRelative: Set<String> = DEFAULT_THEME_FINGERPRINT_EXCLUDES
    ): String {
        if (!dir.exists() || !dir.isDirectory) return ""

        val rootPath = dir.toPath().toAbsolutePath().normalize()
        val items = dir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val rel = rootPath.relativize(file.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/')
                rel to file
            }
            .filter { (rel, _) -> rel !in excludeRelative }
            .sortedBy { it.first }
            .map { (rel, file) ->
                val fileHash = file.inputStream().use { it.hash() }
                "$rel:$fileHash"
            }
            .toList()

        return items.joinToString("\n").byteInputStream(Charsets.UTF_8).use { it.hash() }
    }
}