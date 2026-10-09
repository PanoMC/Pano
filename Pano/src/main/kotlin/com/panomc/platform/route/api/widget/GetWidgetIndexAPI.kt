package com.panomc.platform.route.api.widget

import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.plugin.PluginNamespace
import com.panomc.platform.ui.WidgetRuntime
import com.panomc.platform.ui.WidgetRuntimeInfo
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import com.panomc.platform.schema.EndpointDoc
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.ConcurrentHashMap

/**
 * `GET /api/v1/widgets/index.json` -- what the loader needs to know (doc 06 section 3.3):
 *
 * ```
 * { runtime: { svelte, hash }, site: { name, url, locale },
 *   widgets: { "pano-market-goal": { pluginId, ns, module, attrs, session, controllers, styles: { fallback, hash, icons } } } }
 * ```
 *
 * One entry per widget tag (`pano-<ns>-<tag>`) of the active plugins whose package has a `widgets/widgets.json`
 * (`format: 1`) built for the runtime's Svelte version. A plugin built for another version is left out and logged
 * once; a plugin refused by the namespace rule is not active for the site, so it is absent too. 404 without a
 * runtime. Cached for 5 minutes.
 *
 * `module` is relative to the plugin's `_/ui/widgets/`, `styles.fallback` (with `?v=<hash>`) relative to `_/ui/`,
 * and `styles.icons` (only when the plugin uses icons) is the runtime's icon sheet, written relative to `_/ui/` as
 * well so it resolves behind a proxy prefix. `controllers` is true when the package carries controllers.
 */
