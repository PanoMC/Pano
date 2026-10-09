package com.panomc.platform.util

import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts secrets at rest (webhook secrets and headers): AES-256-GCM, a fresh 12-byte random IV per value, 128-bit tag, stored as
 * `v1:<base64(iv || ciphertext || tag)>`.
 *
 * Decryption never throws: a wrong key or a damaged value gives `null` and the caller treats the secret as missing
 * (a webhook with an unreadable secret ends `DEAD` with `SECRET_UNREADABLE`). A stored value
 * without the `v1:` prefix is legacy plaintext (the old settings were never encrypted) and is returned as it is;
 * [needsEncryption] tells the caller to re-encrypt it. A value that starts with `v1:` but cannot be decoded is
 * treated as damaged, never as plaintext: a legacy plaintext secret that really starts with `v1:` must be entered again.
 *
 * The key never leaves the object: there is no accessor and `toString` does not print it.
 */
class SecretCipher(key: ByteArray, private val random: SecureRandom = SecureRandom()) {
    private val keySpec: SecretKeySpec

    /** `true` when [load] had to create the key (first start, or the key file was missing or unusable). */
    var keyCreated: Boolean = false
        private set

    init {
        require(key.size == KEY_BYTES) { "The secret key must be $KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    /** `v1:<base64>` of [plain]. Two calls with the same input give different results (random IV). */
    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + sealed)
    }

    /**
     * The plaintext of [stored]; `null` when it is a `v1:` value that cannot be decrypted (tampered, wrong key,
     * truncated, bad base64). A value without the prefix is legacy plaintext and comes back unchanged.
     */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            if (raw.size < IV_BYTES + TAG_BITS / 8) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** `true` when [stored] is in the encrypted format (it may still be unreadable). */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    /** `true` for a non-empty value in the legacy plaintext format: the caller re-encrypts it. */
    fun needsEncryption(stored: String): Boolean = stored.isNotEmpty() && !isEncrypted(stored)

    override fun toString(): String = "SecretCipher(AES-256-GCM)"

    companion object {
        const val PREFIX = "v1:"
        /** The key file of the webhook secrets, beside Pano's `config.conf`. */
        const val KEY_FILE = "webhook.key"
        const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val logger = LoggerFactory.getLogger(SecretCipher::class.java)

        /**
         * Reads `<dataDir>/<keyFile>` (`webhook.key`) (base64 of 32 bytes). A missing file is created: 32 bytes from [random] are
         * written to `<keyFile>.tmp` and moved into place atomically, with `rw-------` where the file system knows
         * POSIX permissions (a WARN line otherwise). An existing file that is not a valid key is moved aside as
         * `<keyFile>.invalid-<n>` (never overwritten, never deleted) and a new key is created: every secret stored
         * with the old one is then unreadable, which [keyCreated] lets the caller tell the admin about. Pano starts either way.
         */
        fun load(dataDir: Path, random: SecureRandom = SecureRandom(), keyFile: String = KEY_FILE): SecretCipher {
            Files.createDirectories(dataDir)
            val file = dataDir.resolve(keyFile)
            var created = false
            var key = if (Files.isRegularFile(file)) readKey(file) else null
            if (key == null) {
                if (Files.exists(file)) moveAside(file, keyFile)
                key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
                writeKey(dataDir, file, key, keyFile)
                created = true
            }
            return SecretCipher(key, random).also { it.keyCreated = created }
        }

        private fun readKey(file: Path): ByteArray? = try {
            val bytes = Base64.getDecoder().decode(String(Files.readAllBytes(file), Charsets.UTF_8).trim())
            if (bytes.size == KEY_BYTES) bytes else null
        } catch (e: Exception) {
            null
        }

        private fun moveAside(file: Path, keyFile: String) {
            var n = 1
            var target = file.resolveSibling("$keyFile.invalid-$n")
            while (Files.exists(target)) target = file.resolveSibling("$keyFile.invalid-${++n}")
            Files.move(file, target)
            logger.warn("{} was not a valid key; moved to {}. Secrets stored with it are unreadable until re-entered.", file.fileName, target.fileName)
        }

        private fun writeKey(dataDir: Path, file: Path, key: ByteArray, keyFile: String) {
            val tmp = dataDir.resolve("$keyFile.tmp")
            Files.deleteIfExists(tmp)
            Files.write(tmp, Base64.getEncoder().encode(key), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                Files.setPosixFilePermissions(tmp, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            } catch (e: UnsupportedOperationException) {
                logger.warn("This file system has no POSIX permissions: {} is not restricted to its owner.", keyFile)
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, file)
            }
        }
    }
}
