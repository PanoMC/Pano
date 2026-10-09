package com.panomc.platform.model

import com.panomc.platform.db.model.Notification
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.route.api.GetSitemapAPI
import com.panomc.platform.route.api.notification.GetNotificationsAPI
import com.panomc.platform.route.api.posts.GetPostsService
import com.panomc.platform.route.api.ticket.GetTicketCategoriesAPI
import com.panomc.platform.route.api.ticket.GetTicketsService
import com.panomc.platform.Main
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.SchemaRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** PF-10: the one page shape (doc 04 section 4) and the public lists converted to it. */
class PagingTest {
    private fun page(number: Int = 1, size: Int = 10) = PageRequest(number, size)

    @Suppress("UNCHECKED_CAST")
    private fun pageOf(body: Map<String, Any?>) = body["page"] as Map<String, Any?>

    private fun fieldsOf(e: Error): Map<String, Any?> =
        JsonObject(e.encode()).getJsonObject("error").getJsonObject("fields").map

    // ---- request ----------------------------------------------------------------------------

    @Test
    fun `absent parameters give page 1 and the endpoint default size`() {
        val request = Paging.parse(null, null, defaultSize = 5)

        assertEquals(1, request.number)
        assertEquals(5, request.size)
        assertEquals(0L, request.offset)
        assertEquals(5, request.limit)
    }

    @Test
    fun `default size of ten and a max of one hundred`() {
        assertEquals(10, Paging.parse(null, null).size)
        assertEquals(100, Paging.parse(null, "100").size)
    }

    @Test
    fun `offset is the rows before the page`() {
        assertEquals(40L, Paging.parse("3", "20").offset)
        assertEquals(0L, Paging.parse("1", "7").offset)
    }

    @Test
    fun `offset does not overflow an int`() {
        assertEquals(2_000_000_000L * 100, Paging.parse("2000000001", "100").offset)
    }

    @Test
    fun `page below one is refused with OUT_OF_RANGE, never clamped`() {
        listOf("0", "-1").forEach {
            val e = assertThrows(InvalidFields::class.java) { Paging.parse(it, null) }

            assertEquals(400, e.getStatusCode())
            assertEquals("INVALID_FIELDS", e.getErrorCode())
            assertEquals(mapOf("page" to "OUT_OF_RANGE"), fieldsOf(e))
        }
    }

    @Test
    fun `page size outside the range is refused with OUT_OF_RANGE`() {
        listOf("0", "-5", "101", "9999999999").forEach {
            val e = assertThrows(InvalidFields::class.java) { Paging.parse(null, it) }

            assertEquals(mapOf("pageSize" to "OUT_OF_RANGE"), fieldsOf(e))
        }
    }

    @Test
    fun `the endpoint max replaces the global one`() {
        assertEquals(20, Paging.parse(null, "20", defaultSize = 10, maxSize = 20).size)
        assertThrows(InvalidFields::class.java) { Paging.parse(null, "21", defaultSize = 10, maxSize = 20) }
    }

    @Test
    fun `both bad fields are reported together`() {
        val e = assertThrows(InvalidFields::class.java) { Paging.parse("0", "0") }

        assertEquals(mapOf("page" to "OUT_OF_RANGE", "pageSize" to "OUT_OF_RANGE"), fieldsOf(e))
    }

    @Test
    fun `text that is not an integer is refused`() {
        assertThrows(InvalidFields::class.java) { Paging.parse("abc", null) }
        assertThrows(InvalidFields::class.java) { Paging.parse(null, "1.5") }
    }

    @Test
    fun `a page above int range parses and ends up as page not found`() {
        val request = Paging.parse("99999999999", null)

        assertEquals(Int.MAX_VALUE, request.number)
        assertThrows(PageNotFound::class.java) { Paging.response(emptyList(), 3, request) }
    }

    // ---- response ---------------------------------------------------------------------------

    @Test
    fun `response has items and the page object`() {
        val body = Paging.response(listOf("a", "b"), 57, page(2, 20))

        assertEquals(listOf("a", "b"), body["items"])
        assertEquals(
            mapOf<String, Any>("number" to 2, "size" to 20, "totalItems" to 57L, "totalPages" to 3L),
            pageOf(body)
        )
        assertEquals(setOf("items", "page"), body.keys)
    }

    @Test
    fun `extra top level keys are kept`() {
        val body = Paging.response(emptyList(), 0, page(), mapOf("category" to "news"))

        assertEquals("news", body["category"])
    }

