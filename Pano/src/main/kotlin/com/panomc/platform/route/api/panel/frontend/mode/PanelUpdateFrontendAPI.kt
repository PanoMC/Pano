package com.panomc.platform.route.api.panel.frontend.mode

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.frontend.FrontendDescriptor
import org.slf4j.Logger
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.ui.CustomAppInstaller
import com.panomc.platform.ui.DescriptorHostNotAllowed
import com.panomc.platform.ui.FrontendMode
import com.panomc.platform.ui.ThemeUiController
import com.panomc.platform.ui.UpstreamInvalidUrl
import com.panomc.platform.ui.UpstreamIsPano
import com.panomc.platform.ui.UpstreamTarget
import com.panomc.platform.ui.UpstreamUnreachable
import com.panomc.platform.util.UsageMode
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait
import java.net.URI

/**
 * Sets the front-end mode and the fields that go with it (doc 05 §8). Every field is optional and
 * an omitted one keeps its value. `devUrl` is the panel field "Theme dev server" under Development Mode.
 *
 * The new front-end is started before the old one is stopped; if it cannot start the answer is
 * `FRONTEND_START_FAILED` and nothing changed. `force: true` saves an `EXTERNAL` upstream that did
 * not answer the 5 second probe.
 */
@Endpoint
class PanelUpdateFrontendAPI(
    private val configManager: ConfigManager,
    private val uiManager: UIManager,
    private val themeUiController: ThemeUiController,
    private val customAppInstaller: CustomAppInstaller,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val vertx: Vertx,
    private val httpClient: HttpClient,
    private val frontendDescriptor: FrontendDescriptor,
    private val logger: Logger
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .optionalProperty("mode", stringSchema())
                        .optionalProperty("customAppId", stringSchema())
                        .optionalProperty("upstreamUrl", stringSchema())
                        .optionalProperty("siteUrl", stringSchema())
                        .optionalProperty("descriptorUrl", stringSchema())
                        .optionalProperty("devUrl", stringSchema())
                        .optionalProperty("force", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val body = getParameters(context).body().jsonObject
        val config = configManager.config
        val frontend = config.effectiveFrontend

        val mode = if (body.containsKey("mode")) {
            FrontendMode.parseOrNull(body.getString("mode")) ?: throw InvalidFields(mapOf("mode" to true))
        } else {
            frontend.parsedMode
        }

        val customApp = text(body, "customAppId", frontend.customApp)
        val upstream = text(body, "upstreamUrl", frontend.upstreamUrl)
        val siteUrl = text(body, "siteUrl", frontend.siteUrl).trimEnd('/')
        val descriptorUrl = text(body, "descriptorUrl", frontend.descriptorUrl)
        val devUrl = text(body, "devUrl", frontend.devUrl)
        val force = body.getBoolean("force", false)

        if (siteUrl.isNotEmpty() && hostOf(siteUrl) == null) {
            throw InvalidFields(mapOf("siteUrl" to true))
        }

        val devTarget = devUrl.takeIf { it.isNotEmpty() }?.let {
            val target = UpstreamTarget.parse(it) ?: throw UpstreamInvalidUrl("devUrl")

            if (isPano(target)) throw UpstreamIsPano("devUrl")

            target
        }

        val upstreamTarget = upstream.takeIf { it.isNotEmpty() }?.let { UpstreamTarget.parse(it) }

        if (upstream.isNotEmpty() && upstreamTarget == null && mode == FrontendMode.EXTERNAL) {
            throw UpstreamInvalidUrl()
        }

        if (descriptorUrl.isNotEmpty()) {
            val descriptorHost = hostOf(descriptorUrl) ?: throw InvalidFields(mapOf("descriptorUrl" to true))

            val allowed = listOfNotNull(upstreamTarget?.host, siteUrl.takeIf { it.isNotEmpty() }?.let { hostOf(it) })

            if (allowed.none { it.equals(descriptorHost, ignoreCase = true) }) {
                throw DescriptorHostNotAllowed()
            }
        }

        when (mode) {
            FrontendMode.CUSTOM_APP -> if (!customAppInstaller.exists(customApp)) throw NotFound()

            FrontendMode.EXTERNAL -> {
                val target = upstreamTarget ?: throw UpstreamInvalidUrl()

                if (isPano(target)) throw UpstreamIsPano()

                val alreadyServed = uiManager.isBoundTo(FrontendMode.EXTERNAL, UIManager.EXTERNAL_FRONTEND_ID, target.origin)

                if (!force && !alreadyServed && !target.probe(httpClient).coAwait()) {
                    throw UpstreamUnreachable()
                }
            }

            else -> Unit
        }

        // The plain fields are written first because applying THEME reads dev-url; they are put back
        // if the front-end cannot be started, so a refused change leaves the config as it was.
        val previous = JsonObject()
            .put("siteUrl", frontend.siteUrl)
            .put("descriptorUrl", frontend.descriptorUrl)
            .put("upstreamUrl", frontend.upstreamUrl)
            .put("devUrl", frontend.devUrl)

        frontend.siteUrl = siteUrl
        frontend.descriptorUrl = descriptorUrl
        frontend.devUrl = devTarget?.origin ?: ""

        config.frontend = frontend

        try {
            themeUiController.apply(mode, customApp, upstreamTarget?.origin ?: upstream)
        } catch (e: Throwable) {
            frontend.siteUrl = previous.getString("siteUrl")
            frontend.descriptorUrl = previous.getString("descriptorUrl")
            frontend.devUrl = previous.getString("devUrl")

            throw e
        }

        // apply() saves the config when it switches something; this covers the plain-field-only change.
        configManager.saveConfig()

        val sqlClient = getSqlClient()

        if (descriptorSourceChanged(mode, previous, frontend)) {
            refreshDescriptor(sqlClient)
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(ChangedFrontendModeLog(userId, username, mode.name), sqlClient)

        return Successful(frontendState(configManager, uiManager, themeUiController, customAppInstaller))
    }

    /** The descriptor is fetched again when the address it comes from (or the upstream it is derived from) changed. */
    private fun descriptorSourceChanged(mode: FrontendMode, previous: JsonObject, frontend: PanoConfig.Companion.FrontendConfig): Boolean =
        shouldRefreshDescriptor(mode, previous.getString("descriptorUrl"), previous.getString("upstreamUrl"), frontend.descriptorUrl, frontend.upstreamUrl)

    /** A descriptor that cannot be fetched must not undo the mode change: the panel's Refresh button shows the error. */
    private suspend fun refreshDescriptor(sqlClient: io.vertx.sqlclient.SqlClient) {
        try {
            frontendDescriptor.refresh(sqlClient)
        } catch (e: Throwable) {
            if (e is VirtualMachineError || e is kotlinx.coroutines.CancellationException) throw e

            logger.warn("The front-end descriptor could not be refreshed after saving: {}", e.message)
        }
    }

    private fun text(body: JsonObject, key: String, current: String): String =
        if (body.containsKey(key)) body.getString(key)?.trim().orEmpty() else current

    private fun hostOf(url: String): String? = try {
        val uri = URI(url)

        uri.host?.takeIf { it.isNotEmpty() && (uri.scheme == "http" || uri.scheme == "https") }
    } catch (_: Exception) {
        null
    }

    /** DNS can block, so the "is that this Pano" question runs off the event loop. */
    private suspend fun isPano(target: UpstreamTarget): Boolean =
        vertx.executeBlocking<Boolean> { target.pointsAtPano(configManager.config) }.coAwait()
}

/** Item 1 of CX-06 as a pure function: EXTERNAL and NONE modes refetch the descriptor when its source address changed. */
internal fun shouldRefreshDescriptor(
    mode: FrontendMode,
    oldDescriptorUrl: String,
    oldUpstreamUrl: String,
    newDescriptorUrl: String,
    newUpstreamUrl: String
): Boolean = (mode == FrontendMode.EXTERNAL || mode == FrontendMode.NONE) &&
    (oldDescriptorUrl != newDescriptorUrl || oldUpstreamUrl != newUpstreamUrl)
