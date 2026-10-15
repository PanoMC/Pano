package com.panomc.platform.route.api.panel.frontend.mode

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.model.*
import com.panomc.platform.ui.CustomAppInstaller
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import java.io.File

/**
 * Uploads a custom app (doc 05 §8.1): a multipart zip, field `file`, up to 100 MB, with `manifest.json`
 * and `index.js` at its root. Uploading selects nothing; the front-end is switched with `PUT /panel/frontend`.
 */
@Endpoint
class PanelUploadCustomAppAPI(
    private val customAppInstaller: CustomAppInstaller,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/custom-apps", RouteType.POST))

    // Multipart: nothing a JSON schema could check.
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override fun bodyHandler(): Handler<RoutingContext> = authorizedBodyHandler(
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(MAX_UPLOAD_BYTES)
    )

    // Asked before the upload is spooled to disk, not after.
    override suspend fun checkBeforeBody(context: RoutingContext) {
        authProvider.requirePermission(ManageViewPermission(), context)
    }

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val uploads = context.fileUploads()
        val upload = uploads.firstOrNull { it.name() == PART_NAME } ?: uploads.singleOrNull() ?: throw BadRequest()

        if (upload.size() > MAX_UPLOAD_BYTES) {
            throw FileTooLarge()
        }

        val app = customAppInstaller.install(File(upload.uploadedFileName()))

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UploadedCustomAppLog(userId, username, app.id, app.version), sqlClient)

        return Successful(
            mapOf(
                "id" to app.id,
                "title" to app.title,
                "version" to app.version,
                "author" to app.author,
                "description" to app.description,
                "apiLevel" to app.apiLevel,
                "installedAt" to app.installedAt
            )
        )
    }

    companion object {
        const val PART_NAME = "file"
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
    }
}
