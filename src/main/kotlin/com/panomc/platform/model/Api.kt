package com.panomc.platform.model

import com.panomc.platform.db.DatabaseManager
import io.vertx.core.Handler
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
import org.springframework.beans.factory.annotation.Autowired

abstract class Api : Route() {
    @Autowired
    private lateinit var databaseManager: DatabaseManager

    suspend fun createConnection(routingContext: RoutingContext): SqlConnection {
        val existingSqlConnection = routingContext.get<SqlConnection>("sqlConnection")

        if (existingSqlConnection != null) {
            return existingSqlConnection
        }

        val sqlConnection = databaseManager.createConnection()


        routingContext.put("sqlConnection", sqlConnection)

        return sqlConnection
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        val getSqlConnectionMethod: suspend () -> SqlConnection = {
            createConnection(context)
        }

        context.put("getSqlConnection", getSqlConnectionMethod)

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            onBeforeHandle(context)

            val result = handle(context)

            val sqlConnection = context.get<SqlConnection>("sqlConnection")

            sqlConnection?.close()?.await()

            result?.let { sendResult(it, context) }
        }
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

    private fun sendResult(result: Result, context: RoutingContext) {
        val response = context.response()

        response
            .putHeader("content-type", "application/json; charset=utf-8")

        response.end(result.encode())
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