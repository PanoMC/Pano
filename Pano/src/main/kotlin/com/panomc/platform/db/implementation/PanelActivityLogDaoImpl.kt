package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PanelActivityLogDao
import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanelActivityLogDaoImpl : PanelActivityLogDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `userId` bigint,
                              `type` varchar(255) NOT NULL,
                              `details` mediumtext NOT NULL,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel activity log table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        panelActivityLog: PanelActivityLog,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`userId`, `type`, `details`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    panelActivityLog.userId,
                    panelActivityLog.type,
                    panelActivityLog.details.toString(),
                    panelActivityLog.createdAt,
                    panelActivityLog.updatedAt,
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }
}