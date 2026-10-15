package com.panomc.platform.route.api.panel.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.CreatedPlayerLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.RegisterUtil
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * An admin creates a player by hand: the account a visitor would get from the register form, without the form's
 * agreement, verification e-mail or the visitor's address. Field problems answer as `INVALID_FIELDS`, the way the
 * player edit endpoint does, so the panel marks the field.
 */
@Endpoint
class PanelCreatePlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/players", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("username", stringSchema())
                        .requiredProperty("email", stringSchema())
                        .requiredProperty("password", stringSchema())
                        .requiredProperty("passwordRepeat", stringSchema())
                        .optionalProperty("isEmailVerified", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val data = getParameters(context).body().jsonObject

        val username = data.getString("username").trim()
        val email = data.getString("email").trim()
        val password = data.getString("password")
        val passwordRepeat = data.getString("passwordRepeat")
        // Made by an admin who typed the address: verified unless the admin says otherwise.
        val isEmailVerified = data.getBoolean("isEmailVerified") ?: true

        val errors = validate(username, email, password, passwordRepeat).toMutableMap()

        val sqlClient = getSqlClient()

        if ("username" !in errors && databaseManager.userDao.existsByUsername(username, sqlClient)) {
            errors["username"] = "EXISTS"
        }

        if ("email" !in errors && databaseManager.userDao.isEmailExists(email, sqlClient)) {
            errors["email"] = "EXISTS"
        }

        if (errors.isNotEmpty()) {
            throw InvalidFields(errors)
        }

        // No registration address: the player did not register from anywhere, and the admin's own address would
        // tie the account to the admin (and to an IP ban that is none of the player's business).
        val playerId = RegisterUtil.register(
            databaseManager,
            sqlClient,
            username,
            email,
            password,
            remoteIP = "",
            isSetup = true
        )

        databaseManager.userDao.updateEmailVerifyStatusById(playerId, isEmailVerified, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val authUsername = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(CreatedPlayerLog(userId, authUsername, username), sqlClient)

        return Successful(mapOf("id" to playerId, "username" to username))
    }

    companion object {
        /** The same limits as the register form ([RegisterUtil.validateForm]), as field codes. */
        internal fun validate(
            username: String,
            email: String,
            password: String,
            passwordRepeat: String
        ): Map<String, Any> {
            val errors = mutableMapOf<String, Any>()

            if (username.length < 3 || username.length > 16 || !username.matches(Regex(Regexes.USERNAME))) {
                errors["username"] = "INVALID"
            }

            if (!email.matches(Regex(Regexes.EMAIL))) {
                errors["email"] = "INVALID"
            }

            if (password.isBlank() || password.length < 6 || password.length > 128) {
                errors["password"] = "INVALID"
            }

            if (passwordRepeat != password) {
                errors["passwordRepeat"] = "NOT_MATCH"
            }

            return errors
        }
    }
}
