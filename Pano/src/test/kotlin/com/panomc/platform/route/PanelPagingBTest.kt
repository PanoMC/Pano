package com.panomc.platform.route

import com.panomc.platform.Main
import com.panomc.platform.db.model.Locale
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.db.model.ServerAlert
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.api.panel.PanelGetActivityLogsAPI
import com.panomc.platform.route.api.panel.alert.PanelGetAlertsAPI
import com.panomc.platform.route.api.panel.locale.PanelGetLocalesAPI
import com.panomc.platform.route.api.panel.maintenance.PanelGetMaintenanceBannedIpsAPI
import com.panomc.platform.model.CursorPaging
import com.panomc.platform.route.api.panel.server.PanelGetServerActivityAPI
import com.panomc.platform.model.WholeList
import com.panomc.platform.route.api.panel.server.console.PanelSearchServerConsoleAPI
import com.panomc.platform.server.alert.ServerAlertKind
import com.panomc.platform.server.console.ConsoleDeepSearch
import com.panomc.platform.server.dto.ConsoleLineData
import com.panomc.platform.model.Paging
import com.panomc.platform.route.api.panel.notification.PanelNotificationPage
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PF-22: the remaining panel lists answer `{ items, page }`; the cursor lists (server activity, alerts, console
 * search, notifications) answer `{ items, page: { size, nextCursor } }` and read `limit` + `cursor`.
 */
