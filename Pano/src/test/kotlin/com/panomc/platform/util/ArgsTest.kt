package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArgsTest {
    @Test
    fun `a flag matches with one or two dashes`() {
        assertTrue(Args.hasFlag(arrayOf("-dev"), "--dev"))
        assertTrue(Args.hasFlag(arrayOf("--dev"), "--dev"))
        assertTrue(Args.hasFlag(arrayOf("--nogui"), "-nogui"))
        assertTrue(Args.hasFlag(listOf("-bg"), "-bg"))
    }

    @Test
    fun `other spellings do not match`() {
        assertFalse(Args.hasFlag(arrayOf("dev"), "--dev"))
        assertFalse(Args.hasFlag(arrayOf("---dev"), "--dev"))
        assertFalse(Args.hasFlag(arrayOf("-DEV"), "--dev"))
        assertFalse(Args.hasFlag(arrayOf("--devx"), "--dev"))
        assertFalse(Args.hasFlag(arrayOf("-", "--"), "--"))
    }
}
