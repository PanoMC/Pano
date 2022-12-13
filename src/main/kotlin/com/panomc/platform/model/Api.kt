package com.panomc.platform.model

import com.panomc.platform.db.DatabaseManager
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.ValidationHandler.REQUEST_CONTEXT_KEY
import io.vertx.json.schema.SchemaParser
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class Api : Route() {

    suspend fun createConnection(databaseManager: DatabaseManager, routingContext: RoutingContext): SqlConnection {
        val sqlConnection = databaseManager.createConnection()

        routingContext.put("sqlConnection", sqlConnection)

        return sqlConnection
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        callHandler(context)
    }

    override fun getFailureHandler() = Handler<RoutingContext> { context ->
        CoroutineScope(context.vertx().dispatcher()).launch {
            getFailureHandler(context)

            val failure = context.failure()
            val sqlConnection = context.get<SqlConnection>("sqlConnection")

            sqlConnection?.close()?.await()

            if (!(failure is Result && (failure is Error || failure is Errors))) {
                println("Error on endpoint URL: " + context.request().path())
                throw failure
            }

            sendResult(failure, context)
        }
    }

    fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            val result = handler(context)

            val sqlConnection = context.get<SqlConnection>("sqlConnection")

            sqlConnection?.close()?.await()

            result?.let(handler)
        }
    }

    fun callHandler(context: RoutingContext) {
        handler(context) { sendResult(it, context) }
    }

    fun sendResult(result: Result, context: RoutingContext) {
        val response = context.response()

        response
            .putHeader("content-type", "application/json; charset=utf-8")

        val responseMap = mutableMapOf<String, Any?>(
            "result" to "ok"
        )

        if (result is Successful) {
            responseMap.putAll(result.map)
        }

        if (result is Error) {
            responseMap.putAll(
                mapOf(
                    "result" to "error",
                    "error" to result.errorCode
                )
            )
        }

        if (result is Errors) {
            responseMap.putAll(
                mapOf(
                    "result" to "error",
                    "error" to result.errors
                )
            )
        }

        response.end(JsonObject(responseMap).encode())
    }

    fun getParameters(context: RoutingContext): RequestParameters = context.get(REQUEST_CONTEXT_KEY)

    fun getExceptionHandler(context: RoutingContext) = CoroutineExceptionHandler { _, exception ->
        context.fail(exception)
    }

    abstract override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler?

    abstract suspend fun handler(context: RoutingContext): Result?

    open suspend fun getFailureHandler(context: RoutingContext) = Unit
}