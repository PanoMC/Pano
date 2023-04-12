package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelConfig
import io.vertx.sqlclient.SqlClient

interface PanelConfigDao : Dao<PanelConfig> {
    suspend fun byUserIdAndOption(
        userId: Long,
        option: String,
        sqlClient: SqlClient
    ): PanelConfig?

    suspend fun add(
        panelConfig: PanelConfig,
        sqlClient: SqlClient
    )

    suspend fun updateValueById(
        id: Long,
        value: String,
        sqlClient: SqlClient
    )

    suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}