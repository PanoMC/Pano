package com.panomc.platform.route.api.panel.post


import com.panomc.platform.i18n.ContentI18n
import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.util.HtmlSanitizer
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelGetPostAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val contentTranslations: ContentTranslations
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/posts/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val exists = databaseManager.postDao.existsById(id, sqlClient)

        if (!exists) {
            throw PostNotFound()
        }

        val post = databaseManager.postDao.getById(id, sqlClient)!!

        // The unsaved changes the editor kept for the post, restored into the editor when it opens.
        val autosave = databaseManager.postAutosaveDao.getByPostId(id, sqlClient)

        val translations = contentTranslations.loadPosts(listOf(post.id), sqlClient)[post.id]

        return Successful(
            mapOf(
                "post" to mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    // the title and text in the other languages (the editor's language switcher)
                    "i18n" to ContentI18n.postI18n(post.id, translations),
                    "category" to post.categoryId,
                    "writerUserId" to post.writerUserId,
                    "text" to HtmlSanitizer.sanitize(post.text),
                    "date" to post.date,
                    "status" to post.status,
                    "thumbnailUrl" to post.thumbnailUrl,
                    "views" to post.views
                ),
                "autosave" to autosave?.let {
                    mapOf(
                        "title" to it.title,
                        "category" to it.categoryId,
                        "text" to HtmlSanitizer.sanitize(it.text),
                        "thumbnailUrl" to it.thumbnailUrl,
                        "removeThumbnail" to it.removeThumbnail,
                        "updatedAt" to it.updatedAt
                    )
                }
            )
        )
    }
}