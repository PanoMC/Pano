package com.panomc.platform.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.util.Base64

class PanoArcCryptoArgon2Test {
    @Test
    fun `the java argon2id derives the key libargon2 derives`() {
        // phc-winner-argon2 reference: "password", salt "somesalt", t=2, m=64 MiB, p=4, 32 bytes.
        val expected = Base64.getDecoder().decode("GpZ3sK/oH9p7VIiV56G/64Zo/8GaUw434IimaPqxwCo")

        assertArrayEquals(expected, PanoArcCrypto.javaArgon2id("password".toCharArray(), "somesalt".toByteArray(), 2, 65536, 4))
    }

    @Test
    fun `the key is the same whichever implementation runs`() {
        val salt = ByteArray(PanoArcCrypto.SALT_BYTES) { it.toByte() }
        val passphrase = "şifre-123".toCharArray()

        assertArrayEquals(
            PanoArcCrypto.javaArgon2id(passphrase, salt, 1, 1024, 1),
            PanoArcCrypto.argon2id(passphrase, salt, 1, 1024, 1)
        )
    }
}
