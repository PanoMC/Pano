package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.TranslationDao
import com.panomc.platform.db.model.Translation
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TranslationDaoImpl : TranslationDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `localeId` varchar(10) NOT NULL,
                          `type` varchar(255) NOT NULL,
                          `key` text NOT NULL,
                          `value` text NOT NULL,
                          `createdAt` BIGINT(20) NOT NULL,
                          `updatedAt` BIGINT(20) NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Locales table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(translation: Translation, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`localeId`, `type`, `key`, `value`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?);" // 6

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    translation.localeId,
                    translation.type,
                    translation.key,
                    translation.value,
                    translation.createdAt,
                    translation.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun addAll(translations: List<Translation>, sqlClient: SqlClient) {
        if (translations.isEmpty()) {
            return
        }

        val batchTuple = translations.map { translation ->
            Tuple.of(
                translation.localeId,
                translation.type,
                translation.key,
                translation.value,
                translation.createdAt,
                translation.updatedAt
            )
        }

        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`localeId`, `type`, `key`, `value`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"

        sqlClient
            .preparedQuery(query)
            .executeBatch(batchTuple)
            .coAwait()
    }

    override suspend fun removeAll(translations: List<Translation>, sqlClient: SqlClient) {
        if (translations.isEmpty()) {
            return
        }

        val batchTuple = translations.map { translation ->
            Tuple.of(
                translation.id
            )
        }

        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .executeBatch(batchTuple)
            .coAwait()
    }

    override suspend fun updateAll(translations: List<Translation>, sqlClient: SqlClient) {
        if (translations.isEmpty()) {
            return
        }

        val batchTuple = translations.map { translation ->
            Tuple.of(
                translation.value,
                translation.id
            )
        }

        val query = "UPDATE `${getTablePrefix() + tableName}` SET `value` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .executeBatch(batchTuple)
            .coAwait()
    }

    override suspend fun countByLocaleIdAndType(
        localeId: Long,
        type: Translation.Companion.TranslationType,
        sqlClient: SqlClient
    ): Long {
        val query = "SELECT COUNT(*) FROM `${getTablePrefix() + tableName}` where `localeId` = ? AND `type` = ?;"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(localeId, type.name))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getByLocaleIdAndType(
        localeId: Long,
        type: Translation.Companion.TranslationType,
        sqlClient: SqlClient
    ): List<Translation> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `localeId` = ? AND `type` = ? ORDER BY `createdAt` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(localeId, type.name))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun deleteByLocaleId(
        localeId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `localeId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    localeId
                )
            ).coAwait()
    }
}