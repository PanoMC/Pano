package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.TicketTemplateDao
import com.panomc.platform.db.model.TicketTemplate
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TicketTemplateDaoImpl : TicketTemplateDao() {
    private val table get() = getTablePrefix() + tableName

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(table))
            .execute()
            .coAwait()
    }

    override suspend fun count(search: String?, sqlClient: SqlClient): Long {
        val rows = if (search.isNullOrBlank()) {
            sqlClient.preparedQuery("SELECT COUNT(`id`) FROM `$table`").execute().coAwait()
        } else {
            sqlClient
                .preparedQuery("SELECT COUNT(`id`) FROM `$table` WHERE $SEARCH")
                .execute(Tuple.of("%$search%", "%$search%"))
                .coAwait()
        }

        return rows.first().getLong(0)
    }

    override suspend fun getList(search: String?, limit: Int, offset: Long, sqlClient: SqlClient): List<TicketTemplate> {
        val rows = if (search.isNullOrBlank()) {
            sqlClient
                .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table` ORDER BY `title`, `id` LIMIT ? OFFSET ?")
                .execute(Tuple.of(limit, offset))
                .coAwait()
        } else {
            sqlClient
                .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table` WHERE $SEARCH ORDER BY `title`, `id` LIMIT ? OFFSET ?")
                .execute(Tuple.of("%$search%", "%$search%", limit, offset))
                .coAwait()
        }

        return rows.toEntities()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): TicketTemplate? {
        val rows = sqlClient
            .preparedQuery("SELECT $SELECT_COLUMNS FROM `$table` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.firstOrNull()?.toEntity()
    }

    override suspend fun add(template: TicketTemplate, sqlClient: SqlClient): Long {
        val rows = sqlClient
            .preparedQuery("INSERT INTO `$table` (`title`, `body`, `created_at`, `updated_at`) VALUES (?, ?, ?, ?)")
            .execute(Tuple.of(template.title, template.body, template.createdAt, template.updatedAt))
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(template: TicketTemplate, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("UPDATE `$table` SET `title` = ?, `body` = ?, `updated_at` = ? WHERE `id` = ?")
            .execute(Tuple.of(template.title, template.body, template.updatedAt, template.id))
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `$table` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    companion object {
        private const val SEARCH = "(`title` LIKE ? OR `body` LIKE ?)"

        private const val SELECT_COLUMNS =
            "`id`, `title`, `body`, `created_at` AS `createdAt`, `updated_at` AS `updatedAt`"

        /** Shared with `DatabaseMigration64to65`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `title` varchar(255) NOT NULL,
              `body` mediumtext NOT NULL,
              `created_at` bigint NOT NULL,
              `updated_at` bigint NOT NULL,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ready replies for tickets.';
        """.trimIndent()
    }
}
