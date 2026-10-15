package com.panomc.platform.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure rule behind `LOGIN_EMAIL_NOT_VERIFIED`: only a site that requires verification turns an account away. */
class VerificationRuleTest {
    @Test
    fun `check not waived and verification required must be verified`() {
        assertTrue(AuthProvider.mustBeVerified(dontCheckVerified = false, requireEmailVerification = true))
    }

    @Test
    fun `check not waived but verification not required never blocks`() {
        assertFalse(AuthProvider.mustBeVerified(dontCheckVerified = false, requireEmailVerification = false))
    }

    @Test
    fun `waived check never blocks when verification is required`() {
        assertFalse(AuthProvider.mustBeVerified(dontCheckVerified = true, requireEmailVerification = true))
    }

    @Test
    fun `waived check never blocks when verification is not required`() {
        assertFalse(AuthProvider.mustBeVerified(dontCheckVerified = true, requireEmailVerification = false))
    }
}
