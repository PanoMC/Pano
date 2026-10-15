package com.panomc.platform.route

import com.panomc.platform.Main
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.api.panel.players.PanelGetPlayersAPI
import com.panomc.platform.route.api.panel.post.PanelGetPostsAPI
import com.panomc.platform.route.api.panel.post.category.PanelGetPostCategoriesAPI
import com.panomc.platform.route.api.panel.ticket.PanelGetTicketsAPI
import com.panomc.platform.route.api.panel.ticket.category.PanelGetTicketCategoriesAPI
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.SchemaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.util.concurrent.TimeUnit

/** PF-21: the panel lists of players, posts and tickets answer `{ items, page }` and read `page` + `pageSize`. */
class PanelPagingATest {
    @Suppress("UNCHECKED_CAST")
    private fun pageOf(body: Map<String, Any?>) = body["page"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(body: Map<String, Any?>) = body["items"] as List<Map<String, Any?>>

    private val legacyKeys = setOf(
        "totalPage", "playerCount", "players", "posts", "postCount", "tickets", "ticketCount", "categoryCount",
        "categories"
    )

    // ---- players ----------------------------------------------------------------------------

    @Test
    fun `players answer items and page with the permission group beside them`() {
        val body = PanelGetPlayersAPI.payload(listOf(mapOf("username" to "steve")), 23, PageRequest(3, 10), "admin")

        assertEquals(setOf("items", "page", "permissionGroup"), body.keys)
        assertEquals(mapOf<String, Any>("number" to 3, "size" to 10, "totalItems" to 23L, "totalPages" to 3L), pageOf(body))
        assertEquals("admin", body["permissionGroup"])
        assertTrue(body.keys.none { it in legacyKeys })
    }

    @Test
    fun `players keep the permissionGroup key as null when the list is not filtered`() {
        val body = PanelGetPlayersAPI.payload(listOf(), 0, PageRequest(1, 10), null)

        assertTrue(body.containsKey("permissionGroup"))
        assertEquals(null, body["permissionGroup"])
        assertEquals(0L, pageOf(body)["totalPages"])
        assertEquals(listOf<Any>(), body["items"])
    }

    @Test
    fun `players page size is the request size, not a fixed ten`() {
        val body = PanelGetPlayersAPI.payload(listOf(), 45, PageRequest(2, 20), null)

        assertEquals(20, pageOf(body)["size"])
        assertEquals(3L, pageOf(body)["totalPages"])
    }

    @Test
    fun `players beyond the last page are not found`() {
        assertThrows(PageNotFound::class.java) { PanelGetPlayersAPI.payload(listOf(), 10, PageRequest(2, 10), null) }
    }

    // ---- posts ------------------------------------------------------------------------------

    private fun post(id: Long, categoryId: Long = -1) = Post(
        id = id, title = "Post $id", categoryId = categoryId, writerUserId = 1, text = "t", date = 1000 + id,
        thumbnailUrl = "t$id.png", url = "post-$id"
    )

    @Test
    fun `posts answer items and page`() {
        val body = PanelGetPostsAPI.payload(null, listOf(post(1), post(2)), mapOf(1L to "steve"), mapOf(), 12, PageRequest(2, 5))

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(mapOf<String, Any>("number" to 2, "size" to 5, "totalItems" to 12L, "totalPages" to 3L), pageOf(body))

        val first = itemsOf(body)[0]

        assertEquals("Post 1", first["title"])
        assertEquals("steve", (first["writer"] as Map<*, *>)["username"])
        assertEquals(mapOf("id" to -1, "title" to "-", "url" to "-"), first["category"])
    }

    @Test
    fun `posts of a category carry the category beside the page`() {
        val category = PostCategory(id = 3, title = "News", url = "news")
        val body = PanelGetPostsAPI.payload(category, listOf(post(1, 3)), mapOf(), mapOf(), 1, PageRequest(1, 10))

        assertEquals(setOf("items", "page", "category"), body.keys)
        assertEquals(category, body["category"])
        assertEquals(category, itemsOf(body)[0]["category"])
    }

    @Test
    fun `an empty posts list is page one of zero pages and a later page is not found`() {
        val body = PanelGetPostsAPI.payload(null, listOf(), mapOf(), mapOf(), 0, PageRequest(1, 10))

        assertEquals(0L, pageOf(body)["totalPages"])
        assertEquals(listOf<Any>(), body["items"])
        assertThrows(PageNotFound::class.java) {
            PanelGetPostsAPI.payload(null, listOf(), mapOf(), mapOf(), 0, PageRequest(2, 10))
        }
    }

    @Test
    fun `post categories answer items and page and keep the host key`() {
        val body = PanelGetPostCategoriesAPI.payload(listOf(mapOf("id" to 1L, "title" to "News")), 1, PageRequest(1, 10))

        assertEquals(setOf("items", "page", "host"), body.keys)
        assertEquals("http://", body["host"])
        assertEquals(1L, pageOf(body)["totalItems"])
    }

    // ---- tickets ----------------------------------------------------------------------------

    @Test
    fun `tickets answer items and page`() {
        val body = PanelGetTicketsAPI.payload(
            null, listOf(Ticket(id = 1, title = "Help", userId = 7)), mapOf(), mapOf(7L to "alex"), 31, PageRequest(4, 10)
        )

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(4L, pageOf(body)["totalPages"])
        assertEquals("alex", (itemsOf(body)[0]["writer"] as Map<*, *>)["username"])
        assertEquals(mapOf("id" to -1, "title" to "-", "url" to "-"), itemsOf(body)[0]["category"])
    }

    @Test
    fun `tickets of a category carry the category beside the page`() {
        val category = TicketCategory(id = 2, title = "Bug", url = "bug")
        val body = PanelGetTicketsAPI.payload(
            category, listOf(Ticket(id = 1, title = "t", userId = 1, categoryId = 2)), mapOf(), mapOf(1L to "a"), 1,
            PageRequest(1, 10)
        )

        assertEquals(category, body["category"])
    }

    @Test
    fun `tickets beyond the last page are not found`() {
        assertThrows(PageNotFound::class.java) {
            PanelGetTicketsAPI.payload(null, listOf(), mapOf(), mapOf(), 10, PageRequest(2, 10))
        }
    }

    @Test
    fun `ticket categories answer items and page and keep the host key`() {
        val body = PanelGetTicketCategoriesAPI.payload(listOf(mapOf("id" to 1L)), 11, PageRequest(2, 10))

        assertEquals(setOf("items", "page", "host"), body.keys)
        assertEquals(2L, pageOf(body)["totalPages"])
    }

    // ---- the real endpoints' validation handlers, through a real router -----------------------

    private class FailureApi : Api() {
        override val paths = listOf(Path("/x", RouteType.GET))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
            ValidationHandlerBuilder.create(schemaRepository).build()

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result = Successful()
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private val objenesis = ObjenesisStd()

    /** The real endpoint without its dependencies: only its validation handler is used, never its handle(). */
    private fun <T : Api> bare(type: Class<T>): T = objenesis.newInstance(type)

    private fun call(api: Api, query: String): Pair<Int, JsonObject> {
        val vertx = Vertx.vertx()
        val previous = runCatching { Main.applicationContext }.getOrNull()

        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("PanelPagingATest") })
            refresh()
        }

