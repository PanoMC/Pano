package com.panomc.platform.route.api.panel.software

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.MinecraftJavaVersions
import com.panomc.platform.server.software.ServerSoftwareCatalog
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Resolves one software version to the artifact a node would download.
 *
 * Mostly a preview for the wizard's confirm step; the install itself resolves again at the moment
 * it is started, so what the node receives is never a URL that has been sitting in a browser tab.
 */
@Endpoint
class PanelGetSoftwareVersionAPI(
    private val authProvider: AuthProvider,
    private val serverSoftwareCatalog: ServerSoftwareCatalog
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/software/:id/versions/:version", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .pathParameter(param("version", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").string
        val version = parameters.pathParameter("version").string

        val resolution = serverSoftwareCatalog.resolve(id, version) ?: throw NotExists()

        // `java` is what the wizard needs to say which Java "Automatic" will use and whether the
        // node will have to download it (SM-63, §2.4.28).
        return Successful(
            resolution.toJsonObject()
                .put("java", javaRange(version, resolution.javaMajor))
                .map
        )
    }

    companion object {
        /**
         * `{ minimum, maximum }` for [version], out of Pano's copy of the ladder.
         *
         * The minimum is raised to what the provider itself reported, when it reported more: the
         * Spigot hub knows the JDK a revision was built for, and the node raises its automatic
         * choice to the jar's own requirement the same way, so the two land on the same major.
         * `maximum` is null when the version has no known ceiling.
         */
        fun javaRange(version: String?, reported: Int?): JsonObject {
            val minimum = maxOf(MinecraftJavaVersions.minimumFor(version), reported ?: 0)

            return JsonObject()
                .put("minimum", minimum)
                .put("maximum", MinecraftJavaVersions.maximumFor(version)?.takeIf { it >= minimum })
        }
    }
}
