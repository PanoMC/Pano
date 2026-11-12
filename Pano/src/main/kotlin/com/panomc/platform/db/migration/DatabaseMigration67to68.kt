package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.webhook.template.WebhookTemplates
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * A Discord webhook gets one template per event: `webhook_endpoint.template` (one text for every event) becomes
 * `templates`, a JSON object from an event pattern to its template. The old text is kept under `*`, so it still
 * applies to every event of the endpoint.
 */
@Migration
class DatabaseMigration67to68 : DatabaseMigration(
    67,
    68,
    "Webhook templates per event"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addTemplatesColumn(),
        moveTemplates(),
        dropTemplateColumn()
    )

    private fun table() = "${getTablePrefix()}webhook_endpoint"

    private fun addTemplatesColumn(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        addMissingColumns(sqlClient, table(), "templates" to "MEDIUMTEXT NULL")
    }

    private fun moveTemplates(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        if (hasColumn(sqlClient, table(), "template")) {
            val rows = sqlClient
                .query("SELECT `id`, `template` FROM `${table()}` WHERE `template` IS NOT NULL AND `template` <> '' AND `templates` IS NULL")
                .execute()
                .coAwait()
                .map { it.getLong(0) to it.getString(1) }

            rows.forEach { (id, template) ->
                sqlClient
                    .preparedQuery("UPDATE `${table()}` SET `templates` = ? WHERE `id` = ?")
                    .execute(Tuple.of(WebhookTemplates.encode(mapOf("*" to template)), id))
                    .coAwait()
            }
        }
    }

    private fun dropTemplateColumn(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        dropColumnIfPresent(sqlClient, table(), "template")
    }
}
