package com.panomc.platform.model

import com.panomc.platform.db.DatabaseManager
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class Api : Route() {
    fun sendResult(result: Result, context: RoutingContext) {
        val response = context.response()

        response
            .putHeader("content-type", "application/json; charset=utf-8")

        when (result) {
            is Successful -> {
                val responseMap = mutableMapOf<String, Any?>(
                    "result" to "ok"
                )

                responseMap.putAll(result.map)

                response.end(
                    JsonObject(
                        responseMap
                    ).encode()
                )
            }
            is Error -> response.end(
                JsonObject(
                    mapOf(
                        "result" to "error",
                        "error" to result.errorCode
                    )
                ).encode()
            )
            is Errors -> response.end(
                JsonObject(
                    mapOf(
                        "result" to "error",
                        "error" to result.errors
                    )
                ).encode()
            )
        }
    }

    suspend fun createConnection(databaseManager: DatabaseManager, routingContext: RoutingContext): SqlConnection {
        val sqlConnection = databaseManager.createConnection()

        routingContext.put("sqlConnection", sqlConnection)

        return sqlConnection
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        handler(context) {
            sendResult(it, context)
        }
    }

    override fun getFailureHandler() = Handler<RoutingContext> { context ->
        CoroutineScope(context.vertx().dispatcher()).launch {
            getFailureHandler(context)

            val failure = context.failure()
            val sqlConnection = context.get<SqlConnection>("sqlConnection")

            sqlConnection?.close()?.await()

            if (!(failure is Result && (failure is Error || failure is Errors))) {
                throw failure
            }

            sendResult(failure, context)
        }
    }

    fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        CoroutineScope(context.vertx().dispatcher()).launch {
            val result = handler(context)

            val sqlConnection = context.get<SqlConnection>("sqlConnection")

            sqlConnection?.close()?.await()

            result?.let(handler)
        }
    }

    abstract suspend fun handler(context: RoutingContext): Result?

    open suspend fun getFailureHandler(context: RoutingContext) = Unit
}