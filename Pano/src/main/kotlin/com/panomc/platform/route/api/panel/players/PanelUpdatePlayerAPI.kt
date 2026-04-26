package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.log.UpdatedPlayerLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdatePlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:id", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("username", stringSchema())
                        .requiredProperty("email", stringSchema())
                        .requiredProperty("newPassword", stringSchema())
                        .requiredProperty("newPasswordRepeat", stringSchema())
                        .requiredProperty("isEmailVerified", booleanSchema())
                        .requiredProperty("canCreateTicket", booleanSchema())
                        .requiredProperty("localeCode", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val playerId = parameters.pathParameter("id").long
        val username = data.getString("username")
        val email = data.getString("email")
        val newPassword = data.getString("newPassword")
        val newPasswordRepeat = data.getString("newPasswordRepeat")
        val isEmailVerified = data.getBoolean("isEmailVerified")
        val canCreateTicket = data.getBoolean("canCreateTicket")
        val localeCode = data.getString("localeCode")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val hasManagePlayerPermission = authProvider.hasPermission(ManagePlayersPermission(), context)

        if (!hasManagePlayerPermission && playerId != userId) {
            throw NoPermission()
        }

        validateForm(username, email, newPassword, newPasswordRepeat)

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsById(playerId, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        if (localeCode != null && localeCode.isNotBlank()) {
            val localeExists = databaseManager.localeDao.existsByCode(localeCode, sqlClient)

            if (!localeExists) {
                throw NotExists()
            }
        }

        val isUserAdmin = authProvider.isUserAdmin(playerId)

        if (isUserAdmin) {
            val isAdmin = context.get<Boolean>("isAdmin") ?: false

            if (!isAdmin) {
                throw NoPermission()
            }
        }

        val user = databaseManager.userDao.getById(playerId, sqlClient)!!

        if (username != user.username) {
            val usernameExists = databaseManager.userDao.existsByUsername(username, sqlClient)

            if (usernameExists) {
                throw Errors(mapOf("username" to "EXISTS"))
            }
        }

        if (email != user.email) {
            val emailExists = databaseManager.userDao.isEmailExists(email, sqlClient)

            if (emailExists) {
                throw Errors(mapOf("email" to "EXISTS"))
            }
        }

        if (username != user.username) {
            databaseManager.userDao.setUsernameById(user.id, username, sqlClient)
        }

        if (email != user.email) {
            databaseManager.userDao.setEmailById(user.id, email, sqlClient)
        }

        if (localeCode != user.localeCode) {
            databaseManager.userDao.setLocaleCodeById(localeCode.ifBlank { null }, user.id, sqlClient)
        }

        if (newPassword.isNotEmpty()) {
            databaseManager.userDao.setPasswordById(user.id, newPassword, sqlClient)
        }

        if (playerId != userId) {
            databaseManager.userDao.updateEmailVerifyStatusById(playerId, isEmailVerified, sqlClient)
            databaseManager.userDao.updateCanCreateTicketStatusById(playerId, canCreateTicket, sqlClient)
        }

        val authUsername = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UpdatedPlayerLog(userId, authUsername, user.username), sqlClient)

        return Successful()
    }

    private fun validateForm(
        username: String,
        email: String,
        newPassword: String,
        newPasswordRepeat: String
    ) {
        val errors = mutableMapOf<String, Any>()

        if (username.isEmpty() || username.length > 16 || username.length < 3 || !username.matches(Regex("^[a-zA-Z0-9_]+\$")))
            errors["username"] = "INVALID"

        if (email.isEmpty() || !email.matches(Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}\$")))
            errors["email"] = "INVALID"

        if (newPassword.isNotEmpty() && (newPassword.length < 6 || newPassword.length > 128))
            errors["newPassword"] = "INVALID"

        if (newPasswordRepeat != newPassword)
            errors["newPasswordRepeat"] = "NOT_MATCH"

        if (errors.isNotEmpty()) {
            throw Errors(errors)
        }
    }
}