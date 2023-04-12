package com.panomc.platform.db

import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlClient

abstract class DatabaseMigration(val databaseManager: DatabaseManager) {
    abstract val handlers: List<suspend (sqlClient: SqlClient) -> Unit>

    abstract val FROM_SCHEME_VERSION: Int
    abstract val SCHEME_VERSION: Int
    abstract val SCHEME_VERSION_INFO: String

    fun isMigratable(version: Int) = version == FROM_SCHEME_VERSION

    suspend fun migrate(sqlClient: SqlClient) {
        handlers.forEach {
            it.invoke(sqlClient)
        }
    }

    suspend fun updateSchemeVersion(
        sqlClient: SqlClient
    ) {
        databaseManager.schemeVersionDao.add(
            sqlClient,
            SchemeVersion(SCHEME_VERSION.toString(), SCHEME_VERSION_INFO)
        )
    }

    fun getTablePrefix() = databaseManager.getTablePrefix()
}
