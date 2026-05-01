package com.panomc.platform.route.api.panel.plugins


import com.panomc.platform.PanoApiManager
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.DisabledPluginLog
import com.panomc.platform.auth.panel.log.EnabledPluginLog
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.LicensePanelView
import com.panomc.platform.license.isPluginStartupBlockedByLicense
import com.panomc.platform.license.panelPluginStartupErrorText
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import org.pf4j.PluginState
import org.slf4j.LoggerFactory

@Endpoint
class PanelUpdatePluginAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val databaseManager: DatabaseManager,
    private val licenseManager: LicenseManager,
    private val configManager: ConfigManager,
    private val panoApiManager: PanoApiManager
) : PanelApi() {

    private val log = LoggerFactory.getLogger(javaClass)

    override val paths = listOf(Path("/api/panel/plugins/:pluginId", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty("status", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val pluginId = parameters.pathParameter("pluginId").string

        val pluginWrapper = pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId }

        if (pluginWrapper == null) {
            throw NotFound()
        }

        val status = data.getBoolean("status")

        if (status != null) {
            try {
                if (status) {
                    if (pluginWrapper.pluginState == PluginState.STARTED) {
                        return Successful()
                    }

                    val licenseFields = LicensePanelView.buildLicenseFields(
                        pluginId = pluginId,
                        licenseManager = licenseManager,
                        configManager = configManager,
                        isPanoConnected = panoApiManager.isConnected()
                    )
                    val premium = licenseFields["premium"] as Boolean
                    val licensed = licenseFields["licensed"] as Boolean
                    if (premium && !licensed) {
                        log.warn(
                            "Panel ENABLE '{}' rejected: premium addon without valid license (licensed=false)",
                            pluginId,
                        )
                        return Successful(
                            mapOf(
                                "status" to PluginState.FAILED,
                                "startupBlockedByLicense" to true,
                                "error" to null
                            )
                        )
                    }

                    pluginManager.enablePlugin(pluginId)
                    pluginManager.startPlugin(pluginId)

                    val sqlClient = databaseManager.getSqlClient()
                    val userId = authProvider.getUserIdFromRoutingContext(context)
                    val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                    databaseManager.panelActivityLogDao.add(
                        EnabledPluginLog(
                            userId,
                            username,
                            pluginId,
                        ), sqlClient
                    )
                }

                if (!status) {
                    if (pluginWrapper.pluginState != PluginState.STARTED) {
                        return Successful()
                    }

                    val dependents =
                        pluginManager.plugins.filter { it.pluginState != PluginState.DISABLED && it.descriptor.dependencies.any { it.pluginId == pluginId && !it.isOptional } }
                            .map { it.pluginId }

                    dependents.forEach {
                        pluginManager.stopPlugin(it)
                        pluginManager.disablePlugin(it)
                    }

                    pluginManager.stopPlugin(pluginId)
                    pluginManager.disablePlugin(pluginId)

                    val sqlClient = databaseManager.getSqlClient()
                    val userId = authProvider.getUserIdFromRoutingContext(context)
                    val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                    databaseManager.panelActivityLogDao.add(
                        DisabledPluginLog(
                            userId,
                            username,
                            pluginId,
                        ), sqlClient
                    )
                }
            } catch (t: Throwable) {
                log.error(
                    "Panel plugin '{}' state update threw {} — {}",
                    pluginId,
                    t.javaClass.name,
                    t.message,
                    t,
                )
                val wrapper = pluginManager.getPlugin(pluginId)
                wrapper.failedException = t
                wrapper.pluginState = PluginState.FAILED

                return Successful(
                    mapOf(
                        "status" to wrapper.pluginState,
                        "error" to wrapper.failedException.panelPluginStartupErrorText(),
                        "startupBlockedByLicense" to wrapper.failedException.isPluginStartupBlockedByLicense()
                    )
                )
            }
        }

        return Successful(
            mapOf(
                "status" to pluginWrapper.pluginState
            )
        )
    }
}