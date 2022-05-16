package com.panomc.platform.db

import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlConnection

abstract class DatabaseMigration(val databaseManager: DatabaseManager) {
    abstract val handlers: List<suspend (sqlConnection: SqlConnection) -> Unit>

    abstract val FROM_SCHEME_VERSION: Int
    abstract val SCHEME_VERSION: Int
    abstract val SCHEME_VERSION_INFO: String

    fun isMigratable(version: Int) = version == FROM_SCHEME_VERSION

    suspend fun migrate(sqlConnection: SqlConnection) {
        handlers.forEach {
            it.invoke(sqlConnection)
        }
    }

    suspend fun updateSchemeVersion(
        sqlConnection: SqlConnection
    ) {
        databaseManager.schemeVersionDao.add(
            sqlConnection,
            SchemeVersion(SCHEME_VERSION.toString(), SCHEME_VERSION_INFO)
        )
    }

    fun getTablePrefix() = databaseManager.getTablePrefix()
}
