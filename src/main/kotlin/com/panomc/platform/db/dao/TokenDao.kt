package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Token
import com.panomc.platform.util.TokenType
import io.vertx.sqlclient.SqlConnection

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
interface TokenDao : Dao<Token> {

    suspend fun add(
        token: Token,
        sqlConnection: SqlConnection
    ): Long

    suspend fun isExistsByTokenAndType(
        token: String,
        tokenType: TokenType,
        sqlConnection: SqlConnection
    ): Boolean
}