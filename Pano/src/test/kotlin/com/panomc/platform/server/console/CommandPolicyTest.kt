package com.panomc.platform.server.console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandPolicyTest {

    @Test
    fun `the first token is the command word`() {
        assertEquals("op", CommandPolicy.firstToken("op Notch"))
        assertEquals("op", CommandPolicy.firstToken("  op   Notch  "))
        assertEquals("stop", CommandPolicy.firstToken("stop"))
    }

    @Test
    fun `a leading slash never smuggles a command past its pattern`() {
        assertEquals("op", CommandPolicy.firstToken("/op Notch"))
        assertTrue(CommandPolicy.matches("op", "/op Notch"))
    }

    @Test
    fun `a namespace is part of the command name`() {
        assertEquals("worldedit:set", CommandPolicy.firstToken("worldedit:set stone"))
        assertTrue(CommandPolicy.matches("worldedit:set", "worldedit:set stone"))
    }

    @Test
    fun `matching ignores case on both sides`() {
        assertTrue(CommandPolicy.matches("OP", "op Notch"))
        assertTrue(CommandPolicy.matches("op", "OP Notch"))
    }

    @Test
    fun `an exact pattern does not match a longer command`() {
        assertFalse(CommandPolicy.matches("op", "opme"))
        assertFalse(CommandPolicy.matches("whitelist", "whitelistadd"))
    }

    @Test
    fun `a trailing star matches every command with that prefix`() {
        assertTrue(CommandPolicy.matches("whitelist*", "whitelist add Notch"))
        assertTrue(CommandPolicy.matches("whitelist*", "whitelistadd Notch"))
        assertFalse(CommandPolicy.matches("whitelist*", "white list"))
    }

    @Test
    fun `a bare star denies everything, which is read-only console`() {
        assertTrue(CommandPolicy.matches("*", "say hello"))
        assertTrue(CommandPolicy.matches("*", "op Notch"))
    }

    @Test
    fun `arguments are never matched`() {
        assertFalse(CommandPolicy.matches("Notch", "op Notch"))
        assertFalse(CommandPolicy.matches("stop", "say stop"))
    }

    @Test
    fun `an empty pattern or command denies nothing`() {
        assertFalse(CommandPolicy.matches("", "op Notch"))
        assertFalse(CommandPolicy.matches("   ", "op Notch"))
        assertFalse(CommandPolicy.matches("op", "   "))
    }

    @Test
    fun `the matching pattern is what comes back`() {
        assertEquals("op", CommandPolicy.deniedPattern(listOf("stop", "op", "deop"), "op Notch"))
        assertEquals("whitelist*", CommandPolicy.deniedPattern(listOf("op", "whitelist*"), "whitelist add x"))
    }

    @Test
    fun `a command nothing denies comes back as null`() {
        assertNull(CommandPolicy.deniedPattern(listOf("op", "deop", "stop"), "say hello"))
        assertNull(CommandPolicy.deniedPattern(emptyList(), "op Notch"))
    }

    @Test
    fun `the returned pattern is normalised`() {
        assertEquals("op", CommandPolicy.deniedPattern(listOf("  OP  "), "op Notch"))
    }

    @Test
    fun `an absurdly long pattern is ignored rather than quietly truncated`() {
        val pattern = "a".repeat(CommandPolicy.MAX_PATTERN_LENGTH + 1) + "*"

        assertFalse(CommandPolicy.matches(pattern, "a".repeat(200)))
        assertNull(CommandPolicy.deniedPattern(listOf(pattern), "a".repeat(200)))
    }

    @Test
    fun `a pattern at the limit still works`() {
        val pattern = "a".repeat(CommandPolicy.MAX_PATTERN_LENGTH)

        assertTrue(CommandPolicy.matches(pattern, pattern))
    }
}
