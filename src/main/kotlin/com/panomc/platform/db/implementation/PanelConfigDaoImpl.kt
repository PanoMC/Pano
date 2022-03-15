package com.panomc.platform.db.implementation

import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.dao.PanelConfigDao
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

class PanelConfigDaoImpl(override val tableName: String = "panel_config") : DaoImpl(), PanelConfigDao {
    override fun init(): (sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit =
        { sqlConnection, handler ->
            sqlConnection
                .query(
                    """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` int NOT NULL AUTO_INCREMENT,
                              `user_id` int(11) NOT NULL,
                              `option` varchar(255) NOT NULL,
                              `value` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Panel Config per player table.';
                        """
                )
                .execute {
                    handler.invoke(it)
                }
        }
}