package com.panomc.platform.frontend.pages

import com.panomc.platform.annotation.FallbackPageDefinition
import com.panomc.platform.frontend.CoreFrontendTargets
import com.panomc.platform.frontend.FallbackPage
import com.panomc.platform.frontend.FrontendUrlMap
import io.vertx.ext.web.RoutingContext

/** What the pages of the mail links share: the token of the link and where "sign in" goes. */
private suspend fun linkModel(context: RoutingContext, urls: FrontendUrlMap): Map<String, Any?> = mapOf(
    "token" to (context.request().getParam("token") ?: ""),
    "loginUrl" to runCatching { urls.url(CoreFrontendTargets.AUTH_LOGIN) }.getOrNull()
)

/** The link of the activation mail (`POST /auth/verify-email`). */
@FallbackPageDefinition
class ActivateFallbackPage(private val urls: FrontendUrlMap) :
    FallbackPage(CoreFrontendTargets.AUTH_ACTIVATE, "fallback/auth.activate.hbs") {
    override suspend fun model(context: RoutingContext) = linkModel(context, urls)
}

/** The link of the mail that confirms a new e-mail address (`POST /auth/verify-new-email`). */
@FallbackPageDefinition
class ActivateNewEmailFallbackPage(private val urls: FrontendUrlMap) :
    FallbackPage(CoreFrontendTargets.AUTH_ACTIVATE_NEW_EMAIL, "fallback/auth.activate-new-email.hbs") {
    override suspend fun model(context: RoutingContext) = linkModel(context, urls)
}

/** The link of the password reset mail (`POST /auth/renew-password`). */
@FallbackPageDefinition
class RenewPasswordFallbackPage(private val urls: FrontendUrlMap) :
    FallbackPage(CoreFrontendTargets.AUTH_RENEW_PASSWORD, "fallback/auth.renew-password.hbs") {
    override suspend fun model(context: RoutingContext) = linkModel(context, urls)
}

/**
 * Sign-in: user name and password, the link-code step when the account has none, and one generic code input
 * when a deny names the field (`details.challengeField`, for example auth-guard's `totpCode`).
 */
@FallbackPageDefinition
class LoginFallbackPage : FallbackPage(CoreFrontendTargets.AUTH_LOGIN, "fallback/auth.login.hbs") {
    override suspend fun model(context: RoutingContext): Map<String, Any?> =
        mapOf("clientConfig" to mapOf("next" to (safeNext(context.request().getParam("next")) ?: "")))

    companion object {
        /** A site path (`/x`, never `//x` or `/\x`) or null; nothing else is ever a place to go after a sign-in. */
        internal fun safeNext(value: String?): String? {
            if (value.isNullOrEmpty() || value.length > 2000) {
                return null
            }

            if (!value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\") || value.any { it < ' ' }) {
                return null
            }

            return value
        }
    }
}
