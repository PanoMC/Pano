package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegexesTest {
    private val email = Regex(Regexes.EMAIL)

    @Test
    fun `real addresses are accepted`() {
        listOf(
            "name@studio.online",
            "a@b.digital",
            "x@y.agency",
            "user+tag@gmail.com",
            "first.last@mail.example.co.uk",
            "old_style-1@ex-ample.com",
            "me@panomc.com"
        ).forEach { assertTrue(email.matches(it), it) }
    }

    @Test
    fun `broken or dangerous addresses are refused`() {
        listOf(
            "",
            "plain",
            "@example.com",
            "a@",
            "a@com",
            "a b@example.com",
            "a@exa mple.com",
            "a@example.com\r\nBcc: x@y.com",
            "a,b@example.com",
            "\"a\"@example.com",
            "<a@example.com>",
            "a@@example.com",
            "a".repeat(65) + "@example.com",
            "a@" + "b".repeat(250) + ".com"
        ).forEach { assertFalse(email.matches(it), it) }
    }
}
