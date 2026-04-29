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
import io.vertx.core.Vertx
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
import io.vertx.kotlin.coroutines.coAwait
import java.io.File
import java.util.*

@Endpoint
class PanelUpdateThemeSettingsAPI(
    private val vertx: Vertx,
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

            vertx.executeBlocking<Unit> {
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
            }.coAwait()

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
                    newSettingsFiles.forEach { (key, value) ->
                        val list = value as JsonArray

                        if (list.contains(fileName)) {
                            list.remove(fileName)
                        }

                        if (list.isEmpty) {
                            newSettingsFiles.remove(key)
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

            if (currentThemeSettings != null && !newSettings.isEmpty) {
                mergeFilesFromCurrentTheme(newSettings, currentThemeSettings)
            }

            if (currentThemeSettings != null) {
                val existingFiles = currentThemeSettings.getJsonObject("files")
                if (existingFiles != null) {
                    val newSettingsFiles = newSettings.getJsonObject("files") ?: JsonObject()
                    deleteReplacedThemeFiles(folder, existingFiles, newSettingsFiles)
                }
            }

            newSettings.getJsonObject("files")?.let { stripEmptyThemeFileKeys(it) }

            settingsProperty.put(uiManager.activeTheme, newSettings)

            databaseManager.systemPropertyDao.update(THEME_SETTINGS, settingsProperty.encode(), sqlClient)

            return Successful(newSettings.map)
        }

        newSettings.getJsonObject("files")?.let { stripEmptyThemeFileKeys(it) }

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

    private fun stripEmptyThemeFileKeys(files: JsonObject) {
        for (k in files.fieldNames().toList()) {
            if (fileNamesInThemeFileValue(files.getValue(k)).isEmpty()) {
                files.remove(k)
            }
        }
    }

    private fun fileNamesInThemeFileValue(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> if (value.isEmpty()) emptyList() else listOf(value)
        is JsonArray -> (0 until value.size()).map { value.getValue(it).toString() }
        else -> emptyList()
    }

    /**
     * Copy missing "files" keys from the current DB state so a partial request (e.g. one tab)
     * does not drop other image fields. Keys present in the request (including empty list for
     * "removed") are not overwritten.
     */
    private fun mergeFilesFromCurrentTheme(newSettings: JsonObject, currentThemeSettings: JsonObject) {
        val currentFiles = currentThemeSettings.getJsonObject("files") ?: return
        var nextFiles = newSettings.getJsonObject("files")
        if (nextFiles == null) {
            nextFiles = JsonObject()
            newSettings.put("files", nextFiles)
        }
        for (k in currentFiles.fieldNames()) {
            if (!nextFiles.containsKey(k)) {
                nextFiles.put(k, currentFiles.getValue(k))
            }
        }
    }

    private fun deleteReplacedThemeFiles(
        folder: String,
        previousFiles: JsonObject,
        nextFiles: JsonObject
    ) {
        for (k in previousFiles.fieldNames()) {
            val oldNames = fileNamesInThemeFileValue(previousFiles.getValue(k))
            if (oldNames.isEmpty()) continue
            val newVal = if (nextFiles.containsKey(k)) nextFiles.getValue(k) else null
            val newNames = fileNamesInThemeFileValue(newVal)
            for (fileName in oldNames) {
                if (fileName !in newNames) {
                    val f = File(folder + fileName)
                    if (f.exists()) {
                        f.deleteRecursively()
                    }
                }
            }
        }
    }
}