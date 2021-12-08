package com.panomc.platform.util

import com.panomc.platform.ErrorCode
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jws
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AuthProvider(
    private val mDatabaseManager: DatabaseManager,
    private val mConfigManager: ConfigManager
) {
    companion object {
        const val HEADER_PREFIX = "Bearer "
    }

    /**
     * authenticate method validates input and login
     * Successful() if login is valid
     */
    fun authenticate(
        usernameOrEmail: String,
        password: String,
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
    ) {
        val isEmailVerifiedByIDHandler = isEmailVerifiedByIDHandler@{ isVerified: Boolean?, _: AsyncResult<*> ->
            if (isVerified == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@isEmailVerifiedByIDHandler
            }

            if (!isVerified) {
                // TODO v2 Add sending e-mail again
                handler.invoke(Error(ErrorCode.LOGIN_EMAIL_NOT_VERIFIED))

                return@isEmailVerifiedByIDHandler
            }

            handler.invoke(Successful())
        }

        val getUserIDFromUsernameOrEmailHandler =
            getUserIDFromUsernameOrEmailHandler@{ userID: Int?, _: AsyncResult<*> ->
                if (userID == null) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))

                    return@getUserIDFromUsernameOrEmailHandler
                }

                mDatabaseManager.getDatabase().userDao.isEmailVerifiedByID(
                    userID,
                    sqlConnection,
                    isEmailVerifiedByIDHandler
                )
            }

        val isLoginCorrectHandler = isLoginCorrectHandler@{ isLoginCorrect: Boolean?, _: AsyncResult<*> ->
            if (isLoginCorrect == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@isLoginCorrectHandler
            }

            if (!isLoginCorrect) {
                handler.invoke(Error(ErrorCode.LOGIN_IS_INVALID))

                return@isLoginCorrectHandler
            }

            mDatabaseManager.getDatabase().userDao.getUserIDFromUsernameOrEmail(
                usernameOrEmail,
                sqlConnection,
                getUserIDFromUsernameOrEmailHandler
            )
        }

        mDatabaseManager.getDatabase().userDao.isLoginCorrect(
            usernameOrEmail,
            password,
            sqlConnection,
            isLoginCorrectHandler
        )
    }

    fun login(
        usernameOrEmail: String,
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit
    ) {
        val getUserIDFromUsernameOrEmailHandler =
            getUserIDFromUsernameOrEmailHandler@{ userID: Int?, _: AsyncResult<*> ->
                if (userID == null) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))

                    return@getUserIDFromUsernameOrEmailHandler
                }

                val privateKeySpec = PKCS8EncodedKeySpec(
                    Decoders.BASE64.decode(
                        (mConfigManager.getConfig()["jwt-keys"] as Map<*, *>)["private"] as String
                    )
                )
                val keyFactory = KeyFactory.getInstance("RSA")

                val token = Jwts.builder()
                    .setSubject(userID.toString())
                    .signWith(
                        keyFactory.generatePrivate(privateKeySpec)
                    )
                    .compact()

                handler.invoke(
                    Successful(
                        mapOf(
                            "jwt" to token
                        )
                    )
                )
            }

        mDatabaseManager.getDatabase().userDao.getUserIDFromUsernameOrEmail(
            usernameOrEmail,
            sqlConnection,
            getUserIDFromUsernameOrEmailHandler
        )
    }

    fun isLoggedIn(
        routingContext: RoutingContext,
        handler: (isLoggedIn: Boolean) -> Unit
    ) {
        val token = getTokenFromRoutingContext(routingContext)

        if (token == null) {
            handler.invoke(false)

            return
        }

        handler.invoke(isTokenValid(token))
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
//            databaseManager.getDatabase().tokenDao.getUserIDFromToken(
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
//                databaseManager.getDatabase().userDao.getPermissionGroupIDFromUserID(
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
//                    databaseManager.getDatabase().permissionGroupDao.getPermissionGroupByID(
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

    fun hasAccessPanel(
        routingContext: RoutingContext,
        handler: (hasAccess: Boolean, asyncResult: AsyncResult<*>?) -> Unit
    ) {
        fun hasAccessPanelHandler(sqlConnection: SqlConnection) =
            hasAccessPanelHandler@{ hasAccess: Boolean, asyncResult: AsyncResult<*>? ->
                mDatabaseManager.closeConnection(sqlConnection) {
                    handler.invoke(hasAccess, asyncResult)
                }
            }

        val createConnectionHandler =
            createConnectionHandler@{ sqlConnection: SqlConnection?, asyncResult: AsyncResult<SqlConnection> ->
                if (sqlConnection == null) {
                    handler.invoke(false, asyncResult)

                    return@createConnectionHandler
                }


                hasAccessPanel(routingContext, sqlConnection, hasAccessPanelHandler(sqlConnection))
            }

        mDatabaseManager.createConnection(createConnectionHandler)
    }

    fun hasAccessPanel(
        routingContext: RoutingContext,
        sqlConnection: SqlConnection,
        handler: (hasAccess: Boolean, asyncResult: AsyncResult<*>?) -> Unit
    ) {
        val userID = getUserIDFromRoutingContext(routingContext)

        val getPermissionGroupIDFromUserIDHandler =
            getPermissionGroupIDFromUserIDHandler@{ permissionGroupID: Int?, asyncResult: AsyncResult<*> ->
                if (permissionGroupID == null) {
                    handler.invoke(false, asyncResult)

                    return@getPermissionGroupIDFromUserIDHandler
                }

                if (permissionGroupID == -1) {
                    handler.invoke(false, asyncResult)

                    return@getPermissionGroupIDFromUserIDHandler
                }

                handler.invoke(true, asyncResult)
            }

        mDatabaseManager.getDatabase().userDao.getPermissionGroupIDFromUserID(
            userID,
            sqlConnection,
            getPermissionGroupIDFromUserIDHandler
        )
    }

    fun inputValidator(
        usernameOrEmail: String,
        password: String,
        recaptcha: String,
        handler: (result: Result) -> Unit,
    ) {
        if (usernameOrEmail.isEmpty()) {
            handler.invoke(Error(ErrorCode.LOGIN_IS_INVALID))

            return
        }

        if (!usernameOrEmail.matches(Regex("^[a-zA-Z0-9_]+\$")) && !usernameOrEmail.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}\$"))) {
            handler.invoke(Error(ErrorCode.LOGIN_IS_INVALID))

            return
        }

        if (password.isEmpty()) {
            handler.invoke(Error(ErrorCode.LOGIN_IS_INVALID))

            return
        }

        if (password.length < 6 || password.length > 128) {
            handler.invoke(Error(ErrorCode.LOGIN_IS_INVALID))

            return
        }

//        if (!this.reCaptcha.isValid(reCaptcha)) {
//            handler.invoke(Error(ErrorCode.RECAPTCHA_NOT_VALID))
//
//            return
//        }

        handler.invoke(Successful())
    }

//    fun logout(
//        databaseManager: DatabaseManager,
//        routingContext: RoutingContext,
//        handler: (isLoggedOut: Result?, asyncResult: AsyncResult<*>?) -> Unit
//    ) {
//    }

    fun getUserIDFromRoutingContext(routingContext: RoutingContext): Int {
        val token = getTokenFromRoutingContext(routingContext)

        return getUserIDFromToken(token!!)
    }

    fun getUserIDFromToken(token: String): Int {
        val jwt = parseToken(token)

        return jwt.body.subject.toInt()
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
                (mConfigManager.getConfig()["jwt-keys"] as Map<*, *>)["public"] as String
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