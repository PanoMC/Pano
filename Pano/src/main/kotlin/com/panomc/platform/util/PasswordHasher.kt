package com.panomc.platform.util

import de.mkammerer.argon2.Argon2Factory
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PasswordHasher {

    enum class Algorithm {
        ARGON2ID,
        BCRYPT,
        SHA256,
        MD5;

        companion object {
            fun fromString(value: String): Algorithm {
                return try {
                    valueOf(value.uppercase())
                } catch (e: IllegalArgumentException) {
                    ARGON2ID
                }
            }
        }
    }

    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val secureRandom = SecureRandom()

    /**
     * Hash a plaintext password using the specified algorithm.
     */
    fun hash(password: String, algorithm: Algorithm = Algorithm.ARGON2ID): String {
        return when (algorithm) {
            Algorithm.ARGON2ID -> hashArgon2id(password)
            Algorithm.BCRYPT -> hashBcrypt(password)
            Algorithm.SHA256 -> hashSha256AuthMe(password)
            Algorithm.MD5 -> DigestUtils.md5Hex(password)
        }
    }

    /**
     * Verify a plaintext password against a stored hash.
     * Auto-detects the hash algorithm from the stored hash format.
     * Returns false if the hash format is not recognized.
     */
    fun verify(password: String, storedHash: String): Boolean {
        return when (detectAlgorithm(storedHash)) {
            Algorithm.ARGON2ID -> verifyArgon2id(password, storedHash)
            Algorithm.BCRYPT -> verifyBcrypt(password, storedHash)
            Algorithm.SHA256 -> verifySha256AuthMe(password, storedHash)
            Algorithm.MD5 -> verifyMd5(password, storedHash)
            null -> false // unknown hash format, cannot verify
        }
    }

    /**
     * Check if a stored hash needs to be upgraded to the target algorithm.
     * Always returns true for unknown hash formats.
     */
    fun needsRehash(storedHash: String, targetAlgorithm: Algorithm): Boolean {
        val detected = detectAlgorithm(storedHash)
        return detected == null || detected != targetAlgorithm
    }

    /**
     * Detect which algorithm was used to create a hash.
     * Returns null for unrecognized hash formats.
     */
    fun detectAlgorithm(hash: String): Algorithm? {
        return when {
            hash.startsWith("\$argon2") -> Algorithm.ARGON2ID
            hash.startsWith("\$2a\$") || hash.startsWith("\$2b\$") || hash.startsWith("\$2y\$") -> Algorithm.BCRYPT
            hash.startsWith("\$SHA\$") -> Algorithm.SHA256
            hash.matches(Regex("^[a-fA-F0-9]{32}$")) -> Algorithm.MD5
            else -> null
        }
    }

    // ==================== Argon2id ====================

    private fun hashArgon2id(password: String): String {
        // iterations=3, memory=64MB, parallelism=1
        return argon2.hash(3, 65536, 1, password.toCharArray())
    }

    private fun verifyArgon2id(password: String, hash: String): Boolean {
        return try {
            argon2.verify(hash, password.toCharArray())
        } catch (e: Exception) {
            false
        }
    }

    // ==================== BCrypt ====================

    private fun hashBcrypt(password: String): String {
        return org.mindrot.jbcrypt.BCrypt.hashpw(password, org.mindrot.jbcrypt.BCrypt.gensalt(12))
    }

    private fun verifyBcrypt(password: String, hash: String): Boolean {
        return try {
            org.mindrot.jbcrypt.BCrypt.checkpw(password, hash)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== SHA-256 (AuthMe format: $SHA$salt$hash) ====================

    private fun hashSha256AuthMe(password: String): String {
        val salt = generateSalt()
        val hash = sha256Hex(sha256Hex(password) + salt)
        return "\$SHA\$$salt\$$hash"
    }

    private fun verifySha256AuthMe(password: String, storedHash: String): Boolean {
        return try {
            val parts = storedHash.split("\$").filter { it.isNotEmpty() }
            // parts: ["SHA", salt, hash]
            if (parts.size != 3 || parts[0] != "SHA") return false
            val salt = parts[1]
            val expectedHash = parts[2]
            val computedHash = sha256Hex(sha256Hex(password) + salt)
            computedHash.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
                .replace("+", ".")
                .replace("/", ".")
                .take(16)
    }

    // ==================== MD5 ====================

    private fun verifyMd5(password: String, hash: String): Boolean {
        return DigestUtils.md5Hex(password).equals(hash, ignoreCase = true)
    }
}
