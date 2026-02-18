package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.MariaDBManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.delay

@Endpoint
class Step2InstallPortableDBAPI(
    private val mariaDBManager: MariaDBManager,
    private val configManager: ConfigManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/steps/2/install-portable", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result? {
        mariaDBManager.install()

        // Start the DB
        mariaDBManager.start()

        // Give it a moment to start
        delay(3000)

        mariaDBManager.createDefaultDatabase()

        val credentials = mariaDBManager.getCredentials()
        configManager.config.database.apply {
            type = "portable"
            host = credentials.getString("host")
            name = credentials.getString("dbName")
            username = credentials.getString("username")
            password = credentials.getString("password")
            prefix = credentials.getString("prefix")
        }
        configManager.saveConfig()

        context.end(
            Successful(
                mapOf(
                    "database" to credentials.map
                )
            ).encode()
        )

        return null
    }
}
