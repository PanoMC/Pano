package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelConfig
import io.vertx.sqlclient.SqlConnection

interface PanelConfigDao : Dao<PanelConfig> {
    suspend fun byUserIdAndOption(
        userId: Long,
        option: String,
        sqlConnection: SqlConnection
    ): PanelConfig?

    suspend fun add(
        panelConfig: PanelConfig,
        sqlConnection: SqlConnection
    )

    suspend fun updateValueById(
        id: Long,
        value: String,
        sqlConnection: SqlConnection
    )
}