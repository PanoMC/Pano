package com.panomc.platform.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.AuthenticationTokenType
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.Regexes
import com.panomc.platform.util.TextUtil
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
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
    private val configManager: ConfigManager,
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

        val authConfig = configManager.config.auth
        if (!dontCheckVerified || !authConfig.requireEmailVerification) {
            databaseManager.userDao.getEmailFromUserId(userId, sqlClient) ?: throw RegisterEmailRequired()

            val isVerified = databaseManager.userDao.isEmailVerifiedById(userId, sqlClient)

            if (!isVerified) {
                val email = databaseManager.userDao.getEmailFromUserId(userId, sqlClient)
                val maskedEmail = email?.let { TextUtil.maskEmail(it) } ?: ""

                throw LoginEmailNotVerified(extras = mapOf("email" to maskedEmail))
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
        routingContext: RoutingContext,
        sqlClient: SqlClient
    ): String {
        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(
            usernameOrEmail,
            sqlClient
        )!!

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), AuthenticationTokenType)

        val ipAddress = getRemoteIP(routingContext)
        val userAgent = routingContext.request().getHeader("User-Agent")

        val tokens = databaseManager.tokenDao.getAllBySubjectAndType(userId.toString(), AuthenticationTokenType, sqlClient)

        if (tokens.size >= 5) {
             val tokensToDelete = tokens.drop(4) // Keep 4, so including the new one it will be 5.
             tokensToDelete.forEach {
                 databaseManager.tokenDao.deleteByToken(it.token, sqlClient)
             }
        }

        tokenProvider.saveToken(token, userId.toString(), AuthenticationTokenType, expireDate, sqlClient, ipAddress, userAgent)

        return token
    }

    fun setCookies(
        routingContext: RoutingContext, authToken: String, csrfToken: String
    ): Boolean {
        val response = routingContext.response()
        val request = routingContext.request()
        val domain = resolveCookieDomain(routingContext)
        val forwardedProto = request.getHeader("X-Forwarded-Proto")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.lowercase()

        // Secure flag should follow the effective request protocol, not website-url config.
        val isSecure = request.isSSL || forwardedProto == "https"

        val authTokenCookie = Cookie.cookie(AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME, authToken)
        val csrfTokenCookie = Cookie.cookie(AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME, csrfToken)

        listOf(authTokenCookie, csrfTokenCookie).forEach { cookie ->
            domain?.let { cookie.domain = it }
            cookie.maxAge = 7776000
            cookie.path = "/"
            cookie.isSecure = isSecure
            cookie.isHttpOnly = true
            cookie.sameSite = CookieSameSite.LAX
        }

        response.addCookie(authTokenCookie)
        response.addCookie(csrfTokenCookie)

        return true
    }

    fun clearCookies(routingContext: RoutingContext): Boolean {
        val response = routingContext.response()
        val domain = resolveCookieDomain(routingContext)

        listOf(
            AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME,
            AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME
        ).forEach { cookieName ->
            val cookie = Cookie.cookie(cookieName, "deleted")
            domain?.let { cookie.domain = it }
            cookie.maxAge = 0
            cookie.path = "/"
            response.addCookie(cookie)
        }

        return true
    }

    private fun resolveCookieDomain(routingContext: RoutingContext): String? {
        val remoteIP = getRemoteIP(routingContext)

        if (remoteIP == null || remoteIP == "127.0.0.1" || remoteIP == "::1" || remoteIP == "localhost") {
            return null
        }

        val websiteUrl = configManager.config.websiteUrl
        if (websiteUrl.isEmpty()) {
            return null
        }

        return try {
            val websiteHost = websiteUrl
                .replace("http://", "")
                .replace("https://", "")
                .split("/")
                .first()
                .split(":")
                .first()

            if (websiteHost.isNotEmpty() && websiteHost != "localhost" && websiteHost != "127.0.0.1") {
                ".$websiteHost"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getRemoteIP(routingContext: RoutingContext): String {
        val request = routingContext.request()
        return request.getHeader("X-Forwarded-For")?.split(",")?.first()?.trim()
            ?: request.getHeader("X-Real-IP")
            ?: request.remoteAddress().host()
    }
    
    suspend fun isLoggedIn(
        routingContext: RoutingContext
    ): Boolean {
        val sqlClient = databaseManager.getSqlClient()
        val token = getTokenFromRoutingContext(routingContext) ?: return false

        val isTokenValid = tokenProvider.isTokenValid(token, AuthenticationTokenType, sqlClient)

        return isTokenValid
    }

    suspend fun hasAccessPanel(
        routingContext: RoutingContext
    ): Boolean {
        return hasPermission(AccessPanelPermission(), routingContext)
    }

    fun validateInput(
        usernameOrEmail: String,
        password: String
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

    suspend fun requirePassword(password: String?, context: RoutingContext) {
        if (password == null) {
            throw NoPermission()
        }

        val sqlClient = databaseManager.getSqlClient()
        val userId = getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NoPermission()

        val isLoginCorrect = databaseManager.userDao.isLoginCorrect(username, password, sqlClient)

        if (!isLoginCorrect) {
            throw NoPermission()
        }
    }

    private fun List<String>.hasPermission(permission: Permission) =
        this.any { it == permission.toString() }
}