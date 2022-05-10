package com.panomc.platform.route.template

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Template
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.templ.handlebars.HandlebarsTemplateEngine

@Endpoint
class IndexTemplate(
    private val templateEngine: HandlebarsTemplateEngine,
    private val setupManager: SetupManager,
    private val authProvider: AuthProvider
) : Template() {
    private val mHotLinks = mapOf<String, String>()

    override val routes = arrayListOf("/*")

    override val order = 999

    override fun getHandler() = Handler<RoutingContext> { context ->
        val response = context.response()
        val normalisedPath = context.normalizedPath()

        if (!mHotLinks[normalisedPath.lowercase()].isNullOrEmpty())
            response.putHeader(
                "location",
                mHotLinks[normalisedPath.lowercase()]
            ).setStatusCode(302).end()
        else if (normalisedPath.startsWith("/panel") && setupManager.isSetupDone())
            authProvider.isLoggedIn(context) { isLoggedIn ->
                if (!isLoggedIn) {
                    handleTemplateEngine(context, false)

                    return@isLoggedIn
                }

                authProvider.hasAccessPanel(context) { hasAccess, _ ->
                    handleTemplateEngine(context, hasAccess)
                }
            }
        else
            handleTemplateEngine(context)
    }

    private fun handleTemplateEngine(context: RoutingContext, hasAccess: Boolean = false) {
        val response = context.response()

        templateEngine
            .render(
                JsonObject()
                    .put("is_panel", hasAccess)
                    .put("is_setup", !setupManager.isSetupDone()),
                "src/main/resources/index-template.hbs"
            ) { res ->
                if (res.succeeded())
                    response.end(res.result())
                else
                    response.end("Hello to Pano web platform!")
            }

    }
}