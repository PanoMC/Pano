package com.panomc.platform.db

import com.panomc.platform.db.model.SchemeVersion
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.annotation.Autowired

abstract class DatabaseMigration(val from: Int, val to: Int, val info: String) {
    abstract val handlers: List<suspend (sqlClient: SqlClient) -> Unit>

    @Autowired
    lateinit var databaseManager: DatabaseManager

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
            SchemeVersion(to.toString(), info)
        )
    }

    fun getTablePrefix() = databaseManager.getTablePrefix()
}
