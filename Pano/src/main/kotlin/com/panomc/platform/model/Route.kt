package com.panomc.platform.model

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.route.Mount
import com.panomc.platform.route.Namespace
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import java.io.File

/** Whether a route accepts browser mutations from a foreign origin; see [Route.browserAccess]. */
enum class BrowserAccess { SAME_SITE, ANY_ORIGIN }

abstract class Route {
    private val configManager by lazy {
        applicationContext.getBean(ConfigManager::class.java)
    }

    open val order = 1

    /**
     * The usage modes this route exists in. Anything narrower than [UsageMode.ALL] makes
     * [com.panomc.platform.route.RouterProvider] put a gate in front of it that answers 404 while
     * the install runs in another mode -- read per request, so switching the mode in the panel
     * takes effect without a restart. The website's own features declare
     * [UsageMode.WITH_WEBSITE]: a SERVERS install has no posts, tickets or themes to serve.
     */
    open val usageModes: Set<UsageMode> = UsageMode.ALL

    /**
     * How [paths] are mounted: [Mount.API] puts them under `/api/v1` (see
     * [com.panomc.platform.route.ApiPaths]), [Mount.ROOT] serves them verbatim (templates).
     */
    open val mount: Mount = Mount.ROOT

    /** Which API namespace [paths] are relative to; [Namespace.PANEL] adds `/panel` after `/api/v1`. */
    open val namespace: Namespace = Namespace.SITE

    /**
     * Who may POST (or PUT, PATCH, DELETE) to this route from a browser page of another origin (doc 05 §4).
     * [BrowserAccess.SAME_SITE] (the default): an unsafe request whose `Origin` is foreign is refused with
     * `403 ORIGIN_NOT_ALLOWED` unless it carries a front-end key. [BrowserAccess.ANY_ORIGIN] is for third-party
     * posts (a payment return or notify route, an OAuth `form_post` callback) and lifts that one gate.
     */
    open val browserAccess: BrowserAccess = BrowserAccess.SAME_SITE

    abstract val paths: List<Path>

    abstract fun getHandler(): Handler<RoutingContext>

    open fun bodyHandler(): Handler<RoutingContext>? = BodyHandler.create()
        .setDeleteUploadedFilesOnEnd(true)
        .setUploadsDirectory(configManager.config.fileUploadsFolder + File.separator + "temp")

    open fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? =
        ValidationHandlerBuilder.create(schemaRepository).build()

    open fun getFailureHandler(): Handler<RoutingContext> = Handler { request ->
        val response = request.response()

        if (response.ended()) {
            return@Handler
        }

        response.end()
    }

    enum class Type {
        THEME_UI,
        PANEL_UI,
        SETUP_UI
    }
}