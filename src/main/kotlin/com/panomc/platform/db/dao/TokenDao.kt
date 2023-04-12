package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Token
import com.panomc.platform.token.TokenType
import io.vertx.sqlclient.SqlClient

interface TokenDao : Dao<Token> {

    suspend fun add(
        token: Token,
        sqlClient: SqlClient
    ): Long

    suspend fun existsByTokenAndType(
        token: String,
        tokenType: TokenType,
        sqlClient: SqlClient
    ): Boolean

    suspend fun deleteByToken(token: String, sqlClient: SqlClient)

    suspend fun deleteBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient)

    suspend fun getLastBySubjectAndType(
        subject: String,
        type: TokenType,
        sqlClient: SqlClient
    ): Token?
}