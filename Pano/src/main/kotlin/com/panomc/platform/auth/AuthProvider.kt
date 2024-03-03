package com.panomc.platform.auth

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Permission
import com.panomc.platform.error.LoginEmailNotVerified
import com.panomc.platform.error.LoginIsInvalid
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.NoPermission
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AuthProvider(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
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
        sqlClient: SqlClient
    ) {
        val isLoginCorrect = databaseManager.userDao.isLoginCorrect(usernameOrEmail, password, sqlClient)

        if (!isLoginCorrect) {
            throw LoginIsInvalid()
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)!!

        val isVerified = databaseManager.userDao.isEmailVerifiedById(userId, sqlClient)

        if (!isVerified) {
            throw LoginEmailNotVerified()
        }

        val isBanned = databaseManager.userDao.isBanned(userId, sqlClient)

        if (isBanned) {
            throw LoginUserIsBanned()
        }
    }

    suspend fun login(
        usernameOrEmail: String,
        sqlClient: SqlClient
    ): String {
        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(
            usernameOrEmail,
            sqlClient
        )!!

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.AUTHENTICATION)

        tokenProvider.saveToken(token, userId.toString(), TokenType.AUTHENTICATION, expireDate, sqlClient)

        return token
    }

    suspend fun isLoggedIn(
        routingContext: RoutingContext
    ): Boolean {
        val sqlClient = databaseManager.getSqlClient()
        val token = getTokenFromRoutingContext(routingContext) ?: return false

        val isTokenValid = tokenProvider.isTokenValid(token, TokenType.AUTHENTICATION, sqlClient)

        return isTokenValid
    }

    suspend fun hasAccessPanel(
        routingContext: RoutingContext
    ): Boolean {
        val userId = getUserIdFromRoutingContext(routingContext)

        return hasPermission(userId, PanelPermission.ACCESS_PANEL, routingContext)
    }

    fun validateInput(
        usernameOrEmail: String,
        password: String,
        recaptcha: String
    ) {
        if (usernameOrEmail.isEmpty()) {
            throw LoginIsInvalid()
        }

        if (!usernameOrEmail.matches(Regex(Regexes.USERNAME)) && !usernameOrEmail.matches(Regex(Regexes.EMAIL))) {
            throw LoginIsInvalid()
        }

        if (password.isEmpty()) {
            throw LoginIsInvalid()
        }

        if (password.length < 6 || password.length > 128) {
            throw LoginIsInvalid()
        }

//        if (!this.reCaptcha.isValid(reCaptcha)) {
//            handler.invoke(Error(ErrorCode.RECAPTCHA_NOT_VALID))
//
//            return
//        }
    }

    fun getUserIdFromRoutingContext(routingContext: RoutingContext): Long {
        val token = getTokenFromRoutingContext(routingContext)

        return getUserIdFromToken(token!!)
    }

    fun getUserIdFromToken(token: String): Long {
        val jwt = tokenProvider.parseToken(token)

        return jwt.subject.toLong()
    }

    fun getTokenFromRoutingContext(routingContext: RoutingContext): String? {
        val request = routingContext.request()

        val jwtCookie = request.getCookie("pano_jwt")

        if (jwtCookie != null) {
            return jwtCookie.value
        }

        val authorizationHeader = request.getHeader("Authorization") ?: return null

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

    suspend fun logout(routingContext: RoutingContext, sqlClient: SqlClient) {
        val isLoggedIn = isLoggedIn(routingContext)

        if (!isLoggedIn) {
            return
        }

        val token = getTokenFromRoutingContext(routingContext)!!

        tokenProvider.invalidateToken(token, sqlClient)
    }

    suspend fun getAdminList(sqlClient: SqlClient): List<String> {
        val adminPermissionId = databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlClient)!!

        val admins = databaseManager.userDao.getUsernamesByPermissionGroupId(adminPermissionId, -1, sqlClient)

        return admins
    }

    suspend fun hasPermission(userId: Long, panelPermission: PanelPermission, context: RoutingContext): Boolean {
        val isAdmin = context.get<Boolean>("isAdmin")

        if (isAdmin != null) {
            return isAdmin
        }

        val existingPermissionsList = context.get<List<Permission>>("permissions")

        if (existingPermissionsList != null) {
            return existingPermissionsList.hasPermission(panelPermission)
        }

        val sqlClient = context.get<SqlClient>("sqlClient")

        val permissionGroupName = databaseManager.userDao.getPermissionGroupNameById(userId, sqlClient)

        if (permissionGroupName == "admin") {
            context.put("isAdmin", true)

            return true
        }

        val permissions = databaseManager.userDao.getPermissionsById(userId, sqlClient)

        context.put("permissions", permissions)

        return permissions.hasPermission(panelPermission)
    }

    suspend fun requirePermission(panelPermission: PanelPermission, context: RoutingContext) {
        val userId = getUserIdFromRoutingContext(context)

        if (!hasPermission(userId, panelPermission, context)) {
            throw NoPermission()
        }
    }

    private fun List<Permission>.hasPermission(panelPermission: PanelPermission) =
        this.any { it.name == panelPermission.toString() }
}