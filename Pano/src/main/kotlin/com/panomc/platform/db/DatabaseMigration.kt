package com.panomc.platform.db

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlClient

abstract class DatabaseMigration(val from: Int, val to: Int, val info: String) {
    abstract val handlers: List<suspend (sqlClient: SqlClient) -> Unit>

    private val databaseManager: DatabaseManager by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    fun isMigratable(version: Int) = version == from

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
            SchemeVersion(key = to.toString(), extra = info)
        )
    }

    fun getTablePrefix() = databaseManager.getTablePrefix()
}
