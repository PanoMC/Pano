package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Token
import com.panomc.platform.token.TokenType
import io.vertx.sqlclient.SqlConnection

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

    suspend fun deleteByToken(token: String, sqlConnection: SqlConnection)

    suspend fun deleteBySubjectAndType(subject: String, type: TokenType, sqlConnection: SqlConnection)

    suspend fun getLastBySubjectAndType(
        subject: String,
        type: TokenType,
        sqlConnection: SqlConnection
    ): Token?
}