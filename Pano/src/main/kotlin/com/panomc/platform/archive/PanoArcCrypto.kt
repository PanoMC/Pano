package com.panomc.platform.archive

import de.mkammerer.argon2.Argon2Factory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key schedule of the `.panoarc` envelope (archive-format.md section 3).
 *
 * The platform targets JDK 11, which has no HKDF, so HKDF-SHA256 (RFC 5869) is written out here
 * from HMAC. It is small and pinned by the RFC test vector in the tests. Every other party that
 * reads or writes the format (the Portal agent) must derive exactly the same way:
 * no salt (= 32 zero bytes), info = the ASCII label, L = 32.
 */
object PanoArcCrypto {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 8

    const val ENC_INFO = "panoarc-v1-enc"
    const val CHECK_INFO = "panoarc-v1-check"
    const val CHECK_MESSAGE = "panoarc-v1"

    const val ARGON2_T = 3
    const val ARGON2_M_KIB = 65536
    const val ARGON2_P = 1

    val random = SecureRandom()

    fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")

        mac.init(SecretKeySpec(key, "HmacSHA256"))

        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256 (extract + expand). An empty [salt] means HashLen zero bytes. */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int = KEY_BYTES, salt: ByteArray = ByteArray(0)): ByteArray {
        require(length in 1..255 * 32) { "Invalid HKDF length." }

        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))

            val take = minOf(previous.size, length - offset)

            System.arraycopy(previous, 0, out, offset, take)

            offset += take
            counter++
        }

        return out
    }

    fun encKey(masterKey: ByteArray) = hkdfSha256(masterKey, ENC_INFO.toByteArray(StandardCharsets.US_ASCII))

    fun checkKey(masterKey: ByteArray) = hkdfSha256(masterKey, CHECK_INFO.toByteArray(StandardCharsets.US_ASCII))

    /** The header check value: proves the key before any chunk is touched. */
    fun checkValue(masterKey: ByteArray) =
        hmacSha256(checkKey(masterKey), CHECK_MESSAGE.toByteArray(StandardCharsets.US_ASCII))

    fun checkMatches(masterKey: ByteArray, expected: ByteArray) =
        MessageDigest.isEqual(checkValue(masterKey), expected)

    /** Argon2id(passphrase, salt) as a 32-byte master key. The passphrase is UTF-8 encoded. */
    fun argon2id(passphrase: CharArray, salt: ByteArray, t: Int, mKiB: Int, p: Int): ByteArray =
        Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id, SALT_BYTES, KEY_BYTES)
            .rawHash(t, mKiB, p, passphrase, StandardCharsets.UTF_8, salt)
}
