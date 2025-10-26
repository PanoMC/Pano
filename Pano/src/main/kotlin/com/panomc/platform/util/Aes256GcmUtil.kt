package com.panomc.platform.util

import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Aes256GcmUtil {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val IV_SIZE = 12                 // 12 bytes (96 bits) recommended for GCM
    private const val TAG_LENGTH = 128             // 128-bit authentication tag

    /**
     * Generates a 256-bit AES key and returns it as a Base64 string.
     */
    fun generateBase64Key256(): String {
        val keyBytes = ByteArray(32) // 32 bytes = 256 bits
        SecureRandom().nextBytes(keyBytes)
        return Base64.getEncoder().encodeToString(keyBytes)
    }

    /**
     * Converts a Base64 encoded AES key to a SecretKey instance.
     */
    fun base64ToSecretKey(base64Key: String): SecretKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        require(keyBytes.size == 32) { "AES-256 key must be 32 bytes, got ${keyBytes.size}" }
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    /**
     * Generates a new random IV.
     */
    private fun generateIv(): ByteArray {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        return iv
    }

    /**
     * Encrypts a string using AES-256-GCM.
     * Returns Base64( IV + CipherText ).
     */
    fun encrypt(plainText: String, key: SecretKey): String {
        val iv = generateIv()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Combine IV + encrypted data
        val output = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, output, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, output, iv.size, encryptedBytes.size)

        return Base64.getEncoder().encodeToString(output)
    }

    /**
     * Decrypts a Base64( IV + CipherText ) string.
     */
    fun decrypt(base64Cipher: String, key: SecretKey): String {
        val inputBytes = Base64.getDecoder().decode(base64Cipher)
        require(inputBytes.size > IV_SIZE) { "Invalid input: missing IV" }

        val iv = inputBytes.copyOfRange(0, IV_SIZE)
        val cipherBytes = inputBytes.copyOfRange(IV_SIZE, inputBytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        val plainBytes = cipher.doFinal(cipherBytes)

        return String(plainBytes, Charsets.UTF_8)
    }
}