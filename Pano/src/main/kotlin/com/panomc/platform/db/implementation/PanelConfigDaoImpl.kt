package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PanelConfigDao
import com.panomc.platform.db.model.PanelConfig
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PanelConfigDaoImpl : PanelConfigDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `userId` bigint NOT NULL,
                              `option` varchar(255) NOT NULL,
                              `value` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel Config per player table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun byUserIdAndOption(userId: Long, option: String, sqlClient: SqlClient): PanelConfig? {
        val query =
            "SELECT `id`, `userId`, `option`, `value` FROM `${getTablePrefix() + tableName}` WHERE `userId` = ? AND `option` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId, option))
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun add(panelConfig: PanelConfig, sqlClient: SqlClient) {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` (`userId`, `option`, `value`) VALUES (?, ?, ?)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    panelConfig.userId,
                    panelConfig.option,
                    panelConfig.value
                )
            )
            .await()
    }

    override suspend fun updateValueById(id: Long, value: String, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `value` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    value,
                    id
                )
            )
            .await()
    }

    override suspend fun deleteByUserId(userId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `userId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            )
            .await()
    }
}