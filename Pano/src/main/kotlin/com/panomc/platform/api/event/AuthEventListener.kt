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
     * Called before a user logs in (after credentials are validated but before token creation).
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
