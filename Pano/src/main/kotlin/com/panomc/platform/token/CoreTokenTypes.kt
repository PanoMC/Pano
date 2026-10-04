package com.panomc.platform.token

import org.springframework.stereotype.Component
import java.util.*

/**
 * Built-in core token types for the Pano platform.
 * Each object is a Spring bean ([Component]) and implements [CoreTokenType] so it is collected
 * into `List<CoreTokenType>` and registered before the host Gson bean is created.
 */

/** User login session token. Expires in 1 month. */
@Component
object AuthenticationTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, 1)
        return calendar.timeInMillis
    }
}

/** Email activation token. Expires in 15 minutes. */
@Component
object ActivationTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}

/** Password reset token. Expires in 30 minutes. */
@Component
object ResetPasswordTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 30)
        return calendar.timeInMillis
    }
}

/** Server-to-platform authentication. Expires in 10 years. */
@Component
object ServerAuthenticationTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 10)
        return calendar.timeInMillis
    }
}

/**
 * Node-to-platform authentication. Expires in 10 years.
 *
 * Same shape and lifetime as [ServerAuthenticationTokenType] but a separate type on purpose: a
 * node token can start processes on someone's machine, so a stolen server token must never be
 * accepted on the node socket and vice versa. The subject is the node id.
 */
@Component
object NodeAuthenticationTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 10)
        return calendar.timeInMillis
    }
}

/** Email change verification token. Expires in 15 minutes. */
@Component
object ChangeEmailTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}

/** Registration via link code token. Expires in 15 minutes. */
@Component
object RegisterWithLinkCodeTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}

/** Username setup token. Expires in 15 minutes. */
@Component
object SetUsernameTokenType : CoreTokenType {
    override fun getExpireDate(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)
        return calendar.timeInMillis
    }
}
