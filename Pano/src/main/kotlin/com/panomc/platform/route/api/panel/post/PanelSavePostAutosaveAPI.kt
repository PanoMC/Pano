package com.panomc.platform.route.api.panel.post

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostAutosave
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.multipartFormData
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.PostStatus
import com.panomc.platform.util.TextUtil
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * `PUT /api/v1/panel/posts/:id/autosave` -- keeps what the post editor has on screen for a post, without touching the
 * post: the title, text and category, and a cover picked in the editor (field `thumbnail`, uploaded now) or its
 * removal (`removeThumbnail`). A request with neither keeps the cover state of the last autosave.
 *
 * `POST /api/v1/panel/posts/autosave` does the same for a post that was never saved: it first makes a draft post
 * with the title and category (so the post is in the Drafts list, marked as having unsaved changes) and answers its
 * `id`; the editor goes on with `PUT` from then on.
 *
 * Saving or publishing the post takes the autosave over and removes it (`PanelCreateOrUpdatePostAPI`).
 */
@Endpoint
class PanelSavePostAutosaveAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(
        Path("/posts/:id/autosave", RouteType.PUT),
        Path("/posts/autosave", RouteType.POST)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(optionalParam("id", numberSchema()))
            .body(
                multipartFormData(
                    objectSchema()
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("category", numberSchema())
                        .requiredProperty("text", stringSchema())
                        .optionalProperty("removeThumbnail", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id")?.long
        val title = data.getString("title").orEmpty()
        val categoryId = data.getLong("category")
        val text = data.getString("text").orEmpty()
        val removeThumbnail = data.getBoolean("removeThumbnail") ?: false

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val postId = if (id == null) {
            val url = TextUtil.convertStringToUrl(title, 32)

            val newId = databaseManager.postDao.insert(
                Post(
                    title = title,
                    categoryId = categoryId,
                    writerUserId = userId,
                    text = "",
                    status = PostStatus.DRAFT,
                    thumbnailUrl = "",
                    url = url
                ),
                sqlClient
            )

            databaseManager.postDao.updatePostUrlByUrl(url, "$url-$newId", sqlClient)

            newId
        } else {
            if (!databaseManager.postDao.existsById(id, sqlClient)) {
                throw NotExists()
            }

            id
        }

        val previous = databaseManager.postAutosaveDao.getByPostId(postId, sqlClient)
        val uploaded = if (removeThumbnail) null else Post.saveThumbnail(context.fileUploads(), configManager)

        // A cover replaced or removed again since the last autosave is not needed any more.
        if ((uploaded != null || removeThumbnail) && !previous?.thumbnailUrl.isNullOrEmpty()) {
            Post.deleteThumbnailFile(previous!!.thumbnailUrl, configManager)
        }

        val autosave = PostAutosave(
            postId = postId,
            userId = userId,
            title = title,
            text = text,
            categoryId = categoryId,
            thumbnailUrl = when {
                uploaded != null -> uploaded
                removeThumbnail -> ""
                else -> previous?.thumbnailUrl ?: ""
            },
            removeThumbnail = when {
                uploaded != null -> false
                removeThumbnail -> true
                else -> previous?.removeThumbnail ?: false
            }
        )

        databaseManager.postAutosaveDao.upsert(autosave, sqlClient)

        return Successful(
            mapOf(
                "id" to postId,
                "updatedAt" to autosave.updatedAt,
                "thumbnailUrl" to autosave.thumbnailUrl
            )
        )
    }
}
