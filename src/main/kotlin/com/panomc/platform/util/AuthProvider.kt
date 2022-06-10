package com.panomc.platform.util

import com.panomc.platform.ErrorCode
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jws
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AuthProvider(
    private val databaseManager: DatabaseManager,
    private val mConfigManager: ConfigManager
) {
    companion object {
        const val HEADER_PREFIX = "Bearer "
    }

    /**
     * authenticate method validates input and login
     * Successful() if login is valid
     */
    suspend fun authenticate(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection
    ) {
        val isLoginCorrect = databaseManager.userDao.isLoginCorrect(usernameOrEmail, password, sqlConnection)

        if (!isLoginCorrect) {
            throw Error(ErrorCode.LOGIN_IS_INVALID)
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlConnection) ?: throw Error(
                ErrorCode.UNKNOWN
            )

        val isVerified = databaseManager.userDao.isEmailVerifiedById(userId, sqlConnection)

        if (!isVerified) {
            throw Error(ErrorCode.LOGIN_EMAIL_NOT_VERIFIED)
        }
    }

    suspend fun login(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): String {
        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(
            usernameOrEmail,
            sqlConnection
        )

        if (userId == null) {
            throw Error(ErrorCode.UNKNOWN)
        }

        val privateKeySpec = PKCS8EncodedKeySpec(
            Decoders.BASE64.decode(
                mConfigManager.getConfig().getJsonObject("jwt-keys").getString("private")
            )
        )
        val keyFactory = KeyFactory.getInstance("RSA")

        val token = Jwts.builder()
            .setSubject(userId.toString())
            .signWith(
                keyFactory.generatePrivate(privateKeySpec)
            )
            .compact()

        return token
    }

    fun isLoggedIn(
        routingContext: RoutingContext
    ): Boolean {
        val token = getTokenFromRoutingContext(routingContext)

        if (token == null) {
            return false
        }

        return isTokenValid(token)
    }

//    fun isAdmin(
//        databaseManager: DatabaseManager,
//        routingContext: RoutingContext,
//        handler: (isAdmin: Boolean, asyncResult: AsyncResult<*>?) -> Unit
//    ) {
//        val cookie = routingContext.getCookie(COOKIE_NAME)
//
//        val token = cookie.value
//
//        databaseManager.createConnection { sqlConnection, asyncResultOfCreateConnection ->
//            if (sqlConnection == null) {
//                handler.invoke(false, asyncResultOfCreateConnection)
//
//                return@createConnection
//            }
//
//            databaseManager.tokenDao.getUserIDFromToken(
//                token,
//                sqlConnection
//            ) { userID, asyncResultGetUserIDFromToken ->
//                if (userID == null) {
//                    databaseManager.closeConnection(sqlConnection) {
//                        handler.invoke(false, asyncResultGetUserIDFromToken)
//                    }
//
//                    return@getUserIDFromToken
//                }
//
//                if (userID == 0) {
//                    databaseManager.closeConnection(sqlConnection) {
//                        handler.invoke(false, asyncResultGetUserIDFromToken)
//                    }
//
//                    return@getUserIDFromToken
//                }
//
//                databaseManager.userDao.getPermissionGroupIDFromUserID(
//                    userID,
//                    sqlConnection
//                ) { permissionGroupID, asyncResultOfGetPermissionGroupIDFromUserID ->
//                    if (permissionGroupID == null) {
//                        databaseManager.closeConnection(sqlConnection) {
//                            handler.invoke(false, asyncResultOfGetPermissionGroupIDFromUserID)
//                        }
//
//                        return@getPermissionGroupIDFromUserID
//                    }
//
//                    if (permissionGroupID == -1) {
//                        databaseManager.closeConnection(sqlConnection) {
//                            handler.invoke(false, asyncResultOfGetPermissionGroupIDFromUserID)
//                        }
//
//                        return@getPermissionGroupIDFromUserID
//                    }
//
//                    databaseManager.permissionGroupDao.getPermissionGroupByID(
//                        permissionGroupID,
//                        sqlConnection
//                    ) { permissionGroup, asyncResultOfGetPermissionGroupID ->
//                        databaseManager.closeConnection(sqlConnection) {
//                            if (permissionGroup == null) {
//                                handler.invoke(false, asyncResultOfGetPermissionGroupID)
//
//                                return@closeConnection
//                            }
//
//                            if (permissionGroup.name != "admin") {
//                                handler.invoke(false, asyncResultOfGetPermissionGroupID)
//
//                                return@closeConnection
//                            }
//
//                            handler.invoke(true, asyncResultOfGetPermissionGroupID)
//                        }
//                    }
//                }
//            }
//        }
//    }

    suspend fun hasAccessPanel(
        routingContext: RoutingContext
    ): Boolean {
        val sqlConnection = databaseManager.createConnection()

        val hasAccess = hasAccessPanel(routingContext, sqlConnection)

        databaseManager.closeConnection(sqlConnection)

        return hasAccess
    }

    suspend fun hasAccessPanel(
        routingContext: RoutingContext,
        sqlConnection: SqlConnection
    ): Boolean {
        val userId = getUserIdFromRoutingContext(routingContext)

        val permissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(userId, sqlConnection)

        if (permissionGroupId == null || permissionGroupId == -1L) {
            return false
        }

        return true
    }

    fun validateInput(
        usernameOrEmail: String,
        password: String,
        recaptcha: String
    ) {
        if (usernameOrEmail.isEmpty()) {
            throw Error(ErrorCode.LOGIN_IS_INVALID)
        }

        if (!usernameOrEmail.matches(Regex("^[a-zA-Z0-9_]+\$")) && !usernameOrEmail.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}\$"))) {
            throw Error(ErrorCode.LOGIN_IS_INVALID)
        }

        if (password.isEmpty()) {
            throw Error(ErrorCode.LOGIN_IS_INVALID)
        }

        if (password.length < 6 || password.length > 128) {
            throw Error(ErrorCode.LOGIN_IS_INVALID)
        }

//        if (!this.reCaptcha.isValid(reCaptcha)) {
//            handler.invoke(Error(ErrorCode.RECAPTCHA_NOT_VALID))
//
//            return
//        }
    }

//    fun logout(
//        databaseManager: DatabaseManager,
//        routingContext: RoutingContext,
//        handler: (isLoggedOut: Result?, asyncResult: AsyncResult<*>?) -> Unit
//    ) {
//    }

    fun getUserIdFromRoutingContext(routingContext: RoutingContext): Long {
        val token = getTokenFromRoutingContext(routingContext)

        return getUserIdFromToken(token!!)
    }

    fun getUserIdFromToken(token: String): Long {
        val jwt = parseToken(token)

        return jwt.body.subject.toLong()
    }

    fun getTokenFromRoutingContext(routingContext: RoutingContext): String? {
        val authorizationHeader = routingContext.request().getHeader("Authorization") ?: return null

        if (!authorizationHeader.contains(HEADER_PREFIX)) {
            return null
        }

        val splitHeader = authorizationHeader.split(HEADER_PREFIX)

        if (splitHeader.size != 2) {
            return null
        }

        return try {
            val token = splitHeader.last()

            token
        } catch (exception: Exception) {
            null
        }
    }

    fun isTokenValid(token: String) = try {
        parseToken(token)

        true
    } catch (exception: Exception) {

        false
    }

    @Throws(JwtException::class)
    fun parseToken(token: String): Jws<Claims> {
        val publicKeySpec = X509EncodedKeySpec(
            Decoders.BASE64.decode(
                mConfigManager.getConfig().getJsonObject("jwt-keys").getString("public")
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