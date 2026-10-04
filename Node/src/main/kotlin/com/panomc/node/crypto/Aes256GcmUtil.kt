package com.panomc.node.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The frame codec every byte on the node socket goes through.
 *
 * A deliberate copy of Pano's and the Minecraft plugin's utility of the same name rather than a
 * shared artifact: the wire format is Base64 of a 12-byte IV followed by the AES-256-GCM
 * ciphertext, and the daemon must keep speaking it even when it is a release or two behind the
 * platform it connects to. Three copies that can only be changed together are safer here than one
 * dependency that silently re-frames an old daemon's traffic.
 */
object Aes256GcmUtil {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    /** 96 bits, the size GCM is specified for and the size Pano writes. */
    private const val IV_SIZE = 12

    private const val TAG_LENGTH = 128

    fun base64ToSecretKey(base64Key: String): SecretKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)

        require(keyBytes.size == 32) { "AES-256 key must be 32 bytes, got ${keyBytes.size}" }

        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    fun encrypt(plainText: String, key: SecretKey): String {
        val iv = ByteArray(IV_SIZE)

        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))

        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val output = ByteArray(iv.size + encryptedBytes.size)

        System.arraycopy(iv, 0, output, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, output, iv.size, encryptedBytes.size)

        return Base64.getEncoder().encodeToString(output)
    }

    fun decrypt(base64Cipher: String, key: SecretKey): String {
        val inputBytes = Base64.getDecoder().decode(base64Cipher)

        require(inputBytes.size > IV_SIZE) { "Invalid input: missing IV" }

        val iv = inputBytes.copyOfRange(0, IV_SIZE)
        val cipherBytes = inputBytes.copyOfRange(IV_SIZE, inputBytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))

        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private val secureRandom = SecureRandom()
}
