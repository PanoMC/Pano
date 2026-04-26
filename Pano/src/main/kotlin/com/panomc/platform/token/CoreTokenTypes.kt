package com.panomc.platform.token

import java.util.*

/**
 * Built-in core token types for the Pano platform.
 * Each object produces a DB-compatible name via the [TokenType.getName] default implementation.
 */

/** User login session token. Expires in 1 month. */
object AuthenticationTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, 1)
        return calendar.timeInMillis
    }
}

/** Email activation token. Expires in 15 minutes. */
object ActivationTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}

/** Password reset token. Expires in 30 minutes. */
object ResetPasswordTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 30)
        return calendar.timeInMillis
    }
}

/** Server-to-platform authentication. Expires in 10 years. */
object ServerAuthenticationTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 10)
        return calendar.timeInMillis
    }
}

/** Email change verification token. Expires in 15 minutes. */
object ChangeEmailTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}

/** Registration via link code token. Expires in 15 minutes. */
object RegisterWithLinkCodeTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}

/** Username setup token. Expires in 15 minutes. */
object SetUsernameTokenType : TokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}
