package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class NodePairingCodeManagerTest {
    @Test
    fun `generates a six digit code`() {
        repeat(2000) {
            val code = NodePairingCodeManager.generateCode()

            assertTrue(code in 100000..999999, "code out of range: $code")
            assertEquals(6, code.toString().length)
        }
    }

    @Test
    fun `uses the whole range`() {
        val codes = (1..2000).map { NodePairingCodeManager.generateCode() }.toSet()

        assertTrue(codes.size > 1000, "generator looks stuck: ${codes.size} distinct codes")
    }

    @Test
    fun `is driven by the random it is given`() {
        val fixed = object : Random() {
            override fun nextInt(bound: Int) = 23456
        }

        assertEquals(123456, NodePairingCodeManager.generateCode(fixed))
    }

    @Test
    fun `matches the exact code`() {
        assertTrue(NodePairingCodeManager.isMatch(123456, "123456"))
    }

    @Test
    fun `matches a pasted code with surrounding whitespace`() {
        assertTrue(NodePairingCodeManager.isMatch(123456, "  123456\n"))
    }

    @Test
    fun `rejects a different code`() {
        assertFalse(NodePairingCodeManager.isMatch(123456, "123457"))
    }

    @Test
    fun `rejects a padded or reformatted code`() {
        assertFalse(NodePairingCodeManager.isMatch(123456, "0123456"))
        assertFalse(NodePairingCodeManager.isMatch(123456, "123 456"))
    }

    @Test
    fun `rejects a missing code`() {
        assertFalse(NodePairingCodeManager.isMatch(123456, null))
        assertFalse(NodePairingCodeManager.isMatch(123456, ""))
    }
}
