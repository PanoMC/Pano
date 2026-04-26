package com.panomc.platform.route.api.panel.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.BannedIpLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.BannedIp
import com.panomc.platform.error.AlreadyIpBanned
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InvalidIpAddress
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import java.net.InetAddress

@Endpoint
class PanelBanIpAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/banned-ips", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("ip", stringSchema())
                        .optionalProperty("banMessage", stringSchema())
                        .optionalProperty("duration", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val rawIp = data.getString("ip")
        val banMessage = data.getString("banMessage")
        val duration = data.getLong("duration")

        if ((banMessage?.length ?: 0) > 255) {
            throw BadRequest()
        }

        val ip = normalizeAndValidateIp(rawIp ?: "")

        val sqlClient = getSqlClient()
        val authUserId = authProvider.getUserIdFromRoutingContext(context)
        val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

        if (databaseManager.bannedIpDao.getActiveByIp(ip, sqlClient) != null) {
            throw AlreadyIpBanned()
        }

        val now = System.currentTimeMillis()

        val reason = banMessage?.trim()?.take(255)?.takeIf { it.isNotEmpty() }

        databaseManager.bannedIpDao.upsert(
            BannedIp(
                ip = ip,
                reason = reason,
                bannedUntil = duration,
                bannedBy = authUsername,
                source = "PANEL",
                bannedBySystem = false,
                createdAt = now,
                updatedAt = now
            ),
            sqlClient
        )

        databaseManager.panelActivityLogDao.add(
            BannedIpLog(
                userId = authUserId,
                username = authUsername,
                ip = ip,
                reason = reason ?: "",
                duration = duration ?: 0L,
                permanent = duration == null
            ),
            sqlClient
        )

        return Successful()
    }

    private fun normalizeAndValidateIp(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw InvalidIpAddress()
        }
        if (trimmed.length > 45) {
            throw InvalidIpAddress()
        }
        return try {
            InetAddress.getByName(trimmed).hostAddress
        } catch (e: Exception) {
            throw InvalidIpAddress()
        }
    }
}
