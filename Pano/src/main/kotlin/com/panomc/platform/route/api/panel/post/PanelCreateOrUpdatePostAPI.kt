package com.panomc.platform.route.api.panel.post

import com.panomc.platform.webhook.WebhookCoreEvents
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.PublishedPostLog
import com.panomc.platform.auth.panel.log.UpdatedPostLog
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.Post.Companion.deleteThumbnailFile
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.PostStatus
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.multipartFormData
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelCreateOrUpdatePostAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(
        Path("/posts/:id", RouteType.PUT),
        Path("/post", RouteType.POST)
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
                        .optionalProperty("publish", booleanSchema())
                        .optionalProperty("removeThumbnail", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val fileUploads = context.fileUploads()

        val id = parameters.pathParameter("id")?.long
        val title = data.getString("title")
        val categoryId = data.getLong("category")
        val text = data.getString("text")
        val removeThumbnail = data.getBoolean("removeThumbnail") ?: false
        val publish = data.getBoolean("publish") ?: true
        val url = TextUtil.convertStringToUrl(title, 32)

        var thumbnailUrl = ""
        val body = mutableMapOf<String, Any?>()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        var postInDb: Post? = null

        if (id == null) {
            thumbnailUrl = Post.saveThumbnail(fileUploads, configManager) ?: ""
        } else {
            postInDb = databaseManager.postDao.getById(id, sqlClient) ?: throw NotExists()

            // The editor's autosave may hold a cover picked earlier (uploaded then) or its removal: the save takes
            // it over unless this request says otherwise.
            val autosave = databaseManager.postAutosaveDao.getByPostId(id, sqlClient)
            val uploaded = if (removeThumbnail) null else Post.saveThumbnail(fileUploads, configManager)

            thumbnailUrl = when {
                uploaded != null -> uploaded
                removeThumbnail || autosave?.removeThumbnail == true -> ""
                !autosave?.thumbnailUrl.isNullOrEmpty() -> autosave!!.thumbnailUrl
                else -> postInDb.thumbnailUrl
            }

            if (thumbnailUrl != postInDb.thumbnailUrl) {
                postInDb.deleteThumbnailFile(configManager)
            }

            if (autosave != null && autosave.thumbnailUrl.isNotEmpty() && autosave.thumbnailUrl != thumbnailUrl) {
                Post.deleteThumbnailFile(autosave.thumbnailUrl, configManager)
            }

            databaseManager.postAutosaveDao.deleteByPostId(id, sqlClient)
        }

        val post = Post(
            id = id ?: -1,
            title = title,
            categoryId = categoryId,
            writerUserId = userId,
            text = text,
            thumbnailUrl = thumbnailUrl,
            status = if (postInDb == null) // when creating post first time
                if (publish)
                    PostStatus.PUBLISHED
                else // for save button
                    PostStatus.DRAFT
            else // when the post is already exists
                if (publish)
                    PostStatus.PUBLISHED
                else // for save button
                    postInDb.status,
            url = if (id == null) url else "$url-$id"
        )

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        if (id == null) {
            val postId = databaseManager.postDao.insert(post, sqlClient)

            databaseManager.postDao.updatePostUrlByUrl(url, "$url-$postId", sqlClient)

            body["id"] = postId


            databaseManager.panelActivityLogDao.add(PublishedPostLog(userId, username, title), sqlClient)
        } else {
            databaseManager.postDao.update(userId, post, sqlClient)

            databaseManager.panelActivityLogDao.add(UpdatedPostLog(userId, username, title), sqlClient)
        }

        // core.post.published: only when the post becomes published (a new post saved as published, or a draft / trashed
        // post published), not when an already published post is edited
        if (post.status == PostStatus.PUBLISHED && postInDb?.status != PostStatus.PUBLISHED) {
            val publishedId = id ?: body["id"] as Long
            val published = databaseManager.postDao.getById(publishedId, sqlClient)

            if (published != null) {
                WebhookCoreEvents.fire { postPublished(published, sqlClient) }
            }
        }

        return Successful(body)
    }
}
