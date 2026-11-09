package com.panomc.platform.token

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserTokensTest {
    @Test
    fun `server and node tokens are never treated as user tokens`() {
        assertTrue(UserTokens.isMachineType(ServerAuthenticationTokenType))
        assertTrue(UserTokens.isMachineType(NodeAuthenticationTokenType))
    }

    @Test
    fun `user tokens are user tokens`() {
        listOf(AuthenticationTokenType, ActivationTokenType, ResetPasswordTokenType, ChangeEmailTokenType)
            .forEach { assertFalse(UserTokens.isMachineType(it)) }
    }
}
