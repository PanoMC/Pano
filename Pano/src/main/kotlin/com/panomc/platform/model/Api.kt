package com.panomc.platform.model

import com.panomc.platform.Main
import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.CredentialSource
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InvalidCsrfToken
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InternalServerError
import com.panomc.platform.error.DisabledForDemo
import com.panomc.platform.error.MaintenanceModeEnabled
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.route.Mount
import com.panomc.platform.schema.Deprecation
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.Stability
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.DevMode
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.SMTPException
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.HttpException
import io.vertx.ext.web.validation.*
import io.vertx.ext.web.validation.ValidationHandler.REQUEST_CONTEXT_KEY
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.io.IOException

abstract class Api : Route() {
    override val mount = Mount.API

    private val logger by lazy {
        applicationContext.getBean(Logger::class.java)
    }

    private val databaseManager by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    private val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    private val maintenanceModeManager by lazy {
        applicationContext.getBean(MaintenanceModeManager::class.java)
    }

    /**
     * Whether this endpoint stays reachable while maintenance mode is on. Defaults to
     * [MaintenanceAccess.BYPASSER_ONLY], so a plugin endpoint registered at runtime is covered
     * without anyone maintaining a list.
     */
    open val maintenanceAccess: MaintenanceAccess = MaintenanceAccess.BYPASSER_ONLY

    /**
     * What the OpenAPI document says about this endpoint (doc 04 section 5). `null` lists it as undocumented.
     * In development mode a success body is checked against it and a mismatch is logged, never failed.
     */
    open val doc: EndpointDoc? = null

    /**
     * Opts this endpoint out of the CSRF check of [checkCsrf]. Only for plain HTML form posts that carry their
     * own proof (the maintenance forms); every other endpoint, core or plugin, is covered without doing anything.
     */
    open val csrfExempt: Boolean = false

    /** Who may rely on this endpoint; `null` derives it from where the route is mounted (doc 04 section 6). */
    open val stability: Stability? = null

    /** Set to announce that this endpoint goes away: OpenAPI flag, `Deprecation` and `Sunset` headers, a boot warning. */
    open val deprecation: Deprecation? = null

    suspend fun getSqlClient(): SqlClient {
        return databaseManager.getSqlClient()
    }

