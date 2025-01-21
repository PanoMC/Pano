package com.panomc.platform.util

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom

object KeyGeneratorUtil {
    fun generateJWTKey(): String {
        // Generate a 64-byte secret key
        val secretKey = ByteArray(64)
        SecureRandom().nextBytes(secretKey)

        // Convert the secret key to a hexadecimal string
        return BigInteger(1, secretKey).toString(16)
    }

    fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())

        val keyPair = keyGen.generateKeyPair()

        return keyPair
    }
}