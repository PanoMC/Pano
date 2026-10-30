package com.panomc.platform.util

import de.mkammerer.argon2.Argon2Factory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class JavaArgon2idTest {
    private val native = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val java = PasswordHasher.JavaArgon2id

    @Test
    fun `native argon2 accepts the java hash`() {
        val hash = java.hash("şifre-123", 3, 65536, 1, SecureRandom())

        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=65536,t=3,p=1\$"))
        assertTrue(native.verify(hash, "şifre-123".toCharArray()))
        assertFalse(native.verify(hash, "wrong".toCharArray()))
    }

    @Test
    fun `java argon2 accepts the native hash`() {
        val hash = native.hash(3, 65536, 1, "şifre-123".toCharArray())

        assertTrue(java.verify("şifre-123", hash))
        assertFalse(java.verify("wrong", hash))
    }

    @Test
    fun `java argon2 accepts other parameters and rejects garbage`() {
        val hash = native.hash(2, 4096, 2, "pw".toCharArray())

        assertTrue(java.verify("pw", hash))
        assertFalse(java.verify("pw", "\$argon2id\$v=19\$m=x,t=2,p=2\$abc\$def"))
        assertFalse(java.verify("pw", "\$argon2i\$v=19\$m=4096,t=2,p=2\$abc\$def"))
    }
}
