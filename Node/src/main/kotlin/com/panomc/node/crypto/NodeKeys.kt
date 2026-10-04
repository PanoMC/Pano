package com.panomc.node.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * The node's own RSA identity and the one thing it is used for.
 *
 * Pairing hands Pano an X.509 public key and gets the AES session key back wrapped with it, so the
 * key that decrypts every later frame never crosses the network in the clear even when the
 * operator paired over plain HTTP on a private network. The private half lives in config.conf and
 * nowhere else.
 */
object NodeKeys {
    /** A fresh 2048-bit pair, encoded exactly the way config.conf stores it. */
    fun generate(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("RSA")

        generator.initialize(2048, SecureRandom())

        val keyPair = generator.generateKeyPair()
        val encoder = Base64.getEncoder()

        return encoder.encodeToString(keyPair.public.encoded) to encoder.encodeToString(keyPair.private.encoded)
    }

    /** Unwraps the Base64 AES key Pano returned from pairing. */
    fun unwrapAesKey(base64PrivateKey: String, base64WrappedKey: String): String {
        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64PrivateKey))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)

        val cipher = Cipher.getInstance("RSA")

        cipher.init(Cipher.DECRYPT_MODE, privateKey)

        return String(cipher.doFinal(Base64.getDecoder().decode(base64WrappedKey)))
    }
}
