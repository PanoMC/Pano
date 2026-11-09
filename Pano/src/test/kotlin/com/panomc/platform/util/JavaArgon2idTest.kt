package com.panomc.platform.util

import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class JavaArgon2idTest {
    private val java = PasswordHasher.JavaArgon2id

    /** The native argon2, or null where its library cannot load (Windows on ARM, Android, other libcs). */
    private val native: Argon2? = try {
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).also { it.hash(1, 8, 1, "probe".toCharArray()) }
    } catch (_: Throwable) {
        null
    }

    @Test
    fun `java argon2 accepts hashes made by the native argon2 library`() {
        // Reference strings made by libargon2 (phc-winner-argon2 README, argon2-cffi docs) and by Bun's argon2id:
        // what a Pano on Linux has stored. They need no native library here, so they run on every machine.
        assertTrue(java.verify("password", "\$argon2id\$v=19\$m=65536,t=2,p=4\$c29tZXNhbHQ\$GpZ3sK/oH9p7VIiV56G/64Zo/8GaUw434IimaPqxwCo"))
        assertTrue(
            java.verify(
                "correct horse battery staple",
                "\$argon2id\$v=19\$m=65536,t=3,p=4\$MIIRqgvgQbgj220jfp0MPA\$YfwJSVjtjSU0zzV/P3S9nnQ/USre2wvJMjfCIjrTQbg"
            )
        )

        val panoDefault = "\$argon2id\$v=19\$m=65536,t=3,p=1\$Nq02xQrOOZefJJ+A2r1HGnFeLNVpK+7Au1B2B3sMOJs\$WFuYLwnZFSBAeKiuYjpqmEK0BA7QD07fUcyW1QHl/+o"
        assertTrue(java.verify("şifre-123", panoDefault))
        assertFalse(java.verify("wrong", panoDefault))
        assertFalse(java.verify("password", "\$argon2id\$v=19\$m=65536,t=2,p=4\$c29tZXNhbHQ\$HpZ3sK/oH9p7VIiV56G/64Zo/8GaUw434IimaPqxwCo"))
    }

    @Test
    fun `java argon2 checks its own hashes in the native format`() {
        val hash = java.hash("şifre-123", 3, 65536, 1, SecureRandom())

        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=65536,t=3,p=1\$"))
        assertTrue(java.verify("şifre-123", hash))
        assertFalse(java.verify("wrong", hash))
    }

    @Test
    fun `java argon2 rejects garbage`() {
        assertFalse(java.verify("pw", "\$argon2id\$v=19\$m=x,t=2,p=2\$abc\$def"))
        assertFalse(java.verify("pw", "\$argon2i\$v=19\$m=4096,t=2,p=2\$abc\$def"))
        assertFalse(PasswordHasher().verify("pw", "\$argon2id\$v=19\$m=4096,t=2,p=2\$!!\$!!"))
    }

    @Test
    fun `native argon2 accepts the java hash`() {
        val native = native
        assumeTrue(native != null, "native argon2 does not load on this machine")

        val hash = java.hash("şifre-123", 3, 65536, 1, SecureRandom())

        assertTrue(native!!.verify(hash, "şifre-123".toCharArray()))
        assertFalse(native.verify(hash, "wrong".toCharArray()))
    }

    @Test
    fun `java argon2 accepts a fresh native hash with other parameters`() {
        val native = native
        assumeTrue(native != null, "native argon2 does not load on this machine")

        assertTrue(java.verify("şifre-123", native!!.hash(3, 65536, 1, "şifre-123".toCharArray())))
        assertTrue(java.verify("pw", native.hash(2, 4096, 2, "pw".toCharArray())))
    }

    @Test
    fun `the password hasher signs in with argon2id whether or not the native library loads`() {
        val hasher = PasswordHasher()
        val hash = hasher.hash("şifre-123")

        assertTrue(hash.startsWith("\$argon2id\$v=19\$"))
        assertTrue(hasher.verify("şifre-123", hash))
        assertFalse(hasher.verify("wrong", hash))
    }
}
