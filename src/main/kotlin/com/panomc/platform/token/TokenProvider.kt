package com.panomc.platform.token

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Token
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jws
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.vertx.sqlclient.SqlConnection
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

class TokenProvider(
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager
) {
    fun generateToken(subject: String, tokenType: TokenType): Pair<String, Long> {
        val privateKeySpec = PKCS8EncodedKeySpec(
            Decoders.BASE64.decode(
                configManager.getConfig().getJsonObject("jwt-keys").getString("private")
            )
        )
        val keyFactory = KeyFactory.getInstance("RSA")

        val claims = mutableMapOf<String, Any>()

        claims["tokenType"] = tokenType.name

        val expireDate = tokenType.expireDate.invoke()

        val token = Jwts.builder()
            .setSubject(subject)
            .setId(UUID.randomUUID().toString())
            .addClaims(claims)
            .setExpiration(Date(expireDate))
            .signWith(keyFactory.generatePrivate(privateKeySpec))
            .compact()

        return Pair(token, expireDate)
    }

    suspend fun saveToken(
        token: String,
        subject: String,
        tokenType: TokenType,
        expireDate: Long,
        sqlConnection: SqlConnection
    ) {
        val tokenObject = Token(subject = subject, token = token, type = tokenType, expireDate = expireDate)

        databaseManager.tokenDao.add(tokenObject, sqlConnection)
    }

    suspend fun isTokenValid(token: String, tokenType: TokenType, sqlConnection: SqlConnection): Boolean {
        try {
            parseToken(token)
        } catch (exception: Exception) {
            return false
        }

        return databaseManager.tokenDao.isExistsByTokenAndType(token, tokenType, sqlConnection)
    }

    suspend fun invalidateToken(token: String, sqlConnection: SqlConnection) {
        databaseManager.tokenDao.deleteByToken(token, sqlConnection)
    }

    suspend fun invalidateTokensBySubjectAndType(subject: String, type: TokenType, sqlConnection: SqlConnection) {
        databaseManager.tokenDao.deleteBySubjectAndType(subject, type, sqlConnection)
    }

    @Throws(JwtException::class)
    fun parseToken(token: String): Jws<Claims> {
        val publicKeySpec = X509EncodedKeySpec(
            Decoders.BASE64.decode(
                configManager.getConfig().getJsonObject("jwt-keys").getString("public")
            )
        )
        val keyFactory = KeyFactory.getInstance("RSA")

        return Jwts.parserBuilder()
            .setSigningKey(
                keyFactory.generatePublic(publicKeySpec)
            )
            .build()
            .parseClaimsJws(token)
    }
}