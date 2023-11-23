package com.panomc.platform.route.api.panel.player


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
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
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdatePlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:id", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .property("username", stringSchema())
                        .property("email", stringSchema())
                        .property("newPassword", stringSchema())
                        .property("newPasswordRepeat", stringSchema())
                        .property("isEmailVerified", booleanSchema())
                        .property("canCreateTicket", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val username = data.getString("username")
        val email = data.getString("email")
        val newPassword = data.getString("newPassword")
        val newPasswordRepeat = data.getString("newPasswordRepeat")
        val isEmailVerified = data.getBoolean("isEmailVerified")
        val canCreateTicket = data.getBoolean("canCreateTicket")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val hasManagePlayerPermission = authProvider.hasPermission(userId, PanelPermission.MANAGE_PLAYERS, context)

        if (!hasManagePlayerPermission && id != userId) {
            throw NoPermission()
        }

        validateForm(username, email, newPassword, newPasswordRepeat)

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsById(id, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(id, sqlClient)!!

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlClient)!!

        val isAdmin = context.get<Boolean>("isAdmin") ?: false

        if (userPermissionGroup.name == "admin" && !isAdmin) {
            throw NoPermission()
        }

        val user = databaseManager.userDao.getById(id, sqlClient)!!

        if (username != user.username) {
            val usernameExists = databaseManager.userDao.existsByUsername(username, sqlClient)

            if (usernameExists) {
                throw Errors(mapOf("username" to "EXISTS"))
            }

            databaseManager.userDao.setUsernameById(user.id, username, sqlClient)
        }

        if (email != user.email) {
            val emailExists = databaseManager.userDao.isEmailExists(email, sqlClient)

            if (emailExists) {
                throw Errors(mapOf("username" to "EXISTS"))
            }

            databaseManager.userDao.setEmailById(user.id, username, sqlClient)
        }

        if (newPassword.isNotEmpty()) {
            databaseManager.userDao.setPasswordById(user.id, newPassword, sqlClient)
        }

        if (id != userId) {
            databaseManager.userDao.updateEmailVerifyStatusById(id, isEmailVerified, sqlClient)
            databaseManager.userDao.updateCanCreateTicketStatusById(id, canCreateTicket, sqlClient)
        }

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