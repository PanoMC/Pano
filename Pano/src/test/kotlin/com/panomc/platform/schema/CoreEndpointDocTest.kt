package com.panomc.platform.schema

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.model.Locale
import com.panomc.platform.db.model.Notification
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.db.model.TicketMessage
import com.panomc.platform.model.Api
import com.panomc.platform.model.Error
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Route
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.route.RouteEntry
import com.panomc.platform.route.api.GetLocalesAPI
import com.panomc.platform.route.api.GetSitemapAPI
import com.panomc.platform.route.api.notification.GetNotificationsAPI
import com.panomc.platform.route.api.plugins.GetPluginTranslationsAPI
import com.panomc.platform.route.api.posts.GetPostPreviewAPI
import com.panomc.platform.route.api.posts.GetPostsService
import com.panomc.platform.route.api.server.GetServersAPI
import com.panomc.platform.route.api.ticket.GetTicketsService
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.api.SitemapEntry
import io.vertx.core.json.JsonArray
import com.panomc.platform.api.ErrorStandIn
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchema
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.OutputFormat
import io.vertx.json.schema.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy

/**
 * Doc 04 section 5 (Coverage) and slice B2: every public core endpoint declares a `doc`.
 *
 * No test can start the router, so the core route list is loaded by hand: every `@Endpoint` class of the core is
 * built with stand-in constructor arguments (the constructors only store them) and turned into the same
 * [RouteEntry] the router registers. The documents are then checked against the bodies the real code writes.
 */
class CoreEndpointDocTest {
    // the hand-loaded route list

    @Test
    fun `every core endpoint class can be loaded by hand`() {
        assertEquals(listOf<String>(), loaded.failures)
        assertTrue(loaded.entries.size > 250, "expected the whole core route list, got ${loaded.entries.size}")
    }

    @Test
    fun `no public core entry lacks a doc`() {
        val missing = loaded.entries
            .filter { it.stability == Stability.PUBLIC && it.doc == null }
            .map { "${it.method} ${it.path} (${it.routeClass.simpleName})" }
            .sorted()

        assertEquals(listOf<String>(), missing)
    }

    @Test
    fun `every doc is usable and its errors resolve to a declared code`() {
        val problems = mutableListOf<String>()

        loaded.entries.forEach { entry ->
            val doc = entry.doc ?: return@forEach

            doc.problem()?.let { problems.add("${entry.routeClass.simpleName}: $it") }

            if (doc.summary.isBlank()) {
                problems.add("${entry.routeClass.simpleName}: empty summary")
            }

            doc.errors.forEach { type ->
                val error = ErrorStandIn.create(type.java)

                if (error == null) {
                    problems.add("${entry.routeClass.simpleName}: ${type.simpleName} cannot be built (no all-default constructor and no stand-in arguments), so the document would drop it")
                } else if (error.getStatusCode() !in 400..599) {
                    problems.add("${entry.routeClass.simpleName}: ${type.simpleName} is not an error status")
                }
            }

            if (doc.errors.size != doc.errors.toSet().size) {
                problems.add("${entry.routeClass.simpleName}: lists an error twice")
            }
        }

        assertEquals(listOf<String>(), problems)
    }

    @Test
    fun `a list endpoint declares a page item and the page errors`() {
        val problems = loaded.entries
            .filter { it.stability == Stability.PUBLIC }
            .mapNotNull { entry -> entry.doc?.let { entry to it } }
            .filter { (_, doc) -> doc.paginatedItem != null }
            .filter { (_, doc) ->
                val codes = doc.errors.mapNotNull { ErrorStandIn.create(it.java)?.code }

                "INVALID_FIELDS" !in codes || "PAGE_NOT_FOUND" !in codes
            }
            .map { (entry, _) -> entry.routeClass.simpleName }

        assertEquals(listOf<String>(), problems)
    }

    @Test
    fun `the core document lists no public operation as undocumented`() {
        val document = JsonObject(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, loaded.entries).encode())
        val undocumented = mutableListOf<String>()

        document.getJsonObject("paths").forEach { (path, item) ->
            (item as JsonObject).forEach { (method, operation) ->
                if ((operation as JsonObject).getBoolean("x-pano-undocumented") == true) {
                    undocumented.add("${method.uppercase()} $path ${operation.getString("operationId")}")
                }
            }
        }

