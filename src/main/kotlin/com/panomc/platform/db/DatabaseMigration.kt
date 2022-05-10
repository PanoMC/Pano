package com.panomc.platform.db

import com.panomc.platform.db.model.SchemeVersion
import io.vertx.core.AsyncResult
import io.vertx.sqlclient.SqlConnection

abstract class DatabaseMigration(val databaseManager: DatabaseManager) {
    abstract val handlers: List<(sqlConnection: SqlConnection, handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit>

    abstract val FROM_SCHEME_VERSION: Int
    abstract val SCHEME_VERSION: Int
    abstract val SCHEME_VERSION_INFO: String

    fun isMigratable(version: Int) = version == FROM_SCHEME_VERSION

    fun migrate(
        sqlConnection: SqlConnection
    ): (handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit = { handler ->
        var currentIndex = 0

        fun invoke() {
            val localHandler: (AsyncResult<*>) -> Unit = {
                when {
                    it.failed() -> handler.invoke(it)
                    currentIndex == handlers.lastIndex -> handler.invoke(it)
                    else -> {
                        currentIndex++

                        invoke()
                    }
                }
            }

            if (currentIndex <= handlers.lastIndex)
                handlers[currentIndex].invoke(sqlConnection, localHandler)
        }

        invoke()
    }

    fun updateSchemeVersion(
        sqlConnection: SqlConnection
    ): (handler: (asyncResult: AsyncResult<*>) -> Unit) -> Unit = { handler ->
        databaseManager.schemeVersionDao.add(
            sqlConnection,
            SchemeVersion(SCHEME_VERSION.toString(), SCHEME_VERSION_INFO)
        ) { _, asyncResult ->
            handler.invoke(asyncResult)
        }
    }

    fun getTablePrefix() = databaseManager.getTablePrefix()
}
