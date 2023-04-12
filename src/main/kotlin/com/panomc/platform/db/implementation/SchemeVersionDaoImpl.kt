package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SchemeVersionDao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class SchemeVersionDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "scheme_version"),
    SchemeVersionDao {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `when` timestamp not null default CURRENT_TIMESTAMP,
                              `key` varchar(255) not null,
                              `extra` varchar(255),
                              PRIMARY KEY (`key`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Database scheme version table.';
                        """
            )
            .execute()
            .await()

        val lastSchemeVersion = getLastSchemeVersion(sqlClient)

        if (lastSchemeVersion == null) {
            add(
                sqlClient,
                SchemeVersion(
                    databaseManager.getLatestMigration().SCHEME_VERSION.toString(),
                    databaseManager.getLatestMigration().SCHEME_VERSION_INFO
                )
            )

            return
        }

        val databaseVersion = lastSchemeVersion.key.toIntOrNull() ?: 0

        if (databaseVersion == 0) {
            add(
                sqlClient,
                SchemeVersion(
                    databaseManager.getLatestMigration().SCHEME_VERSION.toString(),
                    databaseManager.getLatestMigration().SCHEME_VERSION_INFO
                )
            )
        }
    }

    override suspend fun add(
        sqlClient: SqlClient,
        schemeVersion: SchemeVersion
    ) {
        sqlClient
            .preparedQuery("INSERT INTO `${getTablePrefix() + tableName}` (`key`, `extra`) VALUES (?, ?)")
            .execute(
                Tuple.of(
                    schemeVersion.key,
                    schemeVersion.extra
                )
            )
            .await()
    }

    override suspend fun getLastSchemeVersion(
        sqlClient: SqlClient
    ): SchemeVersion? {
        val query = "SELECT `key` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList().maxBy { it.getString(0).toInt() }

        if (row.getString(0) == null) {
            return null
        }

        return SchemeVersion(row.getString(0))
    }
}