class PanelPagingBTest {
    @Suppress("UNCHECKED_CAST")
    private fun pageOf(body: Map<String, Any?>) = body["page"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(body: Map<String, Any?>) = body["items"] as List<Any?>

    private val legacyKeys = setOf(
        "data", "meta", "totalPage", "totalCount", "bannedIps", "alerts", "entries", "hasMore", "lines", "cursor",
        "notifications"
    )

    // ---- page-number lists ------------------------------------------------------------------

    @Test
    fun `activity logs answer items and page`() {
        val body = PanelGetActivityLogsAPI.payload(listOf(PanelActivityLog(type = "login")), 31, PageRequest(4, 10))

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(mapOf<String, Any>("number" to 4, "size" to 10, "totalItems" to 31L, "totalPages" to 4L), pageOf(body))
        assertEquals(1, itemsOf(body).size)
    }

    @Test
    fun `activity logs page size is the request size and a later page is not found`() {
        assertEquals(25, pageOf(PanelGetActivityLogsAPI.payload(listOf(), 60, PageRequest(3, 25)))["size"])
        assertThrows(PageNotFound::class.java) { PanelGetActivityLogsAPI.payload(listOf(), 10, PageRequest(2, 10)) }
    }

    @Test
    fun `an empty activity log is page one of zero pages`() {
        val body = PanelGetActivityLogsAPI.payload(listOf(), 0, PageRequest(1, 10))

        assertEquals(0L, pageOf(body)["totalPages"])
        assertEquals(listOf<Any>(), body["items"])
    }

    @Test
    fun `locales answer items and page`() {
        val locale = Locale(code = "en", name = "English", dateFnsCode = "enUS", derivatives = listOf())
        val body = PanelGetLocalesAPI.payload(listOf(locale), 11, PageRequest(2, 10))

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(2L, pageOf(body)["totalPages"])
        assertEquals(listOf<Any?>(locale), body["items"])
        assertThrows(PageNotFound::class.java) { PanelGetLocalesAPI.payload(listOf(), 11, PageRequest(3, 10)) }
    }

    @Test
    fun `the banned ip list keeps its page size of twenty five`() {
        assertEquals(25, PanelGetMaintenanceBannedIpsAPI.DEFAULT_PAGE_SIZE)
    }

    // ---- whole lists ------------------------------------------------------------------------

    @Test
    fun `a whole list is one page holding everything`() {
        val body = WholeList.response(listOf("a", "b", "c"), mapOf("filterCount" to 1))

        assertEquals(setOf("items", "page", "filterCount"), body.keys)
        assertEquals(mapOf<String, Any>("number" to 1, "size" to 3, "totalItems" to 3L, "totalPages" to 1L), pageOf(body))
    }

    @Test
    fun `an empty whole list has zero pages and a valid first page`() {
        val body = WholeList.response(listOf())

        assertEquals(0L, pageOf(body)["totalItems"])
        assertEquals(0L, pageOf(body)["totalPages"])
        assertEquals(1, pageOf(body)["size"])
    }

    @Test
    fun `a whole list refuses an extra key that would shadow items or page`() {
        assertThrows(IllegalArgumentException::class.java) { WholeList.response(listOf(), mapOf("page" to 1)) }
    }

    // ---- cursor lists -----------------------------------------------------------------------

    @Test
    fun `a cursor response has the size and the next cursor and nothing else in page`() {
        val body = CursorPaging.response(listOf("x"), 50, "41", mapOf("query" to "q"))

        assertEquals(setOf("items", "page", "query"), body.keys)
        assertEquals(mapOf<String, Any?>("size" to 50, "nextCursor" to "41"), pageOf(body))
    }

    @Test
    fun `the last cursor page has a null next cursor but still the key`() {
        val page = pageOf(CursorPaging.response(listOf(), 50, null))

        assertTrue(page.containsKey("nextCursor"))
        assertNull(page["nextCursor"])
    }

    @Test
    fun `a cursor response refuses an extra key that would shadow items or page`() {
        assertThrows(IllegalArgumentException::class.java) { CursorPaging.response(listOf(), 1, null, mapOf("items" to 1)) }
    }

    @Test
    fun `split gives the cursor of the last shown row only when a further row exists`() {
        val (shown, next) = CursorPaging.split(listOf(9L, 8L, 7L), 2) { it }

        assertEquals(listOf(9L, 8L), shown)
        assertEquals("8", next)

        val (all, none) = CursorPaging.split(listOf(9L, 8L), 2) { it }

        assertEquals(listOf(9L, 8L), all)
        assertNull(none)

        assertNull(CursorPaging.split(listOf<Long>(), 2) { it }.second)
    }

    private fun fieldsOf(failure: InvalidFields): Map<String, Any?> =
        JsonObject(failure.encode(mapOf())).getJsonObject("error").getJsonObject("fields").map

    @Test
    fun `limit defaults, accepts the range and refuses anything outside it`() {
        assertEquals(50, CursorPaging.limit(null, 50, 200))
        assertEquals(1, CursorPaging.limit("1", 50, 200))
        assertEquals(200, CursorPaging.limit(" 200 ", 50, 200))

        listOf("0", "201", "-3", "abc", "").forEach { raw ->
            val failure = assertThrows(InvalidFields::class.java, { CursorPaging.limit(raw, 50, 200) }, raw)

            assertEquals(mapOf("limit" to "OUT_OF_RANGE"), fieldsOf(failure), raw)
        }
    }

    @Test
    fun `an id cursor is absent for the first page and refused when it is not an id`() {
        assertNull(CursorPaging.idCursor(null))
        assertNull(CursorPaging.idCursor(""))
        assertNull(CursorPaging.idCursor("   "))
        assertEquals(41L, CursorPaging.idCursor("41"))

        listOf("0", "-1", "x", "1.5").forEach { raw ->
            val failure = assertThrows(InvalidFields::class.java, { CursorPaging.idCursor(raw) }, raw)

            assertEquals(mapOf("cursor" to "INVALID"), fieldsOf(failure), raw)
        }
    }

    @Test
    fun `alerts answer items and page with the server and node name on every row`() {
        val alert = ServerAlert(id = 7, kind = ServerAlertKind.SERVER_CRASHED, serverId = 3, nodeId = 2, message = "m")
        val orphan = ServerAlert(id = 6, kind = ServerAlertKind.NODE_OFFLINE, nodeId = 2, message = "n")

        val body = PanelGetAlertsAPI.payload(listOf(alert, orphan), mapOf(3L to "Survival"), mapOf(2L to "node-1"), 50, "6")

        assertEquals(setOf("items", "page"), body.keys)
        assertEquals(mapOf<String, Any?>("size" to 50, "nextCursor" to "6"), pageOf(body))

        val rows = itemsOf(body).map { it as JsonObject }

        assertEquals("Survival", rows[0].getString("serverName"))
        assertEquals("node-1", rows[0].getString("nodeName"))
        assertNull(rows[1].getString("serverName"))
        assertEquals(7L, rows[0].getLong("id"))
    }

    @Test
    fun `console search answers the matches as items and the source cursor as next cursor`() {
        val match = ConsoleDeepSearch.Match(ConsoleLineData(t = 5, l = "WARN", m = "hit"), "latest.log")
        val unfinished = ConsoleDeepSearch.Page(listOf(match), "eyJ2IjoxfQ", false, 1, 4, 1234L, true)

        val body = PanelSearchServerConsoleAPI.payload(unfinished, "hit", 200)

        assertEquals(setOf("items", "page", "query", "done", "scannedFiles", "totalFiles", "scannedBytes", "capped"), body.keys)
        assertEquals(mapOf<String, Any?>("size" to 200, "nextCursor" to "eyJ2IjoxfQ"), pageOf(body))
        assertEquals("latest.log", (itemsOf(body)[0] as JsonObject).getString("f"))
        assertEquals("hit", (itemsOf(body)[0] as JsonObject).getString("m"))
        assertEquals(false, body["done"])

        val finished = ConsoleDeepSearch.Page(listOf(), null, true, 4, 4, 9L, false)

        assertNull(pageOf(PanelSearchServerConsoleAPI.payload(finished, "hit", 200))["nextCursor"])
        assertEquals(true, PanelSearchServerConsoleAPI.payload(finished, "hit", 200)["done"])
    }

    @Test
    fun `server activity keeps its default of fifty`() {
        assertEquals(50, PanelGetServerActivityAPI.DEFAULT_LIMIT)
    }

    // ---- notifications ----------------------------------------------------------------------

    private class TestNotification : NotificationType

    private fun notification(id: Long) = PanelNotification(id = id, userId = 1, type = TestNotification())

    @Test
    fun `panel notifications answer items and a cursor that is the last id when more exist`() {
        val body = PanelNotificationPage.payload(
            listOf(notification(9), notification(8)), 1, { "plugin" }, 10, true, mapOf("notReadCount" to 2L)
        )

        assertEquals(setOf("items", "page", "notReadCount"), body.keys)
        assertEquals(mapOf<String, Any?>("size" to 10, "nextCursor" to "8"), pageOf(body))

        val first = itemsOf(body)[0] as Map<*, *>

        assertEquals(9L, first["id"])
        assertEquals("plugin", first["pluginId"])
        assertEquals(true, first["isPersonal"])
    }

    @Test
    fun `panel notifications on the last page have no next cursor`() {
        val body = PanelNotificationPage.payload(listOf(notification(3)), 1, { null }, 10, false, mapOf())

        assertNull(pageOf(body)["nextCursor"])
        assertNull(pageOf(PanelNotificationPage.payload(listOf(), 1, { null }, 10, true, mapOf()))["nextCursor"])
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

    private fun call(
        api: Api,
        route: String,
        query: String,
        answer: (RoutingContext) -> JsonObject
    ): Pair<Int, JsonObject> {
        val vertx = Vertx.vertx()
        val previous = runCatching { Main.applicationContext }.getOrNull()

        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("PanelPagingBTest") })
            refresh()
        }