        try {
            val repository = SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))
            val router = Router.router(vertx)

            router.route(HttpMethod.GET, "/list")
                .handler(api.getValidationHandler(repository))
                .handler { context ->
                    val page = Paging.request(context)

                    context.response().putHeader("content-type", "application/json")
                        .end(JsonObject().put("number", page.number).put("size", page.size).encode())
                }
                .failureHandler(FailureApi().getFailureHandler())

            val port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
            val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

            return com.panomc.platform.TestHttp.onLoop(vertx) { client.request(HttpMethod.GET, port, "127.0.0.1", "/list$query")
                .compose { it.send() }
                .compose { response ->
                    response.body().map { response.statusCode() to JsonObject(it.toString(Charsets.UTF_8)) }
                } }.blockingGet()
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

            previous?.let { Main.applicationContext = it }
        }
    }

    private val endpoints: List<Pair<String, Api>> by lazy {
        listOf(
            "players" to bare(PanelGetPlayersAPI::class.java),
            "posts" to bare(PanelGetPostsAPI::class.java),
            "post categories" to bare(PanelGetPostCategoriesAPI::class.java),
            "tickets" to bare(PanelGetTicketsAPI::class.java),
            "ticket categories" to bare(PanelGetTicketCategoriesAPI::class.java)
        )
    }

    @Test
    fun `every converted endpoint accepts page and pageSize`() {
        endpoints.forEach { (name, api) ->
            val (status, json) = call(api, "?page=2&pageSize=25")

            assertEquals(200, status, name)
            assertEquals(2, json.getInteger("number"), name)
            assertEquals(25, json.getInteger("size"), name)
        }
    }

    @Test
    fun `every converted endpoint defaults to ten per page, as before`() {
        endpoints.forEach { (name, api) ->
            val (status, json) = call(api, "")

            assertEquals(200, status, name)
            assertEquals(10, json.getInteger("size"), name)
            assertEquals(1, json.getInteger("number"), name)
        }
    }

    @Test
    fun `every converted endpoint refuses an out of range page size with INVALID_FIELDS`() {
        endpoints.forEach { (name, api) ->
            val (status, json) = call(api, "?pageSize=101&page=0")

            assertEquals(400, status, name)
            assertEquals("INVALID_FIELDS", json.getJsonObject("error").getString("code"), name)
            assertEquals(
                mapOf("page" to "OUT_OF_RANGE", "pageSize" to "OUT_OF_RANGE"),
                json.getJsonObject("error").getJsonObject("fields").map,
                name
            )
        }
    }

    @Test
    fun `no converted endpoint source mentions totalPage any more`() {
        val folder = java.io.File("src/main/kotlin/com/panomc/platform/route/api/panel")

        if (!folder.exists()) {
            return
        }

        listOf("players", "post", "ticket").forEach { name ->
            folder.resolve(name).walkTopDown().filter { it.extension == "kt" }.forEach {
                assertFalse(it.readText().contains("totalPage"), it.path)
            }
        }
    }
}
