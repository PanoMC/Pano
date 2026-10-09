package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.WebhookDeliveryDaoImpl
import com.panomc.platform.db.implementation.WebhookEndpointDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `webhook_endpoint` and `webhook_delivery` (open front-end plan, doc 06 §4): the core webhooks, with
 * the delivery queue and log for every event source (`core` and plugins).
 */
@Migration
class DatabaseMigration57to58 : DatabaseMigration(
    57,
    58,
    "Add the webhook_endpoint and webhook_delivery tables"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .preparedQuery(WebhookEndpointDaoImpl.createTableQuery("${getTablePrefix()}webhook_endpoint"))
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            sqlClient
                .preparedQuery(WebhookDeliveryDaoImpl.createTableQuery("${getTablePrefix()}webhook_delivery"))
                .execute()
                .coAwait()
        }
    )
}
