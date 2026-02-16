package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Token
import com.panomc.platform.token.TokenType
import io.vertx.sqlclient.SqlClient

abstract class TokenDao : Dao<Token>(Token::class.java) {

    abstract suspend fun add(
        token: Token,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun existsByTokenAndType(
        token: String,
        tokenType: TokenType,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteByToken(token: String, sqlClient: SqlClient)

    abstract suspend fun deleteBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient)

    abstract suspend fun deleteBySubject(subject: String, sqlClient: SqlClient)

    abstract suspend fun getLastBySubjectAndType(
        subject: String,
        type: TokenType,
        sqlClient: SqlClient
    ): Token?

    abstract suspend fun getAllBySubjectAndType(
        subject: String,
        type: TokenType,
        sqlClient: SqlClient
    ): List<Token>

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): Token?
}