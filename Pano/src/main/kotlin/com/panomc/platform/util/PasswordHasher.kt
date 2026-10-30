package com.panomc.platform.util

import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import org.apache.commons.codec.digest.DigestUtils
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.slf4j.LoggerFactory
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

    // The native argon2 is loaded through JNA, whose bundled libraries need glibc. Where they cannot load (Android /
    // Termux, other libcs) the same hashes are made and checked in pure Java (Bouncy Castle), in the same format.
    private val argon2: Argon2? = try {
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    } catch (e: Throwable) {
        LoggerFactory.getLogger(PasswordHasher::class.java)
            .warn("Native Argon2 is not available here ({}), using the Java implementation.", e.toString())
        null
    }
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
        return argon2?.hash(3, 65536, 1, password.toCharArray()) ?: JavaArgon2id.hash(password, 3, 65536, 1, secureRandom)
    }

    private fun verifyArgon2id(password: String, hash: String): Boolean {
        return try {
            argon2?.verify(hash, password.toCharArray()) ?: JavaArgon2id.verify(password, hash)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Argon2id in pure Java, writing and reading the PHC string the native argon2 uses
     * (`$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>`, unpadded Base64), so either one checks the other's hashes.
     */
    internal object JavaArgon2id {
        private const val SALT_LENGTH = 16
        private const val HASH_LENGTH = 32
        private val encoder = Base64.getEncoder().withoutPadding()
        private val decoder = Base64.getDecoder()

        fun hash(password: String, iterations: Int, memoryKb: Int, parallelism: Int, random: SecureRandom): String {
            val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
            val hash = derive(password, salt, iterations, memoryKb, parallelism, Argon2Parameters.ARGON2_VERSION_13, HASH_LENGTH)

            return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=$parallelism\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
        }

        fun verify(password: String, encoded: String): Boolean {
            // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]; hashes from before v=19 have no version part.
            val parts = encoded.split("\$")
            if (parts.size !in 5..6 || parts[0].isNotEmpty() || parts[1] != "argon2id") return false

            val version = if (parts.size == 6) parts[2].removePrefix("v=").toIntOrNull() ?: return false else 0x10
            val params = parts[parts.size - 3].split(",").associate {
                val (key, value) = it.split("=", limit = 2).let { kv -> if (kv.size == 2) kv else return false }
                key to (value.toIntOrNull() ?: return false)
            }
            val memoryKb = params["m"] ?: return false
            val iterations = params["t"] ?: return false
            val parallelism = params["p"] ?: return false
            val salt = decoder.decode(parts[parts.size - 2])
            val expected = decoder.decode(parts[parts.size - 1])

            val actual = derive(password, salt, iterations, memoryKb, parallelism, version, expected.size)
            return MessageDigest.isEqual(actual, expected)
        }

        private fun derive(
            password: String, salt: ByteArray, iterations: Int, memoryKb: Int, parallelism: Int, version: Int, length: Int
        ): ByteArray {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(version)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
            val generator = Argon2BytesGenerator().apply { init(parameters) }

            return ByteArray(length).also { generator.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
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
