package com.panomc.platform.model

import com.panomc.platform.config.ConfigManager
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import org.springframework.beans.factory.annotation.Autowired
import java.io.File

abstract class Route {
    @Autowired
    private lateinit var configManager: ConfigManager

    open val order = 1

    abstract val paths: List<Path>

    abstract fun getHandler(): Handler<RoutingContext>

    companion object {
        private val allowedHeaders = setOf(
            "x-requested-with",
            "Access-Control-Allow-Origin",
            "origin",
            "Content-Type",
            "accept",
            "X-PINGARUNER",
            "x-csrf-token"
        )

        private val allowedMethods = setOf<HttpMethod>(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.OPTIONS,
            HttpMethod.DELETE,
            HttpMethod.PATCH,
            HttpMethod.PUT
        )
    }

    open fun corsHandler(): Handler<RoutingContext>? = CorsHandler.create(".*.")
        .allowCredentials(true)
        .allowedHeaders(allowedHeaders)
        .allowedMethods(allowedMethods)

    open fun bodyHandler(): Handler<RoutingContext>? = BodyHandler.create()
        .setDeleteUploadedFilesOnEnd(true)
        .setUploadsDirectory(configManager.config.fileUploadsFolder + File.separator + "temp")

    open fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler? =
        ValidationHandlerBuilder.create(schemaParser).build()

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