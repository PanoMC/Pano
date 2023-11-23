package com.panomc.platform.route.api.posts


import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.CategoryNotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.sqlclient.SqlClient
import org.springframework.stereotype.Service
import util.StringUtil

@Service
class GetPostsService(private val databaseManager: DatabaseManager) {
    suspend fun handle(parameters: RequestParameters, sqlClient: SqlClient): Result {
        val page = parameters.queryParameter("page")?.long ?: 1
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

        var totalPage = kotlin.math.ceil(count.toDouble() / 5).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val posts = if (postCategory != null)
            databaseManager.postDao.getPublishedListByPageAndCategoryId(postCategory.id, page, sqlClient)
        else
            databaseManager.postDao.getPublishedListByPage(page, sqlClient)

        if (posts.isEmpty()) {
            return prepareResult(postCategory, posts, mapOf(), mapOf(), count, totalPage)
        }

        val userIdList = posts.distinctBy { it.writerUserId }.map { it.writerUserId }.filter { it != -1L }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

        if (postCategory != null) {
            return prepareResult(postCategory, posts, usernameList, mapOf(), count, totalPage)
        }

        val categoryIdList =
            posts.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return prepareResult(null, posts, usernameList, mapOf(), count, totalPage)
        }

        val categories = databaseManager.postCategoryDao.getByIdList(categoryIdList, sqlClient)

        return prepareResult(null, posts, usernameList, categories, count, totalPage)
    }

    private val prepareResult: (
        PostCategory?,
        List<Post>,
        Map<Long, String>,
        Map<Long, PostCategory>,
        Long,
        Long
    ) -> Successful = { postCategory, posts, usernameList, categories, count, totalPage ->
        val postsDataList = mutableListOf<Map<String, Any?>>()

        posts.forEach { post ->
            postsDataList.add(
                mapOf(
                    "id" to post.id,
                    "title" to post.title,
                    "category" to
                            if (post.categoryId == -1L)
                                mapOf("id" to -1, "title" to "-")
                            else
                                categories.getOrDefault(
                                    post.categoryId,
                                    mapOf("id" to -1, "title" to "-")
                                ),
                    "text" to StringUtil.truncateHTML(post.text, 500, "&hellip;"),
                    "writer" to mapOf(
                        "username" to (usernameList[post.writerUserId] ?: "-")
                    ),
                    "date" to post.date,
                    "thumbnailUrl" to post.thumbnailUrl,
                    "views" to post.views,
                    "url" to post.url
                )
            )
        }

        val data = mutableMapOf<String, Any?>(
            "posts" to postsDataList,
            "postCount" to count,
            "totalPage" to totalPage
        )

        if (postCategory != null) {
            data["category"] = postCategory
        }

        Successful(data)
    }
}