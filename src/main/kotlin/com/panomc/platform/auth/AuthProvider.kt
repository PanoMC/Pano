package com.panomc.platform.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AuthProvider(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
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

        val isBanned = databaseManager.userDao.isBanned(userId, sqlConnection)

        if (isBanned) {
            throw Error(ErrorCode.LOGIN_USER_IS_BANNED)
        }
    }

    suspend fun login(
        usernameOrEmail: String,
        sqlConnection: SqlConnection
    ): String {
        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(
            usernameOrEmail,
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.AUTHENTICATION)

        tokenProvider.saveToken(token, userId.toString(), TokenType.AUTHENTICATION, expireDate, sqlConnection)

        return token
    }

    suspend fun isLoggedIn(
        routingContext: RoutingContext
    ): Boolean {
        val token = getTokenFromRoutingContext(routingContext) ?: return false

        val sqlConnection = databaseManager.createConnection()

        val isTokenValid = tokenProvider.isTokenValid(token, TokenType.AUTHENTICATION, sqlConnection)

        databaseManager.closeConnection(sqlConnection)

        return isTokenValid
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

        if (!usernameOrEmail.matches(Regex(Regexes.USERNAME)) && !usernameOrEmail.matches(Regex(Regexes.EMAIL))) {
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
        val jwt = tokenProvider.parseToken(token)

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

    suspend fun logout(routingContext: RoutingContext, sqlConnection: SqlConnection) {
        val isLoggedIn = isLoggedIn(routingContext)

        if (!isLoggedIn) {
            return
        }

        val token = getTokenFromRoutingContext(routingContext)!!

        tokenProvider.invalidateToken(token, sqlConnection)
    }

    suspend fun getAdminList(sqlConnection: SqlConnection): List<String> {
        val adminPermissionId = databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlConnection)!!

        val admins = databaseManager.userDao.getUsernamesByPermissionGroupId(adminPermissionId, -1, sqlConnection)

        return admins
    }
}