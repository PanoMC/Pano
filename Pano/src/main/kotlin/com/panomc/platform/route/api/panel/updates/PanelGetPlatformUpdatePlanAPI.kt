package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.gate.CompatibilityReconciler
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.compatibility.CompatibilityPayload
import com.panomc.platform.update.PanoCompatibilityStore
import com.panomc.platform.update.PlanTarget
import com.panomc.platform.update.ReleaseApiLevel
import com.panomc.platform.update.UpdatePlan
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/panel/updates/platform/plan` -- gate 2 of the compatibility gate (doc 04 section 7): what the platform
 * update that was found would do to the installed plugins and themes, so the panel can show it before the update
 * button is enabled.
 *
 * ```
 * { "target": { "version", "apiLevel", "minApiLevel" },
 *   "resources": [ { "id", "type", "installedVersion", "verdict": "COMPATIBLE|UPDATE|DISABLE", "updateVersionId"? } ],
 *   "agents": [ ... ] }
 * ```
 *
 * No update found: `target` is null and the lists are empty. A target whose levels are unknown reads `COMPATIBLE`
 * for every resource. Nothing is staged; after the restart the boot reconcile installs the `UPDATE` rows.
 */
@Endpoint
class PanelGetPlatformUpdatePlanAPI(
    private val authProvider: AuthProvider,
    private val updateManager: UpdateManager,
    private val reconciler: CompatibilityReconciler,
    private val store: PanoCompatibilityStore,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/updates/platform/plan", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val info = updateManager.getPlatformUpdateInfo()

        if (info == null) {
            return Successful(
                mapOf(
                    "target" to null,
                    "resources" to emptyList<Any>(),
                    "agents" to emptyList<Any>(),
                    "storeReachable" to null
                )
            )
        }

        val target = PlanTarget(
            version = info.getString("version"),
            apiLevel = ReleaseApiLevel.level(info.getValue("apiLevel")),
            minApiLevel = ReleaseApiLevel.level(info.getValue("minApiLevel"))
        )

        val plan = UpdatePlan.build(target, reconciler.installed(), store)

        val sqlClient = databaseManager.getSqlClient()
        val agents = CompatibilityPayload.agents(
            databaseManager.serverDao.getAllByPermissionGranted(sqlClient),
            databaseManager.nodeDao.getAll(sqlClient),
            CompatibilityPayload.localNodeManaged(configManager.config)
        )

        return Successful(plan.toMap(agents))
    }
}
