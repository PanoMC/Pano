package com.panomc.platform.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.LoginEmailNotVerified
import com.panomc.platform.error.LoginIsInvalid
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.NoPermission
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AuthProvider(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val permissionManager: PermissionManager,
    applicationContext: AnnotationConfigApplicationContext
) {
    companion object {
        const val HEADER_PREFIX = "Bearer "
    }

    private val permissions = mutableListOf<Permission>()

    init {
        permissions.addAll(applicationContext.getBeansOfType(Permission::class.java).map { it.value })
    }

    fun getPermissions() = permissions.toList()

    /**
     * authenticate method validates input and login
     * Successful() if login is valid
     */
    suspend fun authenticate(
        usernameOrEmail: String,
        password: String,
        dontCheckVerified: Boolean = false,
        dontCheckBanned: Boolean = false,
        sqlClient: SqlClient
    ) {
        val isLoginCorrect = databaseManager.userDao.isLoginCorrect(usernameOrEmail, password, sqlClient)

        if (!isLoginCorrect) {
            throw LoginIsInvalid()
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)!!

        if (!dontCheckVerified) {
            val isVerified = databaseManager.userDao.isEmailVerifiedById(userId, sqlClient)

            if (!isVerified) {
                throw LoginEmailNotVerified()
            }
        }

        if (dontCheckBanned) {
            return
        }

        val player = databaseManager.userDao.getById(userId, sqlClient)!!

        if (BanUtil.isBanned(player)) {
            throw LoginUserIsBanned(extras = mapOf("reason" to player.banMessage, "until" to player.bannedUntil))
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
        return hasPermission(AccessPanelPermission(), routingContext)
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

    private fun parseCookies(cookieHeader: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()

        try {
            val cookiePairs = cookieHeader.split(";")
            for (cookiePair in cookiePairs) {
                val (name, value) = cookiePair.trim().split("=")
                cookies[name] = value
            }
        } catch (_: Exception) {
        }

        return cookies
    }

    fun getTokenFromRoutingContext(routingContext: RoutingContext): String? {
        val request = routingContext.request()
        val cookieHeader = request.getHeader("cookie") ?: ""

        val cookies = parseCookies(cookieHeader)
        val jwtCookie = cookies[AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME]

        if (jwtCookie != null) {
            return jwtCookie
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
        val adminUserIdList = permissionManager.getUserIdsInGroup("admin")
        return databaseManager.userDao.getUsernameByListOfId(adminUserIdList.toList(), sqlClient).values.toList()
    }

    suspend fun applyPermissionsTo(context: RoutingContext) {
        val isAdminExisting = context.get<Boolean>("isAdmin")
        val existingPermissionsList = context.get<List<String>>("permissions")

        if (isAdminExisting != null && existingPermissionsList != null) {
            return
        }

        val userId = getUserIdFromRoutingContext(context)

        val grantedNodes = permissionManager.getGrantedNodes(userId)
        val isAdmin = grantedNodes.contains("*")

        context.put("permissions", grantedNodes.toList())
        context.put("isAdmin", isAdmin)
    }

    fun isUserAdmin(userId: Long, grantedNodes: Set<String>): Boolean {
        return grantedNodes.contains("*")
    }

    suspend fun isUserAdmin(userId: Long): Boolean {
        return isUserAdmin(userId, permissionManager.getGrantedNodes(userId))
    }

    suspend fun hasPermission(permission: Permission, context: RoutingContext): Boolean {
        val userId = getUserIdFromRoutingContext(context)

        val isAdmin = context.get<Boolean>("isAdmin")

        if (isAdmin != null && isAdmin) {
            return true
        }

        return permissionManager.hasPermission(userId, permission)
    }

    suspend fun requirePermission(permission: Permission, context: RoutingContext) {
        if (!hasPermission(permission, context)) {
            throw NoPermission()
        }
    }

    private fun List<String>.hasPermission(permission: Permission) =
        this.any { it == permission.toString() }
}