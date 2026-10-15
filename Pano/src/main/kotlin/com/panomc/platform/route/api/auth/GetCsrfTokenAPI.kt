package com.panomc.platform.route.api.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.CredentialSource
import com.panomc.platform.model.*
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.util.CSRFTokenGenerator
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /auth/csrf` (doc 05 §4). The CSRF cookie is HttpOnly, so a page cannot read the token it must repeat in
 * `X-CSRF-Token`; this endpoint hands it over.
 *
 * - cookie session: `{"csrfToken": "<the CSRF cookie's value>"}`; a session whose CSRF cookie is missing gets a fresh
 *   one, set through [AuthProvider.setCookies];
 * - `Authorization: Bearer` (a server-side front-end, an API client): `{"csrfToken": null}`, a Bearer needs none;
 * - no session, or a stale cookie: `401` (the client just logs in), by [LoggedInApi].
 *
 * Never cached.
 */
@Endpoint
class GetCsrfTokenAPI(
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/auth/csrf", RouteType.GET))

    override val doc = EndpointDoc(summary = "The CSRF token of the current cookie session (null for a Bearer token).", tag = "auth")

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        if (authProvider.credentialSource(context) != CredentialSource.COOKIE) {
            return Successful(mapOf("csrfToken" to null))
        }

        val existing = csrfCookieValue(context.request().getHeader("cookie"))

        if (existing != null) {
            return Successful(mapOf("csrfToken" to existing))
        }

        val token = CSRFTokenGenerator.nextToken()

        authProvider.setCookies(context, authProvider.getTokenFromRoutingContext(context)!!, token)

        return Successful(mapOf("csrfToken" to token))
    }

    companion object {
        private val NAMES = listOf(
            AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME,
            AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME + "_http"
        )

        /** The value of the CSRF cookie in a `Cookie` header (the secure variant first), or `null` when there is none. */
        internal fun csrfCookieValue(cookieHeader: String?): String? {
            val cookies = AuthProvider.parseCookies(cookieHeader ?: "")

            return NAMES.firstNotNullOfOrNull { name -> cookies[name]?.takeIf { it.isNotEmpty() } }
        }
    }
}
