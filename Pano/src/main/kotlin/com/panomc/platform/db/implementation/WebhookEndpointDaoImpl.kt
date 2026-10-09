package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.db.model.WebhookEndpoint
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
open class WebhookEndpointDaoImpl : WebhookEndpointDao() {

    /** The table prefix. Open so a test can run the queries against a throwaway database without a Spring context. */
    protected open fun prefix(): String = getTablePrefix()

    private fun table() = "`${prefix() + tableName}`"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(prefix() + tableName))
            .execute()
            .coAwait()
    }

    override suspend fun add(endpoint: WebhookEndpoint, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO ${table()} (`name`, `url`, `events`, `format`, `signing`, `secret`, `headers`, `template`, `enabled`, `maxAttempts`, `failureCount`, `lastStatusCode`, `lastDeliveryAt`, `disabledReason`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(endpoint.name)
            .addValue(endpoint.url)
            .addValue(endpoint.events)
            .addValue(endpoint.format.name)
            .addValue(endpoint.signing.name)
            .addValue(endpoint.secret)
            .addValue(endpoint.headers)
            .addValue(endpoint.template)
            .addValue(if (endpoint.enabled) 1 else 0)
            .addValue(endpoint.maxAttempts)
            .addValue(endpoint.failureCount)
            .addValue(endpoint.lastStatusCode)
            .addValue(endpoint.lastDeliveryAt)
            .addValue(endpoint.disabledReason)
            .addValue(endpoint.createdAt)
            .addValue(endpoint.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): WebhookEndpoint? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM ${table()} WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getAll(sqlClient: SqlClient): List<WebhookEndpoint> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM ${table()} ORDER BY `id` ASC")
            .execute()
            .coAwait()
            .toEntities()

    override suspend fun count(sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("SELECT COUNT(*) FROM ${table()}")
            .execute()
            .coAwait()
            .first()
            .getInteger(0)

    override suspend fun update(endpoint: WebhookEndpoint, now: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE ${table()} SET `name` = ?, `url` = ?, `events` = ?, `format` = ?, `signing` = ?, `secret` = ?, `headers` = ?, `template` = ?, `enabled` = ?, `maxAttempts` = ?, `disabledReason` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(
                Tuple.tuple()
                    .addValue(endpoint.name)
                    .addValue(endpoint.url)
                    .addValue(endpoint.events)
                    .addValue(endpoint.format.name)
                    .addValue(endpoint.signing.name)
                    .addValue(endpoint.secret)
                    .addValue(endpoint.headers)
                    .addValue(endpoint.template)
                    .addValue(if (endpoint.enabled) 1 else 0)
                    .addValue(endpoint.maxAttempts)
                    .addValue(endpoint.disabledReason)
                    .addValue(now)
                    .addValue(endpoint.id)
            )
            .coAwait()
            .rowCount() > 0

    override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM ${table()} WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .rowCount() > 0

    override suspend fun recordOutcome(
        id: Long, success: Boolean, statusCode: Int?, now: Long, disableAfter: Int, sqlClient: SqlClient
    ): Boolean {
        // MariaDB evaluates the SET list left to right: `enabled` and `disabledReason` read the old failureCount, which is assigned last.
        val query = if (success) {
            "UPDATE ${table()} SET `failureCount` = 0, `lastStatusCode` = ?, `lastDeliveryAt` = ?, `updatedAt` = ? WHERE `id` = ?"
        } else {
            "UPDATE ${table()} SET " +
                    "`enabled` = IF(`failureCount` + 1 >= ?, 0, `enabled`), " +
                    "`disabledReason` = IF(`failureCount` + 1 >= ?, '${WebhookEndpointDao.AUTO_DISABLED_REASON}', `disabledReason`), " +
                    "`lastStatusCode` = ?, `lastDeliveryAt` = ?, `updatedAt` = ?, `failureCount` = `failureCount` + 1 WHERE `id` = ?"
        }
        val values = if (success) Tuple.of(statusCode, now, now, id) else Tuple.of(disableAfter, disableAfter, statusCode, now, now, id)

        return sqlClient.preparedQuery(query).execute(values).coAwait().rowCount() > 0
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS ${table()}")
            .execute()
            .coAwait()
    }

    companion object {
        /** Shared with `DatabaseMigration57to58`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `name` varchar(128) NOT NULL,
              `url` varchar(1024) NOT NULL,
              `events` text NOT NULL,
              `format` varchar(16) NOT NULL DEFAULT 'JSON',
              `signing` varchar(16) NOT NULL DEFAULT 'NONE',
              `secret` text NULL,
              `headers` text NULL,
              `template` text NULL,
              `enabled` tinyint(1) NOT NULL DEFAULT 1,
              `maxAttempts` int NOT NULL DEFAULT 8,
              `failureCount` int NOT NULL DEFAULT 0,
              `lastStatusCode` int NULL,
              `lastDeliveryAt` bigint NULL,
              `disabledReason` varchar(64) NULL,
              `createdAt` bigint NOT NULL,
              `updatedAt` bigint NOT NULL,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Webhooks the site owner configured.';
        """.trimIndent()
    }
}
