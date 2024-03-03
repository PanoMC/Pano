package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PanelConfig
import io.vertx.sqlclient.SqlClient

abstract class PanelConfigDao : Dao<PanelConfig>(PanelConfig::class.java) {
    abstract suspend fun byUserIdAndOption(
        userId: Long,
        option: String,
        sqlClient: SqlClient
    ): PanelConfig?

    abstract suspend fun add(
        panelConfig: PanelConfig,
        sqlClient: SqlClient
    )

    abstract suspend fun updateValueById(
        id: Long,
        value: String,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}