    @Test
    fun `extra may not shadow items or page`() {
        assertThrows(IllegalArgumentException::class.java) {
            Paging.response(emptyList(), 0, page(), mapOf("items" to 1))
        }
    }

    @Test
    fun `empty result has zero pages and page one is valid`() {
        val body = Paging.response(emptyList(), 0, page(1, 10))

        assertEquals(0L, pageOf(body)["totalPages"])
        assertEquals(0L, pageOf(body)["totalItems"])
        assertEquals(emptyList<Any>(), body["items"])
    }

    @Test
    fun `a page beyond the last is page not found`() {
        val e = assertThrows(PageNotFound::class.java) { Paging.response(emptyList(), 57, page(4, 20)) }

        assertEquals(404, e.getStatusCode())
        assertEquals("PAGE_NOT_FOUND", e.getErrorCode())
        assertThrows(PageNotFound::class.java) { Paging.response(emptyList(), 0, page(2, 10)) }
    }

    @Test
    fun `the last page is valid and an exact multiple adds no empty page`() {
        Paging.response(listOf(1), 40, page(2, 20))

        assertThrows(PageNotFound::class.java) { Paging.requireInRange(page(3, 20), 40) }
        assertEquals(3L, Paging.totalPages(41, 20))
        assertEquals(2L, Paging.totalPages(40, 20))
    }

    // ---- posts ------------------------------------------------------------------------------

    private fun post(id: Long, categoryId: Long = -1, writer: Long = 1) = Post(
        id = id, title = "Post $id", categoryId = categoryId, writerUserId = writer,
        text = "<p>body $id</p>", date = 1000 + id, thumbnailUrl = "t$id.png", url = "post-$id"
    )

    @Test
    fun `posts answer items and page, not posts postCount totalPage`() {
        val body = GetPostsService.payload(
            null, listOf(post(1), post(2)), mapOf(1L to "steve"), mapOf(), 12, PageRequest(2, 5)
        )

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(mapOf<String, Any>("number" to 2, "size" to 5, "totalItems" to 12L, "totalPages" to 3L), pageOf(body))

        @Suppress("UNCHECKED_CAST")
        val first = (body["items"] as List<Map<String, Any?>>)[0]

        assertEquals("Post 1", first["title"])
        assertEquals("steve", (first["writer"] as Map<*, *>)["username"])
        assertEquals("post-1", first["url"])
        assertEquals(mapOf("id" to -1, "title" to "-"), first["category"])
    }

    @Test
    fun `posts of a category carry the category beside the page`() {
        val category = PostCategory(id = 3, title = "News", url = "news")
        val body = GetPostsService.payload(category, listOf(post(1, 3)), mapOf(), mapOf(), 1, PageRequest(1, 5))

        assertEquals(category, body["category"])
    }

    @Test
    fun `posts default to five per page and a page past the end is not found`() {
        assertEquals(5, GetPostsService.DEFAULT_PAGE_SIZE)
        assertThrows(PageNotFound::class.java) {
            GetPostsService.payload(null, emptyList(), mapOf(), mapOf(), 12, PageRequest(4, 5))
        }
    }

    // ---- tickets ----------------------------------------------------------------------------

    @Test
    fun `tickets answer items and page, not tickets ticketCount totalPage`() {
        val body = GetTicketsService.payload(
            null, listOf(Ticket(id = 1, title = "Help", userId = 7)), mapOf(), "alex", 1, PageRequest(1, 10)
        )

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(1L, pageOf(body)["totalPages"])

        @Suppress("UNCHECKED_CAST")
        val first = (body["items"] as List<Map<String, Any?>>)[0]

        assertEquals("Help", first["title"])
        assertEquals("alex", (first["writer"] as Map<*, *>)["username"])
    }

    @Test
    fun `tickets of a category carry the category and default to ten per page`() {
        val category = TicketCategory(id = 2, title = "Bug", url = "bug")
        val body = GetTicketsService.payload(
            category, listOf(Ticket(id = 1, title = "t", userId = 1, categoryId = 2)), mapOf(), "a", 1, PageRequest(1, 10)
        )

        assertEquals(category, body["category"])
        assertEquals(10, GetTicketsService.DEFAULT_PAGE_SIZE)
    }

    @Test
    fun `ticket categories use the common shape and default to the largest page`() {
        assertEquals(100, GetTicketCategoriesAPI.DEFAULT_PAGE_SIZE)

        val body = Paging.response(listOf(TicketCategory(id = 1, title = "Bug")), 1, PageRequest(1, 100))

        assertEquals(setOf("items", "page"), body.keys)
    }

