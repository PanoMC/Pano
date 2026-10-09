package com.panomc.platform.webhook

import com.panomc.platform.util.SecretCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class SecretCipherTest {
    private val cipher = WebhookTestSupport.cipher()

    @Test
    fun `a value round trips and two encryptions differ`() {
        val a = cipher.encrypt("whsec_abc")
        val b = cipher.encrypt("whsec_abc")

        assertTrue(a.startsWith("v1:"))
        assertNotEquals(a, b)
        assertEquals("whsec_abc", cipher.decrypt(a))
        assertEquals("whsec_abc", cipher.decrypt(b))
        assertEquals("çağrı 🎮", cipher.decrypt(cipher.encrypt("çağrı 🎮")))
    }

    @Test
    fun `a wrong key, a damaged value or bad base64 gives null`() {
        val stored = cipher.encrypt("secret")
        val other = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { 9 })
        val raw = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()

        assertNull(other.decrypt(stored))
        assertNull(cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(raw)))
        assertNull(cipher.decrypt("v1:not base64 !!"))
        assertNull(cipher.decrypt("v1:AAAA"))
    }

    @Test
    fun `a value without the prefix is legacy plaintext`() {
        assertEquals("plain", cipher.decrypt("plain"))
        assertTrue(cipher.needsEncryption("plain"))
        assertFalse(cipher.needsEncryption(cipher.encrypt("x")))
        assertFalse(cipher.needsEncryption(""))
    }

    @Test
    fun `the key file is webhook key, created once and read back`(@TempDir dir: Path) {
        assertEquals("webhook.key", SecretCipher.KEY_FILE)

        val first = SecretCipher.load(dir)
        val file = dir.resolve("webhook.key")

        assertTrue(first.keyCreated)
        assertTrue(Files.isRegularFile(file))
        assertEquals(32, Base64.getDecoder().decode(Files.readString(file).trim()).size)

        val second = SecretCipher.load(dir)

        assertFalse(second.keyCreated)
        assertEquals("s", second.decrypt(first.encrypt("s")))
        assertFalse(Files.exists(dir.resolve("webhook.key.tmp")))
    }

    @Test
    fun `an invalid key file is moved aside and never deleted`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("webhook.key"), "not a key")

        val cipher = SecretCipher.load(dir)

        assertTrue(cipher.keyCreated)
        assertEquals("not a key", Files.readString(dir.resolve("webhook.key.invalid-1")))
        assertTrue(Files.isRegularFile(dir.resolve("webhook.key")))
    }

    @Test
    fun `the key does not show in toString`() {
        assertEquals("SecretCipher(AES-256-GCM)", cipher.toString())
    }
}
