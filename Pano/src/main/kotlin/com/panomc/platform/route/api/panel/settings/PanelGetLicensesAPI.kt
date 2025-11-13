package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

@Endpoint
class PanelGetLicensesAPI(
    private val webClient: WebClient,
    private val uiManager: UIManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/licenses/oss", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    private val localLicensesJson = "licenses.json"

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val panelLicenses = getLicences(Type.PANEL_UI)
        val themeLicenses = getLicences(Type.THEME_UI)

        val licenses = mutableListOf<License>()
        licenses.addAll(panelLicenses)
        licenses.addAll(themeLicenses)

        // Read local licenses.json from resources
        val localLicenses = getLocalLicenses()
        licenses.addAll(localLicenses)

        return Successful(
            mutableMapOf(
                "data" to licenses,
                "meta" to mapOf(
                    "totalCount" to licenses.count(),
                )
            )
        )
    }

    private data class License(
        val name: String,
        val version: String,
        val license: String,
        val licenseText: String? = null,
        val repository: String? = null,
        val homepage: String? = null,
        val author: String? = null
    )

    private suspend fun getLicences(routeType: Type): List<License> {
        // Get licenses from UI (runtime SvelteKit applications)
        val activatedUI = uiManager.activatedUIList[routeType]!!
        val panelPrefix = if (routeType == Type.PANEL_UI) "/panel" else ""
        val apiPrefix = if (routeType == Type.PANEL_UI) "panel" else "theme"
        val url =
            "http://${activatedUI.host}:${activatedUI.port}${panelPrefix}/$apiPrefix-api/licenses.json"

        return getLicensesFromUrl(url)
    }

    private fun getLocalLicenses(): List<License> {
        return try {
            val inputStream = Thread.currentThread().contextClassLoader
                .getResourceAsStream(localLicensesJson)

            if (inputStream == null) {
                return emptyList()
            }

            val bodyString = inputStream.bufferedReader().use { it.readText() }.trim()

            // Check if response is empty object or empty array
            if (bodyString == "{}" || bodyString == "[]" || bodyString.isEmpty()) {
                return emptyList()
            }

            // Try to parse as JSON array
            val body = JsonArray(bodyString)

            (0 until body.size()).mapNotNull { index ->
                try {
                    val element = body.getJsonObject(index)
                    val repository = element.getString("repository")?.takeIf { !it.isNullOrBlank() }
                    val homepage = element.getString("homepage")?.takeIf { !it.isNullOrBlank() }
                        ?: repository // Use repository as homepage if homepage is null

                    License(
                        name = element.getString("name") ?: "",
                        version = element.getString("version") ?: "",
                        license = element.getString("license") ?: "Unknown",
                        licenseText = element.getString("licenseText")?.takeIf { !it.isNullOrBlank() },
                        repository = repository,
                        homepage = homepage,
                        author = element.getString("author")?.takeIf { !it.isNullOrBlank() }
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun getLicensesFromUrl(url: String): List<License> {
        return try {
            val response = webClient.getAbs(url).send().coAwait()

            if (response.statusCode() == 404) {
                return emptyList()
            }

            val bodyString = response.bodyAsString()?.trim() ?: ""

            // Check if response is empty object or empty array
            if (bodyString == "{}" || bodyString == "[]" || bodyString.isEmpty()) {
                return emptyList()
            }

            // Try to parse as JSON array
            val body = JsonArray(bodyString)

            (0 until body.size()).mapNotNull { index ->
                try {
                    val element = body.getJsonObject(index)
                    val repository = element.getString("repository")?.takeIf { !it.isNullOrBlank() }
                    val homepage = element.getString("homepage")?.takeIf { !it.isNullOrBlank() }
                        ?: repository // Use repository as homepage if homepage is null

                    License(
                        name = element.getString("name") ?: "",
                        version = element.getString("version") ?: "",
                        license = element.getString("license") ?: "Unknown",
                        licenseText = element.getString("licenseText")?.takeIf { !it.isNullOrBlank() },
                        repository = repository,
                        homepage = homepage,
                        author = element.getString("author")?.takeIf { !it.isNullOrBlank() }
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}