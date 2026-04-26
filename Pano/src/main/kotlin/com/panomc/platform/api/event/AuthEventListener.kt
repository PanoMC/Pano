package com.panomc.platform.api.event

import com.panomc.platform.db.model.User
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient

/**
 * Event listener for authentication lifecycle events.
 * Plugins can implement this to hook into login and register flows.
 */
interface AuthEventListener : PanoEventListener {

    /**
     * Called before credentials are validated (before password check).
     * Use this for pre-authentication checks like captcha verification.
     * Return a non-null LoginDecision to deny the login before password is checked.
     * Returning null lets the authentication proceed normally.
     */
    suspend fun onBeforeAuthenticate(context: RoutingContext, sqlClient: SqlClient): LoginDecision? {
        return null
    }

    /**
     * Called before link-code verification (POST /api/auth/verifyLinkCode).
     * Use when login captcha is required for users with no password (link-only), instead of on the
     * initial username request that returns LinkCodeRequired.
     */
    suspend fun onBeforeVerifyLinkCode(context: RoutingContext, sqlClient: SqlClient): LoginDecision? {
        return null
    }

    /**
     * Called before a user logs in (after credentials are validated but before token creation).
     * Use this for post-authentication checks like 2FA verification.
     * Return a non-null LoginDecision to override the default behavior.
     * Returning null lets the login proceed normally.
     */
    suspend fun onBeforeLogin(user: User, context: RoutingContext, sqlClient: SqlClient): LoginDecision? {
        return null
    }

    /**
     * Called after a successful login (after token creation and cookie setting).
     */
    suspend fun onAfterLogin(user: User, context: RoutingContext, sqlClient: SqlClient) {}

    /**
     * Called after a successful registration.
     */
    suspend fun onAfterRegister(user: User, sqlClient: SqlClient) {}

    sealed class LoginDecision {
        /**
         * Deny the login with a custom error key that will be sent to the client.
         */
        data class Deny(val errorKey: String, val extras: Map<String, Any?> = emptyMap()) : LoginDecision()

        /**
         * Require the user to set a new username before login can proceed.
         * The client will show a username input form.
         */
        data class RequireUsername(val userId: Long) : LoginDecision()

        /**
         * Allow the login to proceed normally.
         */
        data object Allow : LoginDecision()
    }
}