@Endpoint
class GetWidgetIndexAPI internal constructor(
    private val widgetRuntime: WidgetRuntime,
    private val pluginUiManager: PluginUiManager,
    private val pluginManager: PluginManager,
    private val site: () -> Map<String, Any?>
) : Api() {
    @Autowired
    constructor(
        widgetRuntime: WidgetRuntime,
        pluginUiManager: PluginUiManager,
        pluginManager: PluginManager,
        configManager: ConfigManager,
        frontendUrlMap: FrontendUrlMap
    ) : this(widgetRuntime, pluginUiManager, pluginManager, {
        mapOf(
            "name" to configManager.config.websiteName,
            "url" to frontendUrlMap.siteUrl(),
            "locale" to configManager.config.locale
        )
    })

    override val paths = listOf(Path("/widgets/index.json", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override val doc = EndpointDoc(
        summary = "What the widget loader needs: the runtime pin, the site, and every widget tag of the active plugins.",
        tag = "widgets",
        response = objectSchema()
            .requiredProperty("runtime", objectSchema().requiredProperty("svelte", stringSchema()).requiredProperty("hash", stringSchema()))
            .requiredProperty(
                "site",
                objectSchema()
                    .requiredProperty("name", stringSchema())
                    .requiredProperty("url", stringSchema())
                    .requiredProperty("locale", stringSchema())
            )
            .requiredProperty("widgets", objectSchema().additionalProperties(objectSchema())),
        errors = listOf(NotFound::class)
    )

    private val logger = LoggerFactory.getLogger(GetWidgetIndexAPI::class.java)

    // Plugins already logged for a mismatching pin: the index is read often, the line once per plugin and pin.
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        val body = context.vertx().executeBlocking<JsonObject?> { build() }.coAwait() ?: throw NotFound()

        val response = context.response()
        val bytes = body.encode().toByteArray(Charsets.UTF_8)

        response.putHeader("Content-Type", "application/json")
        response.putHeader("X-Content-Type-Options", "nosniff")
        response.putHeader("Cache-Control", WidgetFiles.FIVE_MINUTES)
        response.putHeader("Content-Length", bytes.size.toString())
        response.end(Buffer.buffer(bytes))

        return null
    }

    /** The index document, or null without a runtime. Blocking. */
    internal fun build(): JsonObject? {
        val runtime = widgetRuntime.info() ?: return null
        val widgets = JsonObject()

        pluginUiManager.getActiveRegisteredPlugins(pluginManager)
            .map { it.first }
            .sortedBy { it.pluginId }
            .forEach { plugin ->
                try {
                    entries(plugin, runtime).forEach { (tag, entry) ->
                        val taken = widgets.getJsonObject(tag)

                        if (taken != null) {
                            logger.warn(
                                "Widget tag '{}' of plugin '{}' is already taken by '{}'; it is left out.",
                                tag, plugin.pluginId, taken.getString("pluginId")
                            )
                        } else {
                            widgets.put(tag, entry)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("The widgets of plugin '{}' are left out: {}", plugin.pluginId, e.message)
                }
            }

        return JsonObject()
            .put("runtime", JsonObject().put("svelte", runtime.svelte).put("hash", runtime.hash))
            .put("site", JsonObject(site()))
            .put("widgets", widgets)
    }

    private fun entries(plugin: PanoPlugin, runtime: WidgetRuntimeInfo): Map<String, JsonObject> {
        val declared = pluginUiManager.readPackageEntry(plugin, WIDGETS_FILE) ?: return emptyMap()

        val file = try {
            JsonObject(String(declared.bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("Plugin '{}' has an unreadable {}; its widgets are left out.", plugin.pluginId, WIDGETS_FILE)

            return emptyMap()
        }

        if (file.getValue("format") != 1) {
            logger.warn("Plugin '{}' has a widgets.json of format {}, only 1 is known; its widgets are left out.", plugin.pluginId, file.getValue("format"))

            return emptyMap()
        }

        val pin = file.getValue("svelte") as? String

        if (pin != runtime.svelte) {
            if (reported.add("${plugin.pluginId}@$pin@${runtime.svelte}")) {
                logger.warn(
                    "Widgets of plugin '{}' were built for svelte {}, this Pano's widget runtime has {}; they are left out until the plugin is rebuilt.",
                    plugin.pluginId, pin, runtime.svelte
                )
            }

            return emptyMap()
        }

        val manifest = pluginUiManager.manifest(plugin)
        val ns = PluginNamespace.ofManifest(plugin.pluginId, manifest)
        val styles = styles(manifest, pluginUiManager.readPackageEntry(plugin, OWN_SHEET))
        val controllers = !(manifest?.getValue("controllers") as? String).isNullOrBlank()
        val result = linkedMapOf<String, JsonObject>()

        (file.getValue("widgets") as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { widget ->
            val tag = widget.getValue("tag") as? String
            val module = widget.getValue("module") as? String

            if (tag == null || !TAG.matches(tag) || module == null || !MODULE.matches(module)) {
                logger.warn("Plugin '{}' lists a widget with an unusable tag or module ({}); it is left out.", plugin.pluginId, widget.encode())

                return@forEach
            }

            val element = "pano-$ns-$tag"

            if (result.containsKey(element)) {
                logger.warn("Plugin '{}' lists the widget tag '{}' twice; the second is left out.", plugin.pluginId, element)

                return@forEach
            }

            result[element] = JsonObject()
                .put("pluginId", plugin.pluginId)
                .put("ns", ns)
                .put("module", module)
                .put("attrs", (widget.getValue("attrs") as? JsonArray) ?: JsonArray())
                .put("session", (widget.getValue("session") as? String) ?: "none")
                .put("controllers", controllers)
                .put("styles", styles)
        }

        return result
    }

    private fun styles(manifest: JsonObject?, own: com.panomc.platform.PackageEntry?): JsonObject {
        val declared = manifest?.getValue("styles") as? JsonObject
        val fallback = declared?.getValue("fallback") as? String
        val hash = declared?.getValue("hash") as? String
        val icons = declared?.getValue("icons") == true
        val styles = JsonObject()

        if (!fallback.isNullOrBlank() && SAFE_PATH.matches(fallback)) {
            styles.put("fallback", if (hash.isNullOrBlank()) fallback else "$fallback?v=$hash")
        }

        if (!hash.isNullOrBlank()) styles.put("hash", hash)
        if (icons) styles.put("icons", ICONS_SHEET)

        // The plugin's own sheet for a widget's shadow root, relative to `_/ui/` like the fallback sheet.
        if (own != null) styles.put("own", OWN_SHEET)

        return styles
    }

    companion object {
        const val WIDGETS_FILE = "widgets/widgets.json"

        const val OWN_SHEET = "client/plugin.css"

        // The icon sheet of the runtime, from a plugin's `_/ui/` folder (four levels up is the API root).
        const val ICONS_SHEET = "../../../../widgets/runtime/css/pano-fallback-icons.css"

        private val TAG = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val MODULE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.m?js$")
        private val SAFE_PATH = Regex("^[A-Za-z0-9_][A-Za-z0-9._/-]*$")
    }
}
