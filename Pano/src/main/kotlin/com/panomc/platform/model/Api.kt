package com.panomc.platform.model

import com.panomc.platform.Main
import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InternalServerError
import com.panomc.platform.error.DisabledForDemo
import com.panomc.platform.error.MaintenanceModeEnabled
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.ext.mail.SMTPException
import io.vertx.ext.web.RoutingContext
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
            if (context.get<Boolean>(BEFORE_HANDLE_DONE) != true) {
                onBeforeHandle(context)
            }

            val result = handle(context)

            result?.let { sendResult(it, context) }
        }
    }

    override fun getFailureHandler() = Handler<RoutingContext> { context ->
        CoroutineScope(context.vertx().dispatcher()).launch {
            getFailureHandler(context)

            val failure = context.failure()

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

        response.end(result.encode(extras))
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
                checkBeforeBody(context)

                context.put(BEFORE_HANDLE_DONE, true)

                bodyHandler.handle(context)
            }
        }

    /** Extra checks (e.g. a permission) for [authorizedBodyHandler], run before the body is read. */
    open suspend fun checkBeforeBody(context: RoutingContext) = Unit

    fun getParameters(context: RoutingContext): RequestParameters = context.get(REQUEST_CONTEXT_KEY)

    abstract override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler?

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
        /** Set once [authorizedBodyHandler] has already run [onBeforeHandle] for this request. */
        const val BEFORE_HANDLE_DONE = "pano.api.beforeHandleDone"
    }
}