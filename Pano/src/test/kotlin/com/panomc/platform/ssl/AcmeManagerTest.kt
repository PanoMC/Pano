package com.panomc.platform.ssl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AcmeManagerTest {
    @Test
    fun `only ACME tokens are looked up`() {
        assertTrue(AcmeManager.isValidToken("LoqXcYV8q5ONbJQxbmR7SCTNo3tiAXDfowyjxAjEuX0"))
        listOf(
            null,
            "",
            "..\\..\\config.conf",
            "../../config.conf",
            "a.b",
            "token\nINFO forged",
            "x".repeat(129)
        ).forEach { assertFalse(AcmeManager.isValidToken(it), it.toString()) }
    }
}