    fun checkSetup() {
        if (!setupManager.isSetupDone()) {
            throw InstallationRequired()
        }
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            putDeprecationHeaders(context)

            if (context.get<Boolean>(BEFORE_HANDLE_DONE) != true) {
                onBeforeHandle(context)
                checkCsrf(context)
            }

            val result = handle(context)

            result?.let { sendResult(it, context) }
        }
    }

    override fun getFailureHandler() = Handler<RoutingContext> { context ->
        CoroutineScope(context.vertx().dispatcher()).launch {
            getFailureHandler(context)

            val failure = context.failure()

            // A handler that called context.fail(statusCode) (e.g. the body handler on an over-limit
            // body) leaves no failure object; answer that status instead of a 500.
            val statusOnly = statusOnlyResult(context.statusCode(), failure)

            if (statusOnly != null) {
                sendResult(statusOnly, context)

                return@launch
            }

            if (
                failure is BadRequestException ||
                failure is ParameterProcessorException ||
                failure is BodyProcessorException ||
                failure is RequestPredicateException
            ) {
                sendResult(BadRequest(), context, mapOf("bodyValidationError" to failure.message))

                return@launch
            }

            if (failure is IOException) {
                sendResult(BadRequest(), context, mapOf("inputError" to failure.message))

                return@launch
            }

            if (failure is SMTPException) {
                sendResult(InternalServerError(), context)

                return@launch
            }

            if (failure !is Result) {
                logger.error("Error on endpoint URL: {} {}", context.request().method(), context.request().path())
                sendResult(InternalServerError(), context)

                throw failure
            }

            sendResult(failure, context)
        }
    }

    private fun statusOnlyResult(statusCode: Int, failure: Throwable?): Result? {
        val status = when {
            failure == null -> statusCode
            failure is HttpException -> failure.statusCode
            else -> return null
        }

        return when {
            status == 413 -> PayloadTooLarge()
            status in 400..499 -> BadRequest()
            status >= 500 -> InternalServerError()
            else -> null
        }
    }

    private fun sendResult(
        result: Result,
        context: RoutingContext,
        extras: Map<String, Any?> = mapOf()
    ) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .putHeader("content-type", "application/json; charset=utf-8")

        response.statusCode = result.getStatusCode()
        response.statusMessage = result.getStatusMessage()

        val encoded = result.encode(extras)

        checkAgainstDoc(result, encoded, context)

        response.end(encoded)
    }

    private fun putDeprecationHeaders(context: RoutingContext) {
        val deprecation = deprecation ?: return
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response.putHeader("Deprecation", "true")
        response.putHeader("Sunset", deprecation.sunset)
    }

    /**
     * Development mode only: compares a success body with [doc] and logs the class and the failing JSON pointers.
     * It never changes the answer and never throws.
     */
    private fun checkAgainstDoc(result: Result, encoded: String, context: RoutingContext) {
        val doc = doc ?: return

        if (doc.binary || result is Error || result.getStatusCode() !in 200..299) {
            return
        }

        try {
            if (!DevMode.isActive()) {
                return
            }

            val failures = doc.check(JsonObject(encoded))

            if (failures.isNotEmpty()) {
                logger.warn(
                    "Response of {} ({} {}) does not match its declared schema: {}",
                    javaClass.name,
                    context.request().method(),
                    context.request().path(),
                    failures.joinToString("; ")
                )
            }
        } catch (e: Exception) {
            // A broken check must not break the endpoint.
        }
    }

    private fun getExceptionHandler(context: RoutingContext) = CoroutineExceptionHandler { _, exception ->
        context.fail(exception)
    }

    /**
     * Wraps [bodyHandler] so [onBeforeHandle] (setup state, login, panel access, maintenance) and
     * [checkBeforeBody] run before a single byte of the body is read or spooled to disk. Meant for
     * routes that accept large uploads: without it the router's body handler would store the whole
     * upload before anyone is asked whether the caller may send it. The request is paused while the
     * checks run; a failed check fails the route without reading the body.
     */
    protected fun authorizedBodyHandler(bodyHandler: Handler<RoutingContext>): Handler<RoutingContext> =
        Handler { context ->
            context.request().pause()

            CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
                onBeforeHandle(context)
                checkCsrf(context)
                checkBeforeBody(context)

                context.put(BEFORE_HANDLE_DONE, true)

                bodyHandler.handle(context)
            }
        }

    /**
     * CSRF on every mutation (doc 05 §4). It runs right after [onBeforeHandle] returns, in [getHandler] and in
     * [authorizedBodyHandler], so no override can skip it. It throws [InvalidCsrfToken] when the method is unsafe,
     * the endpoint is not [csrfExempt], the session credential is a **cookie**, the request lives in a valid session
     * and the `X-CSRF-Token` header does not repeat the CSRF cookie.
     *
     * "Only for a valid session" keeps a browser with a revoked or expired cookie able to log in again; a request
     * without a live session is covered by the Origin gate of the access plane. A Bearer token, with or without a
     * front-end key, is no ambient credential and is never checked.
     */
    private suspend fun checkCsrf(context: RoutingContext) {
        if (csrfExempt || context.request().method() in CSRF_SAFE_METHODS) {
            return
        }

        // A cookie credential needs a Cookie header; most API clients and servers have none and cost nothing here.
        if (context.request().getHeader("cookie") == null) {
            return
        }

        val probe = csrfProbe()

        if (probe.credentialSource(context) != CredentialSource.COOKIE) {
            return
        }

        if (probe.isCsrfSafe(context) || !probe.isLoggedIn(context)) {
            return
        }

        throw InvalidCsrfToken(extras = mapOf("message" to CSRF_MESSAGE))
    }

    /** What [checkCsrf] asks. The real one is [AuthProvider]; a test stands in for the host. */
    protected open fun csrfProbe(): CsrfProbe = defaultCsrfProbe

    private val defaultCsrfProbe by lazy {
        AuthCsrfProbe(applicationContext.getBean(AuthProvider::class.java))
    }

    /** Extra checks (e.g. a permission) for [authorizedBodyHandler], run before the body is read. */
    open suspend fun checkBeforeBody(context: RoutingContext) = Unit

    fun getParameters(context: RoutingContext): RequestParameters = context.get(REQUEST_CONTEXT_KEY)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    abstract suspend fun handle(context: RoutingContext): Result?

    open suspend fun getFailureHandler(context: RoutingContext) = Unit

    open suspend fun onBeforeHandle(context: RoutingContext) {
        checkSetup()

        checkDemoMode(context)

        checkMaintenance(context)
    }

    protected fun checkDemoMode(context: RoutingContext) {
        if (Main.IS_DEMO && !isAllowedInDemo(context.request().method())) {
            throw DisabledForDemo()
        }
    }

    protected suspend fun checkMaintenance(context: RoutingContext) {
        if (maintenanceAccess == MaintenanceAccess.ALWAYS) {
            return
        }

        if (!maintenanceModeManager.isEnabled()) {
            return
        }

        // Keys on the permission only, never on the skip cookie: the cookie decides which UI is
        // served, the permission decides which APIs answer.
        if (maintenanceModeManager.resolveAccess(context).canBypass) {
            return
        }

        throw MaintenanceModeEnabled()
    }

    open fun isAllowedInDemo(method: HttpMethod): Boolean {
        return method == HttpMethod.GET || method == HttpMethod.OPTIONS
    }

    companion object {
        private val CSRF_SAFE_METHODS = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)

        /** The text of the `INVALID_CSRF_TOKEN` answer: it names the fix. */
        const val CSRF_MESSAGE =
            "This request changes data and came with a session cookie but without a matching X-CSRF-Token header. " +
                "Send the header (ApiUtil does; or GET /auth/csrf to read the token)."

        /** Set once [authorizedBodyHandler] has already run [onBeforeHandle] for this request. */
        const val BEFORE_HANDLE_DONE = "pano.api.beforeHandleDone"
    }
}

/** The three facts [Api.checkCsrf] needs about a request. */
interface CsrfProbe {
    /** Where the session credential comes from (sync, no database). */
    fun credentialSource(context: RoutingContext): CredentialSource?

    /** Whether the `X-CSRF-Token` header repeats the CSRF cookie (or the method is safe, or a Bearer is the credential). */
    fun isCsrfSafe(context: RoutingContext): Boolean

    /** Whether the request carries a live session. */
    suspend fun isLoggedIn(context: RoutingContext): Boolean
}

private class AuthCsrfProbe(private val authProvider: AuthProvider) : CsrfProbe {
    override fun credentialSource(context: RoutingContext) = authProvider.credentialSource(context)

    override fun isCsrfSafe(context: RoutingContext) = authProvider.isCsrfSafe(context)

    override suspend fun isLoggedIn(context: RoutingContext) = authProvider.isLoggedIn(context)
}

/** The request body is bigger than the route's body limit (HTTP 413, error code PAYLOAD_TOO_LARGE). */
class PayloadTooLarge(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PAYLOAD_TOO_LARGE", 413, statusMessage, extras)
