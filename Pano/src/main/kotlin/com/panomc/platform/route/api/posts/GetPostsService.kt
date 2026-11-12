package com.panomc.platform.route.api.posts


import com.panomc.platform.i18n.PostI18n
import com.panomc.platform.i18n.ContentI18n
import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.util.HtmlSanitizer
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.CategoryNotExists
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.sqlclient.SqlClient
import org.springframework.stereotype.Service
import util.StringUtil

@Service
class GetPostsService(
    private val databaseManager: DatabaseManager,
    private val contentTranslations: ContentTranslations
) {
    suspend fun handle(parameters: RequestParameters, page: PageRequest, sqlClient: SqlClient): Result {
        val categoryUrl = parameters.queryParameter("categoryUrl")?.string

        var postCategory: PostCategory? = null

        if (categoryUrl != null && categoryUrl != "-") {
            val isPostCategoryExists = databaseManager.postCategoryDao.existsByUrl(categoryUrl, sqlClient)

            if (!isPostCategoryExists) {
                throw CategoryNotExists()
            }

            postCategory = databaseManager.postCategoryDao.getByUrl(categoryUrl, sqlClient)!!
        }

        if (categoryUrl != null && categoryUrl == "-") {
            postCategory = PostCategory()
        }

        val count = if (postCategory != null)
            databaseManager.postDao.countOfPublishedByCategoryId(postCategory.id, sqlClient)
        else
            databaseManager.postDao.countOfPublished(sqlClient)

        Paging.requireInRange(page, count)

        val posts = if (postCategory != null)
            databaseManager.postDao.getPublishedListByCategoryId(postCategory.id, page.limit, page.offset, sqlClient)
        else
            databaseManager.postDao.getPublishedList(page.limit, page.offset, sqlClient)

        // the posts' and categories' texts in other languages
        val postTranslations = contentTranslations.loadPosts(posts.map { it.id }, sqlClient)
        val categoryTranslations = contentTranslations.loadPostCategories(
            posts.map { it.categoryId }.filter { it != -1L } + listOfNotNull(postCategory?.id?.takeIf { it != -1L }), sqlClient
        )
        val i18n = PostI18n(
            { id -> ContentI18n.postI18n(id, postTranslations[id]) },
            { id -> ContentI18n.postCategoryI18n(id, categoryTranslations[id]) }
        )

        if (posts.isEmpty()) {
            return Successful(payload(postCategory, posts, mapOf(), mapOf(), count, page, i18n))
        }

        val userIdList = posts.distinctBy { it.writerUserId }.map { it.writerUserId }.filter { it != -1L }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

        if (postCategory != null) {
            return Successful(payload(postCategory, posts, usernameList, mapOf(), count, page, i18n))
        }

        val categoryIdList =
            posts.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return Successful(payload(null, posts, usernameList, mapOf(), count, page, i18n))
        }

        val categories = databaseManager.postCategoryDao.getByIdList(categoryIdList, sqlClient)

        return Successful(payload(null, posts, usernameList, categories, count, page, i18n))
    }

    companion object {
        /** Posts per page when the client sends no `pageSize` (as before the page shape). */
        const val DEFAULT_PAGE_SIZE = 5

        /** The whole response body: `{ items, page }` plus the `category` when the list is filtered. */
        fun payload(
            postCategory: PostCategory?,
            posts: List<Post>,
            usernameList: Map<Long, String>,
            categories: Map<Long, PostCategory>,
            count: Long,
            page: PageRequest,
            i18n: PostI18n = PostI18n()
        ): Map<String, Any?> {
            val items = posts.map { post ->
                mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    "category" to
                            if (post.categoryId == -1L)
                                mapOf("id" to -1, "title" to "-")
                            else
                                categories[post.categoryId]?.let { i18n.categoryMap(it) }
                                    ?: mapOf("id" to -1, "title" to "-"),
                    "text" to StringUtil.truncateHTML(HtmlSanitizer.sanitize(post.text), 500, "&hellip;"),
                    // the excerpt in other languages is cut the same way
                    "i18n" to i18n.post(post.id)?.let { PostI18n.excerpt(it) },
                    "writer" to mapOf(
                        "username" to (usernameList[post.writerUserId] ?: "-")
                    ),
                    "date" to post.date,
                    "thumbnailUrl" to post.thumbnailUrl,
                    "views" to post.views,
                    "url" to post.url
                )
            }

            return PostI18n.compactMap(Paging.response(
                items,
                count,
                page,
                if (postCategory != null) mapOf("category" to i18n.categoryMap(postCategory)) else mapOf()
            ))
        }
    }
}
