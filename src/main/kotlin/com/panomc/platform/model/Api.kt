package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.mail.SMTPException
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.*
import io.vertx.ext.web.validation.ValidationHandler.REQUEST_CONTEXT_KEY
import io.vertx.json.schema.SchemaParser
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import java.io.IOException

abstract class Api : Route() {
    @Autowired
    private lateinit var logger: Logger

    @Autowired
    private lateinit var databaseManager: DatabaseManager

    @Autowired
    private lateinit var setupManager: SetupManager

    fun getSqlClient(): SqlClient {
        return databaseManager.getSqlClient()
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        if (setupManager.isSetupDone()) {
            context.put("sqlClient", databaseManager.getSqlClient())
        }

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            onBeforeHandle(context)

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
                sendResult(Error(ErrorCode.BAD_REQUEST), context, mapOf("bodyValidationError" to failure.message))

                return@launch
            }

            if (failure is IOException) {
                sendResult(Error(ErrorCode.BAD_REQUEST), context, mapOf("inputError" to failure.message))

                return@launch
            }

            if (failure is SMTPException) {
                sendResult(Error(ErrorCode.INTERNAL_SERVER_ERROR), context)

                return@launch
            }

            if (failure !is Result) {
                logger.error("Error on endpoint URL: {} {}", context.request().method(), context.request().path())
                sendResult(Error(ErrorCode.INTERNAL_SERVER_ERROR), context)

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

        if (response.ended()) {
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

    fun getParameters(context: RoutingContext): RequestParameters = context.get(REQUEST_CONTEXT_KEY)

    abstract override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler?

    abstract suspend fun handle(context: RoutingContext): Result?

    open suspend fun getFailureHandler(context: RoutingContext) = Unit

    open suspend fun onBeforeHandle(context: RoutingContext) = Unit
}