        assertEquals(listOf<String>(), undocumented)
    }

    @Test
    fun `the generated core document is valid OpenAPI and resolves every reference`() {
        val document = JsonObject(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, loaded.entries).encode())

        val result = metaSchema.validate(document)

        assertTrue(
            result.valid == true,
            "not valid OpenAPI 3.1:\n" + result.errors.orEmpty().map { "${it.instanceLocation}: ${it.error}" }.distinct().joinToString("\n")
        )

        val components = document.getJsonObject("components").getJsonObject("schemas")

        listOf("Post", "PostSummary", "PostDetail", "User", "Ticket", "TicketDetail", "TicketMessage", "TicketCategory", "Notification", "Server", "Session", "Locale").forEach {
            assertTrue(components.containsKey(it), "components.schemas.$it")
        }

        val refs = mutableSetOf<String>()

        fun walk(node: Any?) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (key == "\$ref" && value is String) refs.add(value)
                    walk(value)
                }

                is JsonArray -> node.forEach { walk(it) }
            }
        }

        walk(document)

        refs.filter { it.startsWith("#/") }.forEach { ref ->
            var target: Any? = document

            ref.removePrefix("#/").split('/').forEach { part -> target = (target as? JsonObject)?.getValue(part) }

            assertTrue(target != null, "dangling $ref")
        }
    }

    @Test
    fun `the paths that are binary or empty are marked as such`() {
        val byClass = loaded.entries.associateBy { it.routeClass.simpleName }

        listOf("GetFaviconAPI", "GetWebsiteLogoAPI", "GetPostThumbnailAPI", "GetThemeSettingResourceAPI", "GetPluginUiFileAPI", "GetProfilePictureAPI")
            .forEach { assertTrue(byClass.getValue(it).doc!!.binary, it) }

        assertEquals(null, byClass.getValue("GetFaviconAPI").doc!!.responseSchema)
    }

    // the documents against the bodies the real code writes (the dev-mode check of Api.sendResult)

    @Test
    fun `a post page matches postDetail with and without neighbours`() {
        val post = Post(id = 4, title = "Hello", writerUserId = 1, text = "<p>Body</p>", thumbnailUrl = "/t.png", url = "hello", categoryId = 2)
        val category = PostCategory(id = 2, title = "News", url = "news")
        val other = Post(id = 5, title = "Next", writerUserId = 1, text = "", thumbnailUrl = "", url = "next")

        val doc = docOf("GetPostDetailAPI")

        assertEquals(listOf<String>(), doc.check(wire(GetPostPreviewAPI.body(post, category, "steve", null, null))))
        assertEquals(listOf<String>(), doc.check(wire(GetPostPreviewAPI.body(post, null, null, other, other))))
        assertEquals(SchemaJson.of(doc.response!!), SchemaJson.of(docOf("GetPostPreviewAPI").response!!))
    }

    @Test
    fun `a post page with a broken neighbour is rejected`() {
        val body = JsonObject()
            .put("post", wire(GetPostPreviewAPI.body(Post(id = 1, title = "t", writerUserId = 1, text = "", thumbnailUrl = "", url = "t"), null, null, null, null)).getJsonObject("post"))
            .put("previousPost", JsonObject().put("id", 1))
            .put("nextPost", "-")

        assertTrue(docOf("GetPostDetailAPI").check(body).isNotEmpty())
    }

    @Test
    fun `the lists written by the real services match their docs`() {
        val post = Post(id = 4, title = "Hello", writerUserId = 1, text = "<p>Body</p>", thumbnailUrl = "/t.png", url = "hello", categoryId = 2)
        val category = PostCategory(id = 2, title = "News", url = "news")

        assertEquals(
            listOf<String>(),
            docOf("GetPostsAPI").check(wire(GetPostsService.payload(category, listOf(post), mapOf(1L to "steve"), mapOf(2L to category), 1, PageRequest(1, 5))))
        )

        val ticket = Ticket(id = 3, title = "Help", userId = 1, categoryId = 7)
        val ticketCategory = TicketCategory(id = 7, title = "Bugs", url = "bugs")

        assertEquals(
            listOf<String>(),
            docOf("GetTicketsAPI").check(wire(GetTicketsService.payload(null, listOf(ticket), mapOf(7L to ticketCategory), "steve", 1, PageRequest(1, 10))))
        )

        assertEquals(
            listOf<String>(),
            docOf("GetTicketCategoriesAPI").check(wire(Paging.response(listOf(ticketCategory), 1, PageRequest(1, 100))))
        )

        val notification = Notification(id = 9, userId = 1, type = object : NotificationType {})

        assertEquals(
            listOf<String>(),
            docOf("GetNotificationsAPI").check(wire(GetNotificationsAPI.payload(listOf(notification), 1, { null }, 1, PageRequest(1, 10))))
        )

        assertEquals(
            listOf<String>(),
            docOf("GetSitemapAPI").check(wire(GetSitemapAPI.payload(listOf(SitemapEntry("post", mapOf("url" to "hello"), 1700000000000), SitemapEntry("post", mapOf("url" to "b"))), PageRequest(1, 100))))
        )
    }

    @Test
    fun `an empty list and a page past the data keep the page shape`() {
        assertEquals(
            listOf<String>(),
            docOf("GetPostsAPI").check(wire(GetPostsService.payload(null, listOf(), mapOf(), mapOf(), 0, PageRequest(1, 5))))
        )
    }

    @Test
    fun `the servers locales and plugin texts match their docs`() {
        val server = Server(
            id = 2, name = "survival", motd = "Welcome", host = "10.0.0.5", port = 25565, playerCount = 3, maxPlayerCount = 20,
            type = ServerType.PAPER, version = "1.21.8", favicon = "", permissionGranted = true, status = ServerStatus.ONLINE,
            startTime = 0, aesKey = "secret", kind = ServerKind.LINKED
        )

        assertEquals(listOf<String>(), docOf("GetServersAPI").check(wire(GetServersAPI.payload(listOf(server), 2L, "play.example.com"))))

        val locale = Locale(id = 1, code = "en-US", name = "English", dateFnsCode = "enUS", derivatives = listOf("en"))

        assertEquals(listOf<String>(), docOf("GetLocalesAPI").check(wire(GetLocalesAPI.payload(listOf(locale)))))

        val texts = GetPluginTranslationsAPI.response("pano-plugin-x", "en-US", mapOf("en-US" to JsonObject().put("a", JsonObject().put("b", "c"))), mapOf())

        assertEquals(listOf<String>(), docOf("GetPluginTranslationsAPI").check(wire(texts)))
    }

    @Test
    fun `ticket messages and the ticket page match their docs`() {
        val message = TicketMessage(id = 1, userId = 1, ticketId = 3, message = "Hi")

        val row = mapOf(
            "id" to message.id, "userId" to message.userId, "ticketId" to message.ticketId, "username" to "steve",
            "message" to message.message, "date" to message.date, "panel" to message.panel
        )

        assertEquals(listOf<String>(), docOf("SendTicketMessageAPI").check(wire(mapOf("message" to row))))
        assertEquals(listOf<String>(), docOf("GetTicketMessagesAPI").check(wire(mapOf("messages" to listOf(row)))))

        val ticket = mapOf(
            "id" to 3, "username" to "steve", "title" to "Help", "category" to mapOf("title" to "-", "url" to "-"),
            "messages" to listOf(row), "status" to com.panomc.platform.util.TicketStatus.NEW, "date" to 1, "messageCount" to 1
        )

        assertEquals(listOf<String>(), docOf("GetTicketAPI").check(wire(mapOf("ticket" to ticket))))
        assertTrue(docOf("GetTicketAPI").check(wire(mapOf("ticket" to ticket - "status"))).isNotEmpty())
    }

    @Test
    fun `the session bodies of the sign-in endpoints match their docs`() {
        val cookie = JsonObject().put("csrfToken", "abc")
        val stored = JsonObject().put("sessionToken", "jwt").put("expiresAt", 1700000000000)

        listOf("LoginAPI", "CompletePendingAuthAPI").forEach { name ->
            assertEquals(listOf<String>(), docOf(name).check(cookie), name)
            assertEquals(listOf<String>(), docOf(name).check(stored), name)
        }

        val register = docOf("RegisterAPI")

        assertEquals(listOf<String>(), register.check(JsonObject().put("login", true).put("csrfToken", "abc")))
        assertEquals(listOf<String>(), register.check(JsonObject().put("login", true).put("sessionToken", "jwt").put("expiresAt", 1)))
        assertEquals(listOf<String>(), register.check(JsonObject()))
        assertTrue(register.check(JsonObject().put("login", "yes")).isNotEmpty())
    }

    @Test
    fun `the small bodies match their docs`() {
        val samples = mapOf(
            "TrackPostViewAPI" to JsonObject().put("counted", true),
            "CreateTicketAPI" to JsonObject().put("id", 4),
            "GetQuickNotificationsAPI" to JsonObject().put("items", JsonArray()).put("page", JsonObject().put("size", 5).putNull("nextCursor")).put("notificationCount", 0),
            "MarkQuickNotificationsAsReadAPI" to JsonObject().put("notificationCount", 0),
            "GetMoreNotificationsAPI" to JsonObject().put("items", JsonArray()).put("page", JsonObject().put("size", 10).put("nextCursor", "7")).put("notificationCount", 2),
            "HealthAPI" to JsonObject(),
            "GetWidgetIndexAPI" to JsonObject().put("runtime", JsonObject().put("svelte", "5.0.0").put("hash", "h")).put("site", JsonObject().put("name", "n").put("url", "u").put("locale", "en")).put("widgets", JsonObject()),
            "GetMySessionsAPI" to JsonObject().put(
                "items",
                JsonArray().add(JsonObject().put("id", 1).put("ip", "-").put("userAgent", "-").put("lastActivityTime", 1).put("expireDate", 2).put("isCurrent", true))
            ),
            "GetPlayerProfileAPI" to JsonObject().put("registerDate", 1700000000000),
            "GetProfileAPI" to JsonObject().put("registerDate", 1700000000000).put("lastLoginDate", 1700000001000),
            "HomeSidebarAPI" to JsonObject().put("ipAddress", "play.example.com").put("serverGameVersion", "1.21.x")
                .putNull("mainServer").put("lastRegisteredUsers", JsonArray().add(JsonObject().put("username", "a").put("registerDate", 1).put("lastActivityTime", 0))),
            "PlayerProfileSidebarAPI" to JsonObject().put("lastActivityTime", 0).put("inGame", false).putNull("permissionGroupName").put("banned", false),
            "ProfileSidebarAPI" to JsonObject().put("lastActivityTime", 0).put("inGame", false).put("permissionGroupName", "Admin"),
            "SupportSidebarAPI" to JsonObject().put("items", JsonArray().add("steve")),
            "CreateWsTicketAPI" to JsonObject().put("ticket", "t").put("expiresIn", 30),
            "GetCredentialsAPI" to JsonObject().put("username", "steve").putNull("email").put("panelAccess", false).put("permissions", JsonArray()).put("admin", false),
            "VerifyLinkCodeAPI" to JsonObject().put("token", "t").put("username", "steve"),
            "GetFrontendUrlsAPI" to JsonObject().put("siteUrl", "").put("urls", JsonObject().put("login", "/login")),
            "GetRegisterAgreementAPI" to JsonObject().put("registerAgreement", "text"),
            "GetTranslationsAPI" to JsonObject().put("data", JsonObject()).put("meta", JsonObject().put("totalCount", 0).put("pluginAdminKeys", JsonObject())),
            "GetPluginPackagesAPI" to JsonObject().put("plugins", JsonObject().put("pano-plugin-x", JsonObject().put("namespace", "x"))),
            "LogoutAPI" to JsonObject(),
            "VisitorVisitAPI" to JsonObject()
        )

        samples.forEach { (name, body) -> assertEquals(listOf<String>(), docOf(name).check(body), name) }
    }

    @Test
    fun `site info matches its doc with and without a signed-in user`() {
        val base = JsonObject()
            .put("locale", "en-US").put("platformLocale", "en-US").put("allowUserLocaleSelection", true).put("developmentMode", false)
            .put("usageMode", "BOTH").put("websiteName", "Pano").put("websiteDescription", "").put("ipAddress", "play.example.com")
            .put("websiteUrl", "https://example.com").put("hasRegisterAgreement", false).put("supportEmail", "").put("keywords", JsonArray().add("a"))
            .put("panoVersion", "1.0.0").put("websiteLogoHash", "h").put("faviconHash", "h")
            .put("plugins", JsonObject().put("pano-plugin-x", JsonObject().putNull("version").put("uiHash", "u").put("dependencies", JsonArray())))
            .put("emailEnabled", false).put("isDemo", false).put("themeSettings", JsonObject()).putNull("homePage")

        val doc = docOf("GetSiteInfoAPI")

        assertEquals(listOf<String>(), doc.check(base))

        assertEquals(
            listOf<String>(),
            doc.check(base.copy().putNull("userLocaleCode").put("locales", JsonArray().add(wire(GetLocalesAPI.payload(listOf(Locale(id = 1, code = "en-US", name = "English", dateFnsCode = "enUS", derivatives = listOf()))))
                .getJsonArray("items").getJsonObject(0))))
        )

        assertTrue(doc.check(base.copy().also { it.remove("locale") }).isNotEmpty())
    }

    // helpers

    private fun docOf(simpleName: String): EndpointDoc =
        loaded.entries.first { it.routeClass.simpleName == simpleName }.doc ?: error("$simpleName has no doc")

    /** A body as it leaves the server: encoded and parsed again, so an enum is its name. */
    private fun wire(body: Map<String, Any?>) = JsonObject(JsonObject(body).encode())

    private val metaSchema: Validator by lazy {
        val text = CoreEndpointDocTest::class.java.getResourceAsStream("/openapi/openapi-3.1-schema.json")!!.use { String(it.readAllBytes()) }

        Validator.create(
            JsonSchema.of(JsonObject(text)),
            JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("app://openapi").setOutputFormat(OutputFormat.Basic)
        )
    }

    class Loaded(val entries: List<RouteEntry>, val failures: List<String>)

    companion object {
        private val objenesis = ObjenesisStd()

        /** The core route list, loaded once for the whole class. */
        val loaded: Loaded by lazy { load() }

        private fun load(): Loaded {
            val scanner = ClassPathScanningCandidateComponentProvider(false)

            scanner.addIncludeFilter(AnnotationTypeFilter(Endpoint::class.java))

            val classes = scanner.findCandidateComponents("com.panomc.platform")
                .mapNotNull(BeanDefinition::getBeanClassName)
                .map { Class.forName(it) }
                .filter { Route::class.java.isAssignableFrom(it) }
                .sortedBy { it.name }

            val entries = mutableListOf<RouteEntry>()
            val failures = mutableListOf<String>()

            classes.forEach { type ->
                try {
                    val route = build(type) as Route
                    val api = route as? Api

                    route.paths.forEach { path ->
                        entries.add(
                            RouteEntry(
                                method = path.routeType.vertxHttpMethod?.name() ?: "ALL",
                                path = ApiPaths.resolve(path.url, route.mount, route.namespace, null),
                                declared = path.url,
                                pluginId = null,
                                routeClass = type,
                                mount = route.mount,
                                namespace = route.namespace,
                                doc = api?.doc,
                                declaredStability = api?.stability,
                                deprecation = api?.deprecation,
                                validation = null
                            )
                        )
                    }
                } catch (e: Throwable) {
                    failures.add("${type.name}: ${e.javaClass.simpleName} ${e.message}")
                }
            }

            return Loaded(entries, failures)
        }

        /** The class built through its widest constructor with stand-ins, which the constructors only store. */
        private fun build(type: Class<*>): Any {
            val constructor: Constructor<*> = type.declaredConstructors
                .filter { !it.isSynthetic }
                .maxByOrNull { it.parameterCount }
                ?: error("no constructor")

            constructor.isAccessible = true

            return constructor.newInstance(*constructor.parameterTypes.map { standIn(it) }.toTypedArray())
        }

        private fun standIn(type: Class<*>): Any? = when {
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Double::class.javaPrimitiveType -> 0.0
            type == Float::class.javaPrimitiveType -> 0f
            type == Short::class.javaPrimitiveType -> 0.toShort()
            type == Byte::class.javaPrimitiveType -> 0.toByte()
            type == Char::class.javaPrimitiveType -> ' '
            type == String::class.java -> ""
            type == List::class.java -> emptyList<Any?>()
            type == Set::class.java -> emptySet<Any?>()
            type == Map::class.java -> emptyMap<Any?, Any?>()
            type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }

            else -> objenesis.newInstance(type)
        }
    }
}
