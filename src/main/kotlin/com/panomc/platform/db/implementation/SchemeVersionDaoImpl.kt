package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SchemeVersionDao
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class SchemeVersionDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "scheme_version"),
    SchemeVersionDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
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

        val lastSchemeVersion = getLastSchemeVersion(sqlConnection)

        if (lastSchemeVersion == null) {
            add(
                sqlConnection,
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
                sqlConnection,
                SchemeVersion(
                    databaseManager.getLatestMigration().SCHEME_VERSION.toString(),
                    databaseManager.getLatestMigration().SCHEME_VERSION_INFO
                )
            )
        }
    }

    override suspend fun add(
        sqlConnection: SqlConnection,
        schemeVersion: SchemeVersion
    ) {
        sqlConnection
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
        sqlConnection: SqlConnection
    ): SchemeVersion? {
        val query = "SELECT `key` FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
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