    // ---- notifications ----------------------------------------------------------------------

    private class TestNotification : NotificationType

    @Test
    fun `notifications answer items and page with the plugin owner`() {
        val notification = Notification(
            id = 9, userId = 4, type = TestNotification(), details = JsonObject().put("k", "v"), createdAt = 5, updatedAt = 6
        )

        val body = GetNotificationsAPI.payload(listOf(notification), 4, { "pano-plugin-x" }, 31, PageRequest(2, 10))

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(4L, pageOf(body)["totalPages"])

        @Suppress("UNCHECKED_CAST")
        val first = (body["items"] as List<Map<String, Any?>>)[0]

        assertEquals(9L, first["id"])
        assertEquals("pano-plugin-x", first["pluginId"])
        assertEquals(true, first["isPersonal"])
        assertEquals(mapOf("k" to "v"), first["details"])
        assertEquals(10, GetNotificationsAPI.DEFAULT_PAGE_SIZE)
    }

    @Test
    fun `notifications beyond the last page are not found`() {
        assertThrows(PageNotFound::class.java) {
            GetNotificationsAPI.payload(emptyList(), 1, { null }, 5, PageRequest(2, 10))
        }
    }

    // ---- sitemap page (the entry behaviour lives in SitemapTest) ----------------------------

    @Test
    fun `sitemap defaults to one hundred per page`() {
        assertEquals(100, GetSitemapAPI.DEFAULT_PAGE_SIZE)
        assertFalse(GetSitemapAPI.payload(emptyList(), PageRequest(1, 100)).containsKey("posts"))
        assertNull(pageOf(GetSitemapAPI.payload(emptyList(), PageRequest(1, 100)))["nextCursor"])
    }

    // ---- through a real router: validation handler, request(), failure handler --------------------

    private class PagedApi : Api() {
        override val paths = listOf(Path("/paged", RouteType.GET))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
            Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result {
            val page = Paging.request(context, 7)

            return Successful(Paging.response(listOf("x"), 30, page))
        }
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun call(query: String): Pair<Int, JsonObject> {
        val vertx = Vertx.vertx()
        val previous = runCatching { Main.applicationContext }.getOrNull()

        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("PagingTest") })
            refresh()
        }

        try {
            val api = PagedApi()
            val repository = SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))
            val router = Router.router(vertx)

            router.route(HttpMethod.GET, "/paged")
                .handler(api.getValidationHandler(repository))
                .handler(api.getHandler())
                .failureHandler(api.getFailureHandler())

            val port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
            val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

            return client.request(HttpMethod.GET, port, "127.0.0.1", "/paged$query")
                .compose { it.send() }
                .compose { response ->
                    response.body().map { response.statusCode() to JsonObject(it.toString(Charsets.UTF_8)) }
                }
                .blockingGet()
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

            previous?.let { Main.applicationContext = it }
        }
    }

    @Test
    fun `router - defaults come from the endpoint`() {
        val (status, json) = call("")

        assertEquals(200, status)
        assertEquals(7, json.getJsonObject("page").getInteger("size"))
        assertEquals(1, json.getJsonObject("page").getInteger("number"))
    }

    @Test
    fun `router - page and pageSize are read from the query`() {
        val (status, json) = call("?page=2&pageSize=15")

        assertEquals(200, status)
        assertEquals(15, json.getJsonObject("page").getInteger("size"))
        assertEquals(2, json.getJsonObject("page").getInteger("number"))
        assertEquals(2, json.getJsonObject("page").getInteger("totalPages"))
    }

    @Test
    fun `router - out of range answers 400 INVALID_FIELDS with the field code`() {
        val (status, json) = call("?pageSize=101&page=0")

        assertEquals(400, status)
        assertEquals("INVALID_FIELDS", json.getJsonObject("error").getString("code"))
        assertEquals(
            mapOf("page" to "OUT_OF_RANGE", "pageSize" to "OUT_OF_RANGE"),
            json.getJsonObject("error").getJsonObject("fields").map
        )
    }

    @Test
    fun `router - a page beyond the last answers 404 PAGE_NOT_FOUND`() {
        val (status, json) = call("?page=5&pageSize=10")

        assertEquals(404, status)
        assertEquals("PAGE_NOT_FOUND", json.getJsonObject("error").getString("code"))
    }
}
