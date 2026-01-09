package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SchemeVersionDao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.annotation.Autowired

@Dao
class SchemeVersionDaoImpl : SchemeVersionDao() {
    @Autowired
    private lateinit var databaseManager: DatabaseManager

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `pluginId` varchar(255),
                              `when` timestamp not null default CURRENT_TIMESTAMP,
                              `key` varchar(255) not null,
                              `extra` varchar(255),
                              PRIMARY KEY (`key`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Database scheme version table.';
                        """
            )
            .execute()
            .coAwait()

        val lastSchemeVersion = getLastSchemeVersion(sqlClient)
        val latestMigration = databaseManager.getLatestMigration()

        if (lastSchemeVersion == null) {
            add(
                sqlClient,
                SchemeVersion(
                    key = (latestMigration?.to ?: 1).toString(),
                    extra = "Init"
                )
            )

            return
        }

        val databaseVersion = lastSchemeVersion.key.toIntOrNull() ?: 0

        if (databaseVersion == 0) {
            add(
                sqlClient,
                SchemeVersion(
                    key =latestMigration!!.to.toString(),
                    extra = latestMigration.info
                )
            )
        }
    }

    override suspend fun add(
        sqlClient: SqlClient,
        schemeVersion: SchemeVersion
    ) {
        sqlClient
            .preparedQuery("INSERT INTO `${getTablePrefix() + tableName}` (`pluginId`, `key`, `extra`) VALUES (?, ?, ?)")
            .execute(
                Tuple.of(
                    schemeVersion.pluginId,
                    schemeVersion.key,
                    schemeVersion.extra
                )
            )
            .coAwait()
    }

    override suspend fun getLastSchemeVersion(
        sqlClient: SqlClient
    ): SchemeVersion? {
        val query = "SELECT `pluginId`, `when`, `key`, `extra` FROM `${getTablePrefix() + tableName}` WHERE `pluginId` IS NULL"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.maxByOrNull { it.getString("key")?.toIntOrNull() ?: 0 }?.toEntity()
    }

    override suspend fun getLastSchemeVersion(
        pluginId: String,
        sqlClient: SqlClient,
    ): SchemeVersion? {
        val query = "SELECT `pluginId`, `when`, `key`, `extra` FROM `${getTablePrefix() + tableName}` WHERE `pluginId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(pluginId))
            .coAwait()

        return rows.maxByOrNull { it.getString("key")?.toIntOrNull() ?: 0 }?.toEntity()
    }

    override suspend fun deleteByPluginId(
        pluginId: String,
        sqlClient: SqlClient
    ) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `pluginId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(pluginId))
            .coAwait()
    }

    override suspend fun getAllPluginIds(sqlClient: SqlClient): List<String> {
        val query = "SELECT DISTINCT `pluginId` FROM `${getTablePrefix() + tableName}` WHERE `pluginId` IS NOT NULL"

        val rows: RowSet<Row> = sqlClient
            .query(query)
            .execute()
            .coAwait()

        return rows.map { it.getString("pluginId") }
    }
}