        try {
            val repository = SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))
            val router = Router.router(vertx)

            router.route(HttpMethod.GET, route)
                .handler(api.getValidationHandler(repository))
                .handler { context ->
                    context.response().putHeader("content-type", "application/json").end(answer(context).encode())
                }
                .failureHandler(FailureApi().getFailureHandler())

            val port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
            val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
            val url = route.replace(":id", "7") + query

            return client.request(HttpMethod.GET, port, "127.0.0.1", url)
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

    private fun pageNumberCall(api: Api, query: String, defaultSize: Int = Paging.DEFAULT_SIZE) =
        call(api, "/list", query) { context ->
            val page = Paging.request(context, defaultSize)

            JsonObject().put("number", page.number).put("size", page.size)
        }

    private fun cursorCall(api: Api, route: String, query: String) = call(api, route, query) { context ->
        val limit = CursorPaging.limit(context, 50, PanelGetAlertsAPIMax)

        JsonObject().put("limit", limit).put("cursor", CursorPaging.idCursor(context))
    }

    private val pageEndpoints: List<Triple<String, Api, Int>> by lazy {
        listOf(
            Triple("activity logs", bare(PanelGetActivityLogsAPI::class.java), 10),
            Triple("locales", bare(PanelGetLocalesAPI::class.java), 10),
            Triple("banned ips", bare(PanelGetMaintenanceBannedIpsAPI::class.java), PanelGetMaintenanceBannedIpsAPI.DEFAULT_PAGE_SIZE)
        )
    }

    private val cursorEndpoints: List<Triple<String, Api, String>> by lazy {
        listOf(
            Triple("alerts", bare(PanelGetAlertsAPI::class.java), "/list"),
            Triple("server activity", bare(PanelGetServerActivityAPI::class.java), "/servers/:id/list")
        )
    }

    @Test
    fun `every page number endpoint accepts page and pageSize`() {
        pageEndpoints.forEach { (name, api, _) ->
            val (status, json) = pageNumberCall(api, "?page=2&pageSize=25")

            assertEquals(200, status, name)
            assertEquals(2, json.getInteger("number"), name)
            assertEquals(25, json.getInteger("size"), name)
        }
    }

    @Test
    fun `every page number endpoint keeps its old page size by default`() {
        pageEndpoints.forEach { (name, api, defaultSize) ->
            val (status, json) = pageNumberCall(api, "", defaultSize)

            assertEquals(200, status, name)
            assertEquals(defaultSize, json.getInteger("size"), name)
            assertEquals(1, json.getInteger("number"), name)
        }
    }

    @Test
    fun `every page number endpoint refuses an out of range page size with INVALID_FIELDS`() {
        pageEndpoints.forEach { (name, api, defaultSize) ->
            val (status, json) = pageNumberCall(api, "?pageSize=101&page=0", defaultSize)

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
    fun `the activity log keeps its search and locale parameters beside the page`() {
        val (status, json) = pageNumberCall(bare(PanelGetActivityLogsAPI::class.java), "?search=login&locale=en&page=3&pageSize=5")

        assertEquals(200, status)
        assertEquals(3, json.getInteger("number"))
        assertEquals(5, json.getInteger("size"))
    }

    @Test
    fun `every cursor endpoint accepts limit and cursor`() {
        cursorEndpoints.forEach { (name, api, route) ->
            val (status, json) = cursorCall(api, route, "?limit=20&cursor=41")

            assertEquals(200, status, name)
            assertEquals(20, json.getInteger("limit"), name)
            assertEquals(41L, json.getLong("cursor"), name)
        }
    }

    @Test
    fun `every cursor endpoint starts at the newest row with fifty by default and an empty cursor`() {
        cursorEndpoints.forEach { (name, api, route) ->
            val (status, json) = cursorCall(api, route, "?cursor=")

            assertEquals(200, status, name)
            assertEquals(50, json.getInteger("limit"), name)
            assertNull(json.getLong("cursor"), name)
        }
    }

    @Test
    fun `every cursor endpoint refuses a limit outside the range and a cursor that is not an id`() {
        cursorEndpoints.forEach { (name, api, route) ->
            val (status, json) = cursorCall(api, route, "?limit=500")

            assertEquals(400, status, name)
            assertEquals("INVALID_FIELDS", json.getJsonObject("error").getString("code"), name)
            assertEquals(mapOf("limit" to "OUT_OF_RANGE"), json.getJsonObject("error").getJsonObject("fields").map, name)

            val (cursorStatus, cursorJson) = cursorCall(api, route, "?cursor=abc")

            assertEquals(400, cursorStatus, name)
            assertEquals(mapOf("cursor" to "INVALID"), cursorJson.getJsonObject("error").getJsonObject("fields").map, name)
        }
    }

    @Test
    fun `the console search accepts limit and cursor, and server activity no longer reads before`() {
        val api = bare(PanelSearchServerConsoleAPI::class.java)

        val (status, json) = call(api, "/servers/:id/console/search", "?query=a&limit=10&cursor=abc") { context ->
            JsonObject().put("limit", CursorPaging.raw(context, "limit")).put("cursor", CursorPaging.raw(context, "cursor"))
        }

        assertEquals(200, status)
        assertEquals("10", json.getString("limit"))
        assertEquals("abc", json.getString("cursor"))

        val activity = File("src/main/kotlin/com/panomc/platform/route/api/panel/server/PanelGetServerActivityAPI.kt")

        if (activity.exists()) {
            assertFalse(activity.readText().contains("\"before\""))
        }
    }

    // ---- no old shape left --------------------------------------------------------------------

    private val mainSources: List<File> by lazy {
        val folder = File("src/main/kotlin/com/panomc/platform/route/api")

        if (folder.exists()) folder.walkTopDown().filter { it.extension == "kt" }.toList() else listOf()
    }

    @Test
    fun `no endpoint source reads or writes totalPage any more`() {
        val word = Regex("\\btotalPage\\b")

        mainSources.forEach { assertFalse(word.containsMatchIn(it.readText()), it.path) }
    }

    @Test
    fun `no panel list endpoint answers data and meta or a totalCount beside its list`() {
        val folder = File("src/main/kotlin/com/panomc/platform/route/api/panel")

        if (!folder.exists()) {
            return
        }

        val offenders = listOf(
            "PanelGetActivityLogsAPI.kt", "locale/PanelGetLocalesAPI.kt", "locale/PanelGetLocaleTranslationsAPI.kt",
            "locale/PanelUpdateLocaleTranslationsAPI.kt", "settings/PanelGetLicensesAPI.kt",
            "settings/PanelBannedIpsMigrationUploadAPI.kt", "settings/PanelBannedPlayersMigrationUploadAPI.kt",
            "settings/PanelAuthMeMigrationUploadAPI.kt", "maintenance/PanelGetMaintenanceBannedIpsAPI.kt",
            "plugins/PanelGetPluginsAPI.kt"
        ).filter { name ->
            val text = folder.resolve(name).readText()

            text.contains("\"meta\" to") || text.contains("\"totalCount\" to") || text.contains("\"data\" to")
        }

        assertEquals(listOf<String>(), offenders)
    }

    private companion object {
        /** The alerts endpoint's `MAX_LIMIT`, as the router test passes it to the reader. */
        const val PanelGetAlertsAPIMax = 200
    }
}
