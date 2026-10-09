package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.WebhookDeliveryDao
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
open class WebhookDeliveryDaoImpl : WebhookDeliveryDao() {

    /** The table prefix. Open so a test can run the queries against a throwaway database without a Spring context. */
    protected open fun prefix(): String = getTablePrefix()

    private fun table() = "`${prefix() + tableName}`"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(prefix() + tableName))
            .execute()
            .coAwait()
    }

    override suspend fun add(delivery: WebhookDelivery, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO ${table()} (`endpointId`, `source`, `ownerRef`, `subjectRef`, `eventId`, `event`, `url`, `format`, `signing`, `secret`, `body`, `status`, `attempts`, `maxAttempts`, `nextAttemptAt`, `claimedUntil`, `lastStatusCode`, `lastError`, `lastResponse`, `durationMs`, `deliveredAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(delivery.endpointId)
            .addValue(delivery.source)
            .addValue(delivery.ownerRef)
            .addValue(delivery.subjectRef)
            .addValue(delivery.eventId)
            .addValue(delivery.event)
            .addValue(delivery.url)
            .addValue(delivery.format.name)
            .addValue(delivery.signing.name)
            .addValue(delivery.secret)
            .addValue(delivery.body)
            .addValue(delivery.status.name)
            .addValue(delivery.attempts)
            .addValue(delivery.maxAttempts)
            .addValue(delivery.nextAttemptAt)
            .addValue(delivery.claimedUntil)
            .addValue(delivery.lastStatusCode)
            .addValue(delivery.lastError)
            .addValue(delivery.lastResponse)
            .addValue(delivery.durationMs)
            .addValue(delivery.deliveredAt)
            .addValue(delivery.createdAt)
            .addValue(delivery.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): WebhookDelivery? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM ${table()} WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByEventId(eventId: String, sqlClient: SqlClient): WebhookDelivery? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM ${table()} WHERE `eventId` = ?")
            .execute(Tuple.of(eventId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByEndpointId(endpointId: Long, limit: Int, sqlClient: SqlClient): List<WebhookDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM ${table()} WHERE `endpointId` = ? ORDER BY `id` DESC LIMIT ?")
            .execute(Tuple.of(endpointId, limit))
            .coAwait()
            .toEntities()

    private fun filter(source: String?, status: WebhookDeliveryStatus?, endpointId: Long?): Pair<String, Tuple> {
        val where = ArrayList<String>()
        val values = Tuple.tuple()

        if (source != null) {
            where += "`source` = ?"
            values.addValue(source)
        }
        if (status != null) {
            where += "`status` = ?"
            values.addValue(status.name)
        }
        if (endpointId != null) {
            where += "`endpointId` = ?"
            values.addValue(endpointId)
        }

        return (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) to values
    }

    override suspend fun getPage(
        source: String?, status: WebhookDeliveryStatus?, endpointId: Long?, limit: Int, offset: Int, sqlClient: SqlClient
    ): List<WebhookDelivery> {
        val (where, values) = filter(source, status, endpointId)

        return sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM ${table()}$where ORDER BY `id` DESC LIMIT ? OFFSET ?")
            .execute(values.addValue(limit).addValue(offset))
            .coAwait()
            .toEntities()
    }

    override suspend fun countFiltered(
        source: String?, status: WebhookDeliveryStatus?, endpointId: Long?, sqlClient: SqlClient
    ): Long {
        val (where, values) = filter(source, status, endpointId)

        return sqlClient
            .preparedQuery("SELECT COUNT(*) FROM ${table()}$where")
            .execute(values)
            .coAwait()
            .first()
            .getLong(0)
    }

    override suspend fun countBySubjectRef(source: String, subjectRef: String, sqlClient: SqlClient): Long =
        sqlClient
            .preparedQuery("SELECT COUNT(*) FROM ${table()} WHERE `source` = ? AND `subjectRef` = ?")
            .execute(Tuple.of(source, subjectRef))
            .coAwait()
            .first()
            .getLong(0)

    override suspend fun getDueIds(now: Long, limit: Int, activeSources: Collection<String>, sqlClient: SqlClient): List<Long> {
        val values = Tuple.tuple().addValue(now)
        val direct = if (activeSources.isEmpty()) {
            "`endpointId` <> 0"
        } else {
            activeSources.forEach { values.addValue(it) }
            "(`endpointId` <> 0 OR `source` IN (${activeSources.joinToString(",") { "?" }}))"
        }
        values.addValue(limit)

        return sqlClient
            .preparedQuery(
                "SELECT `id` FROM ${table()} WHERE `status` IN ('PENDING','FAILED') AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ? AND $direct ORDER BY `id` ASC LIMIT ?"
            )
            .execute(values)
            .coAwait()
            .map { it.getLong("id") }
    }

    override suspend fun claim(id: Long, now: Long, claimedUntil: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery(
                "UPDATE ${table()} SET `status` = 'SENDING', `claimedUntil` = ?, `attempts` = `attempts` + 1, `updatedAt` = ? WHERE `id` = ? AND `status` IN ('PENDING','FAILED')"
            )
            .execute(Tuple.of(claimedUntil, now, id))
            .coAwait()
            .rowCount() > 0

    override suspend fun getStaleClaimIds(now: Long, limit: Int, sqlClient: SqlClient): List<Long> =
        sqlClient
            .preparedQuery(
                "SELECT `id` FROM ${table()} WHERE `status` = 'SENDING' AND `claimedUntil` IS NOT NULL AND `claimedUntil` < ? ORDER BY `id` ASC LIMIT ?"
            )
            .execute(Tuple.of(now, limit))
            .coAwait()
            .map { it.getLong("id") }

    override suspend fun markResult(
        id: Long, from: WebhookDeliveryStatus, to: WebhookDeliveryStatus, attempts: Int, nextAttemptAt: Long?,
        statusCode: Int?, error: String?, response: String?, durationMs: Int?, deliveredAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean =
        sqlClient
            .preparedQuery(
                "UPDATE ${table()} SET `status` = ?, `attempts` = ?, `nextAttemptAt` = ?, `claimedUntil` = NULL, `lastStatusCode` = ?, `lastError` = ?, `lastResponse` = ?, `durationMs` = ?, `deliveredAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ?"
            )
            .execute(
                Tuple.tuple()
                    .addValue(to.name).addValue(attempts).addValue(nextAttemptAt).addValue(statusCode).addValue(error)
                    .addValue(response).addValue(durationMs).addValue(deliveredAt).addValue(now).addValue(id).addValue(from.name)
            )
            .coAwait()
            .rowCount() > 0

    override suspend fun deadenOpenRows(endpointId: Long, reason: String, now: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery(
                "UPDATE ${table()} SET `status` = 'DEAD', `lastError` = ?, `nextAttemptAt` = NULL, `claimedUntil` = NULL, `updatedAt` = ? WHERE `endpointId` = ? AND `status` IN ('PENDING','FAILED','SENDING')"
            )
            .execute(Tuple.of(reason, now, endpointId))
            .coAwait()
            .rowCount()

    override suspend fun requeue(id: Long, now: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery(
                "UPDATE ${table()} SET `status` = 'PENDING', `attempts` = 0, `nextAttemptAt` = ?, `claimedUntil` = NULL, `updatedAt` = ? WHERE `id` = ? AND `status` IN ('SUCCEEDED','FAILED','DEAD')"
            )
            .execute(Tuple.of(now, now, id))
            .coAwait()
            .rowCount() > 0

    override suspend fun purgeFinishedBefore(cutoff: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM ${table()} WHERE `status` IN ('SUCCEEDED','DEAD') AND `updatedAt` < ?")
            .execute(Tuple.of(cutoff))
            .coAwait()
            .rowCount()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS ${table()}")
            .execute()
            .coAwait()
    }

    private fun Throwable.isDuplicateKey(): Boolean {
        var current: Throwable? = this
        var depth = 0

        while (current != null && depth++ < 8) {
            if (current is MySQLException && current.errorCode == ER_DUP_ENTRY) return true

            current = current.cause
        }

        return false
    }

    companion object {
        private const val ER_DUP_ENTRY = 1062

        /** Shared with `DatabaseMigration57to58`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `endpointId` bigint NOT NULL DEFAULT 0,
              `source` varchar(64) NOT NULL,
              `ownerRef` varchar(128) NULL,
              `subjectRef` varchar(128) NULL,
              `eventId` varchar(64) NOT NULL,
              `event` varchar(128) NOT NULL,
              `url` varchar(1024) NOT NULL,
              `format` varchar(16) NOT NULL,
              `signing` varchar(16) NOT NULL,
              `secret` text NULL,
              `body` mediumtext NOT NULL,
              `status` varchar(16) NOT NULL DEFAULT 'PENDING',
              `attempts` int NOT NULL DEFAULT 0,
              `maxAttempts` int NOT NULL DEFAULT 8,
              `nextAttemptAt` bigint NULL,
              `claimedUntil` bigint NULL,
              `lastStatusCode` int NULL,
              `lastError` varchar(512) NULL,
              `lastResponse` varchar(2048) NULL,
              `durationMs` int NULL,
              `deliveredAt` bigint NULL,
              `createdAt` bigint NOT NULL,
              `updatedAt` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `uq_eventId` (`eventId`),
              KEY `idx_due` (`status`, `nextAttemptAt`),
              KEY `idx_endpoint` (`endpointId`, `id`),
              KEY `idx_source` (`source`, `id`),
              KEY `idx_subject` (`source`, `subjectRef`),
              KEY `idx_finished` (`status`, `updatedAt`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='The outbound webhook queue and log.';
        """.trimIndent()
    }
}
