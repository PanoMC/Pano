package com.panomc.platform.route.api.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_CSRF
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_INVALID
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_IP_BANNED
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_NO_ACCESS
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_PARAM
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_PASSWORD_FIELD
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_PATH
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_USERNAME_FIELD
import com.panomc.platform.model.*
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.TextUtil
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import org.slf4j.Logger

/**
 * The staff door while the site is closed: a plain server-rendered form POST, answered with a 302
 * carrying an `?e=` code. No JSON, no JavaScript — the maintenance page is meant to be hand-editable
 * and the only way back into a locked-down site must not depend on a bundler.
 */
@Endpoint
class MaintenanceLoginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val maintenanceModeManager: MaintenanceModeManager,
    private val logger: Logger
) : Api() {
    override val paths = listOf(Path(LOGIN_PATH, RouteType.POST))

    // The one door that must stay open while everything else is closed.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun isAllowedInDemo(method: HttpMethod) = true

    // The body is a plain HTML form, not JSON: a schema failure would answer a browser navigation
    // with a JSON error. The two fields are read from form attributes and validated by hand.
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        if (!maintenanceModeManager.isEnabled()) {
            redirect(context, "/")

            return null
        }

        val request = context.request()
        val loginUrl = maintenanceModeManager.loginLocations().first()

        // Same-origin + double-submit nonce. Without it any web page could make its visitors POST
        // here until their IP is permanently banned, so a request that fails this is answered 403
        // and is deliberately NOT counted as a login attempt.
        if (!maintenanceModeManager.verifyLoginRequest(context)) {
            sendCsrfRejection(context)

            return null
        }

        if (maintenanceModeManager.isBanned(maintenanceModeManager.resolveBanIdentity(context))) {
            redirect(context, errorUrl(loginUrl, ERROR_IP_BANNED))

            return null
        }

        val usernameOrEmail = TextUtil.stripWhitespace(request.getFormAttribute(LOGIN_USERNAME_FIELD) ?: "")
        val password = request.getFormAttribute(LOGIN_PASSWORD_FIELD) ?: ""

        val sqlClient = getSqlClient()

        // ZERO plugin hooks, on purpose. LoginAPI runs AuthEventListener.onBeforeAuthenticate,
        // onBeforeLogin and onAfterLogin inline; this endpoint calls AuthProvider directly and must
        // keep doing so. Maintenance mode is the recovery path — a captcha, 2FA or social-login
        // plugin that is itself broken must never be able to lock staff out. Do not "helpfully"
        // wire the hooks in here.
        try {
            authProvider.validateInput(usernameOrEmail, password)

            authProvider.authenticate(
                usernameOrEmail = usernameOrEmail,
                password = password,
                dontCheckVerified = true,   // email delivery may be exactly what is broken
                dontCheckBanned = false,
                sqlClient = sqlClient
            )
        } catch (t: Throwable) {
            if (t !is Error) {
                // Infrastructure failure, not a bad password. Counting it would let a database
                // hiccup ban the administrator who is trying to fix it.
                logger.error("Maintenance login failed unexpectedly", t)

                redirect(context, errorUrl(loginUrl, ERROR_INVALID))

                return null
            }

            val nowBanned = maintenanceModeManager.registerLoginFailure(context, usernameOrEmail)

            // "No such user" and "wrong password" are never distinguished, and neither an
            // unverified email nor a platform ban is ever leaked.
            redirect(context, errorUrl(loginUrl, if (nowBanned) ERROR_IP_BANNED else ERROR_INVALID))

            return null
        }

        maintenanceModeManager.registerLoginSuccess(context)

        val token = authProvider.login(usernameOrEmail, context, sqlClient)
        val userId = authProvider.getUserIdFromToken(token)

        databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

        authProvider.setCookies(context, token, CSRFTokenGenerator.nextToken())

        maintenanceModeManager.invalidateAccessCache()

        redirect(context, resolveRedirectTarget(userId))

        return null
    }

    private suspend fun resolveRedirectTarget(userId: Long): String {
        val access = maintenanceModeManager.accessForUser(userId)

        return when {
            access.hasPanelAccess -> "/panel"
            access.canBypass -> "/"                     // maintenance page, now with the skip button
            // Correct credentials, no bypass right. Never counted as a failed attempt: otherwise a
            // low-privilege user could get their own office permanently banned.
            else -> errorUrl("/", ERROR_NO_ACCESS)
        }
    }

    /**
     * Re-renders the form with a fresh nonce instead of a bare 403 body, so an expired nonce (or a
     * browser that dropped the cookie) is not a dead end on a site with no other way in.
     */
    private suspend fun sendCsrfRejection(context: RoutingContext) {
        val body = maintenanceModeManager.renderLoginPage(
            context,
            MaintenanceModeManager.Notice.fromErrorCode(ERROR_CSRF)
        )

        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(403)
            .setStatusMessage("Forbidden")
            .putHeader("content-type", "text/html; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .putHeader("X-Robots-Tag", "noindex")
            .end(body)
    }

    private fun errorUrl(base: String, code: String) = "$base?$ERROR_PARAM=$code"

    private fun redirect(context: RoutingContext, location: String) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(302)
            .setStatusMessage("Found")
            .putHeader("Location", location)
            .putHeader("Cache-Control", "no-store")
            .end()
    }
}
