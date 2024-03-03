package com.panomc.platform.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Token
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.*

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class TokenProvider(
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager
) {
    fun getAlgorithm(): Algorithm {
        val secretKey = String(Base64.getDecoder().decode(configManager.getConfig().getString("jwt-key")))

        return Algorithm.HMAC512(secretKey)
    }

    fun generateToken(subject: String, tokenType: TokenType): Pair<String, Long> {
        val expireDate = tokenType.expireDate.invoke()

        val token = JWT.create()
            .withJWTId(UUID.randomUUID().toString())
            .withSubject(subject)
            .withClaim("tokenType", tokenType.expireDate.invoke())
            .withExpiresAt(Date(expireDate))
            .sign(getAlgorithm())

        return Pair(token, expireDate)
    }

    suspend fun saveToken(
        token: String,
        subject: String,
        tokenType: TokenType,
        expireDate: Long,
        sqlClient: SqlClient
    ) {
        val tokenObject = Token(subject = subject, token = token, type = tokenType, expireDate = expireDate)

        databaseManager.tokenDao.add(tokenObject, sqlClient)
    }

    suspend fun isTokenValid(token: String, tokenType: TokenType, sqlClient: SqlClient): Boolean {
        try {
            parseToken(token)
        } catch (exception: Exception) {
            return false
        }

        return databaseManager.tokenDao.existsByTokenAndType(token, tokenType, sqlClient)
    }

    suspend fun invalidateToken(token: String, sqlClient: SqlClient) {
        databaseManager.tokenDao.deleteByToken(token, sqlClient)
    }

    suspend fun invalidateTokensBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient) {
        databaseManager.tokenDao.deleteBySubjectAndType(subject, type, sqlClient)
    }

    fun parseToken(token: String): DecodedJWT {
        val verifier = JWT.require(getAlgorithm())
            .build()

        return verifier.verify(token)
    }
}