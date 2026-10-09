package com.panomc.platform.route.api.panel.compatibility

import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.gate.CompatibilityReconciler
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `POST /api/v1/panel/compatibility/reconcile` -- runs gate 0 again (the "Retry" of the Compatibility card): asks the
 * store for compatible versions of everything still refused and installs the hits. Answers with the refreshed
 * compatibility body. A run that leaves something refused sends the admins one notification. Runs are serialized: a
 * second call waits for the first and then reconciles what is left.
 */
@Endpoint
class PanelReconcileCompatibilityAPI(
    private val authProvider: AuthProvider,
    private val reconciler: CompatibilityReconciler,
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager
) : PanelApi() {
    override val paths = listOf(Path("/compatibility/reconcile", RouteType.POST))

    override fun isAllowedInDemo(method: HttpMethod): Boolean = false

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        PanelGetCompatibilityAPI.requireCompatibilityAccess(authProvider, context)

        reconciler.reconcile(atBoot = false)
        reconciler.announceRefusals()

        return Successful(PanelGetCompatibilityAPI.payload(databaseManager, pluginManager, reconciler))
    }
}
