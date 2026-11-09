package com.panomc.platform.route.api.maintenance

import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_CSRF
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_INVALID
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_IP_BANNED
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_NO_ACCESS
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.ERROR_PARAM
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_CODE_FIELD
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_PASSWORD_FIELD
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_PATH
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.LOGIN_USERNAME_FIELD
import com.panomc.platform.model.*
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.TextUtil
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.impl.RoutingContextInternal
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.withTimeoutOrNull
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

    // A plain HTML form post, not a fetch: it cannot send X-CSRF-Token (doc 05 §4).
    override val csrfExempt = true

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

        // The site's own IP bans apply here as on the normal login: this door is for staff who are
        // locked out by maintenance, not for an address the site has banned. Not counted as a
        // failed attempt, like the maintenance ban above.
        if (databaseManager.bannedIpDao.getActiveByIp(authProvider.getRemoteIP(context), sqlClient) != null) {
            redirect(context, errorUrl(loginUrl, ERROR_IP_BANNED))

            return null
        }

        // Almost no plugin hooks, on purpose. LoginAPI runs AuthEventListener.onBeforeAuthenticate,
        // onBeforeLogin and onAfterLogin inline; this endpoint calls AuthProvider directly.
        // Maintenance mode is the recovery path — a captcha or social-login plugin that is itself
        // broken must never be able to lock staff out. The one exception is a second factor (see
        // [checkSecondFactor]): a password alone must not open an account that has one, but a 2FA
        // plugin that throws or hangs still fails open.
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

        when (checkSecondFactor(context, usernameOrEmail, request.getFormAttribute(LOGIN_CODE_FIELD), sqlClient)) {
            SecondFactor.PASSED -> Unit

            // Not a failed attempt: the password was right, the code is simply still to come.
            SecondFactor.REQUIRED -> {
                sendLoginPage(context, 401, MaintenanceModeManager.Notice.CODE_REQUIRED)

                return null
            }

            SecondFactor.INVALID -> {
                // Counted like a wrong password, so codes cannot be guessed without limit.
                if (maintenanceModeManager.registerLoginFailure(context, usernameOrEmail)) {
                    redirect(context, errorUrl(loginUrl, ERROR_IP_BANNED))
                } else {
                    sendLoginPage(context, 401, MaintenanceModeManager.Notice.CODE_INVALID)
                }

                return null
            }
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

    /**
     * Whether the account's second factor, when it has one, lets this sign-in through.
     *
     * Asks the [AuthEventListener.onBeforeLogin] hooks the normal login asks, with the JSON body
     * they expect (this form posts form data): first without a code, and a hook that answers with a
     * `challengeField` is a second factor; then only those hooks again with [code] in that field.
     * Every other answer of any hook is ignored here, and a hook that throws or does not answer in
     * time counts as no answer, so a broken plugin can never lock staff out of a closed site.
     */
    private suspend fun checkSecondFactor(
        context: RoutingContext,
        usernameOrEmail: String,
        code: String?,
        sqlClient: SqlClient
    ): SecondFactor {
        val listeners = PluginEventManager.getPanoEventListeners<AuthEventListener>()

        if (listeners.isEmpty()) {
            return SecondFactor.PASSED
        }

        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)
            ?: return SecondFactor.PASSED
        val user = databaseManager.userDao.getById(userId, sqlClient) ?: return SecondFactor.PASSED

        val internal = context as? RoutingContextInternal ?: run {
            logger.warn("Maintenance login could not ask the sign-in plugins for a second factor.")

            return SecondFactor.PASSED
        }

        val originalBody = context.body().buffer()

        try {
            return secondFactor(listeners, usernameOrEmail, code) { listener, body ->
                internal.setBody(body.toBuffer())

                askHook(listener, user, context, sqlClient)
            }
        } finally {
            internal.setBody(originalBody)
        }
    }

    private suspend fun askHook(
        listener: AuthEventListener,
        user: User,
        context: RoutingContext,
        sqlClient: SqlClient
    ): AuthEventListener.LoginDecision? = try {
        withTimeoutOrNull(HOOK_TIMEOUT_MS) { listener.onBeforeLogin(user, context, sqlClient) }
    } catch (t: Throwable) {
        logger.warn("A sign-in plugin failed during the maintenance login; ignoring it: ${t.message}")

        null
    }

    private suspend fun sendLoginPage(context: RoutingContext, status: Int, notice: MaintenanceModeManager.Notice) {
        // Pano's own form: the one in the maintenance folder may predate the code field.
        val body = maintenanceModeManager.renderLoginPage(context, notice, bundledForm = true)

        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(status)
            .putHeader("content-type", "text/html; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .putHeader("X-Robots-Tag", "noindex")
            .end(body)
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

    enum class SecondFactor { PASSED, REQUIRED, INVALID }

    companion object {
        /** The extra a second-factor plugin names its code field in (auth-guard: `totpCode`). */
        const val CHALLENGE_FIELD_KEY = "challengeField"

        /**
         * The second-factor decision, given how to [ask] each listener with a request body.
         *
         * A listener that denies with a [CHALLENGE_FIELD_KEY] is a second factor; only those are
         * asked again, with [code] in their field, and only their denial then refuses the sign-in.
         * Pure apart from [ask], so the rule can be asserted without a server.
         */
        suspend fun <T> secondFactor(
            listeners: List<T>,
            usernameOrEmail: String,
            code: String?,
            ask: suspend (T, JsonObject) -> AuthEventListener.LoginDecision?
        ): SecondFactor {
            val challenges = listeners.mapNotNull { listener ->
                val decision = ask(listener, JsonObject().put(LOGIN_USERNAME_FIELD, usernameOrEmail))
                    as? AuthEventListener.LoginDecision.Deny

                (decision?.extras?.get(CHALLENGE_FIELD_KEY) as? String)?.takeIf { it.isNotBlank() }?.let { listener to it }
            }

            if (challenges.isEmpty()) {
                return SecondFactor.PASSED
            }

            val cleanedCode = code?.trim().orEmpty()

            if (cleanedCode.isEmpty()) {
                return SecondFactor.REQUIRED
            }

            challenges.forEach { (listener, field) ->
                val body = JsonObject().put(LOGIN_USERNAME_FIELD, usernameOrEmail).put(field, cleanedCode)

                if (ask(listener, body) is AuthEventListener.LoginDecision.Deny) {
                    return SecondFactor.INVALID
                }
            }

            return SecondFactor.PASSED
        }

        private const val HOOK_TIMEOUT_MS = 5_000L
    }
}
