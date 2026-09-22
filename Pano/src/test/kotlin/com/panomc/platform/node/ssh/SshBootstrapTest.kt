package com.panomc.platform.node.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class SshBootstrapTest {
    @Test
    fun `a fingerprint is the unpadded base64 sha256 OpenSSH prints`() {
        val blob = byteArrayOf(1, 2, 3, 4, 5)

        val expected = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

        assertEquals(expected, SshHostKeyFingerprint.format(blob))
    }

    @Test
    fun `a fingerprint carries no padding`() {
        assertFalse(SshHostKeyFingerprint.format(byteArrayOf(9)).endsWith("="))
    }

    @Test
    fun `the same key always formats the same way`() {
        assertEquals(
            SshHostKeyFingerprint.format(byteArrayOf(7, 7, 7)),
            SshHostKeyFingerprint.format(byteArrayOf(7, 7, 7))
        )
    }

    @Test
    fun `fingerprints match with or without the prefix and around whitespace`() {
        val fingerprint = SshHostKeyFingerprint.format(byteArrayOf(4, 2))
        val bare = fingerprint.removePrefix("SHA256:")

        assertTrue(SshHostKeyFingerprint.matches(fingerprint, fingerprint))
        assertTrue(SshHostKeyFingerprint.matches(fingerprint, " $bare "))
        assertTrue(SshHostKeyFingerprint.matches(bare, fingerprint))
    }

    @Test
    fun `a different key never matches, and neither does nothing`() {
        val fingerprint = SshHostKeyFingerprint.format(byteArrayOf(1))

        assertFalse(SshHostKeyFingerprint.matches(fingerprint, SshHostKeyFingerprint.format(byteArrayOf(2))))
        assertFalse(SshHostKeyFingerprint.matches(fingerprint, null))
        assertFalse(SshHostKeyFingerprint.matches(null, fingerprint))
        assertFalse(SshHostKeyFingerprint.matches("", ""))
    }

    @Test
    fun `the command pipes the script into sh with the pairing credential`() {
        assertEquals(
            "sh -s -- --pano 'https://pano.example.com' --code 'abc123'",
            SshBootstrapCommand.build("https://pano.example.com", "abc123", sudo = false)
        )
    }

    @Test
    fun `sudo never prompts`() {
        assertTrue(
            SshBootstrapCommand.build("https://pano.example.com", "abc", sudo = true).startsWith("sudo -n sh -s --")
        )
    }

    @Test
    fun `a name is passed through when one was given`() {
        assertEquals(
            "sh -s -- --pano 'https://p' --code 'c' --name 'box one'",
            SshBootstrapCommand.build("https://p", "c", sudo = false, name = "box one")
        )

        assertFalse(SshBootstrapCommand.build("https://p", "c", sudo = false, name = " ").contains("--name"))
    }

    @Test
    fun `a quote in any value cannot break out of its argument`() {
        val command = SshBootstrapCommand.build("https://p", "'; rm -rf / #", sudo = false)

        assertEquals("sh -s -- --pano 'https://p' --code ''\\''; rm -rf / #'", command)
    }
}
