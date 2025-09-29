package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.AppConstants.THEME_SETTINS_FILE_UPLOAD_FOLDER
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import java.io.File
import java.util.*

@Endpoint
class PanelUpdateThemeSettingsAPI(
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/theme/settings", RouteType.PUT))

    companion object {
        const val THEME_SETTINGS = "theme_settings"
    }

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(100 * 1024 * 1024) // 100MB

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.multipartFormData(
                    objectSchema()
                        .requiredProperty(
                            "settings", objectSchema()
                        )
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        if (!uiManager.activatedUIList.containsKey(Type.THEME_UI)) {
            throw NoPermission()
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val fileUploads = context.fileUploads()

        val newSettings = data.getJsonObject("settings")

        val folder = configManager.config
            .fileUploadsFolder + File.separator + THEME_SETTINS_FILE_UPLOAD_FOLDER + File.separator
        val folderFile = File(folder)

        if (!folderFile.exists() || !folderFile.isDirectory) {
            folderFile.deleteRecursively()
            folderFile.mkdirs()
        }

        val newFiles = mutableMapOf<String, MutableList<String>>()

        if (fileUploads.isNotEmpty()) {
            val fileUploadsByName = fileUploads.groupBy { it.name() }

            fileUploadsByName.forEach {
                it.value.forEach { uploadedFile ->
                    val containsExt = uploadedFile.uploadedFileName().contains(".")
                    val split = uploadedFile.uploadedFileName().split(".")

                    val newFileName = UUID.randomUUID().toString() + if (containsExt) "." + split.last() else ""
                    val newPath = folder + newFileName
                    val uploadedFileObj = File(uploadedFile.uploadedFileName())

                    uploadedFileObj.copyTo(File(newPath), true)
                    uploadedFileObj.delete()

                    if (newFiles[it.key] == null) {
                        newFiles[it.key] = mutableListOf()
                    }

                    newFiles[it.key]!!.add(newFileName)
                }
            }

            val newSettingsFiles = newSettings.getJsonObject("files") ?: JsonObject()

            newFiles.forEach {
                var list = newSettingsFiles.getJsonArray(it.key)

                if (list == null) {
                    val jsonArray = JsonArray()
                    newSettingsFiles.put(it.key, jsonArray)
                    list = jsonArray
                }

                it.value.forEach { fileName ->
                    list.add(fileName)
                }
            }

            newSettings.put("files", newSettingsFiles)
        }

        val removeFiles = newSettings.getJsonArray("remove-files")

        if (removeFiles != null && !removeFiles.isEmpty) {
            val newSettingsFiles = newSettings.getJsonObject("files")

            removeFiles.forEach { fileName ->
                val file = File(folder + fileName)

                if (file.exists()) {
                    file.deleteRecursively()
                }

                if (newSettingsFiles != null) {
                    newSettingsFiles.forEach {
                        val list = it.value as JsonArray

                        if (list.contains(fileName)) {
                            list.remove(fileName)
                        }

                        if (list.isEmpty) {
                            newSettingsFiles.remove(it.toString())
                        }
                    }
                }
            }

            newSettings.remove("remove-files")
        }

        val sqlClient = getSqlClient()

        val existingSettingsProperty = databaseManager.systemPropertyDao.getByOption(THEME_SETTINGS, sqlClient)

        if (existingSettingsProperty != null) {
            val settingsProperty = JsonObject(existingSettingsProperty.value)
            val currentThemeSettings = settingsProperty.getJsonObject(uiManager.activeTheme)

            if (currentThemeSettings != null) {
                val existingFiles = currentThemeSettings.getJsonObject("files")

                if (existingFiles != null) {
                    val newSettingsFiles = newSettings.getJsonObject("files") ?: JsonObject()

                    existingFiles.filter { !(newSettingsFiles.getJsonArray(it.key) ?: JsonArray()).contains(it.value) }
                        .forEach {
                            JsonArray(it.value.toString()).forEach { fileName ->
                                val file = File(folder + fileName)

                                if (file.exists()) {
                                    file.deleteRecursively()
                                }
                            }
                        }
                }
            }

            settingsProperty.put(uiManager.activeTheme, newSettings)

            databaseManager.systemPropertyDao.update(THEME_SETTINGS, settingsProperty.encode(), sqlClient)

            return Successful(newSettings.map)
        }

        val newSettingsProperty = JsonObject()

        newSettingsProperty.put(uiManager.activeTheme, newSettings)

        databaseManager.systemPropertyDao.add(
            SystemProperty(
                option = THEME_SETTINGS,
                value = newSettingsProperty.encode()
            ), sqlClient
        )

        return Successful(newSettings.map)
    }
}