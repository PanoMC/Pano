package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.PanelConfigDao
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection

@Dao
class PanelConfigDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "panel_config"), PanelConfigDao {
    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `user_id` bigint NOT NULL,
                              `option` varchar(255) NOT NULL,
                              `value` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel Config per player table.';
                        """
            )
            .execute()
            .await()
    }
}