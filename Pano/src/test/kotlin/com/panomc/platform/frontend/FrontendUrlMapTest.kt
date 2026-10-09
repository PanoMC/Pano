package com.panomc.platform.frontend

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.mail.templates.ChangeEmailMail
import com.panomc.platform.mail.templates.ResetPasswordMail
import com.panomc.platform.ui.FrontendMode
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

/**
 * The front-end URL map (open front-end plan, doc 05 section 10.1): the four resolution steps, the
 * placeholder rules, the theme route config, a plugin's `frontend-targets.json` and the mails that
 * follow an override. The platform inputs (config, theme folder, database) are replaced by a fake.
 */
class FrontendUrlMapTest {
    private class FakeInputs : FrontendUrlInputs {
        var mode = FrontendMode.THEME
        var site = "https://site.example"
        var website = "https://pano.example"
        var overrides: Map<String, String> = emptyMap()
        var frontend: Map<String, Any?> = emptyMap()
        var routes: ThemeRouteMap? = null

        override fun mode() = mode
        override fun siteUrl() = site
        override fun websiteUrl() = website
        override fun overrides() = overrides
        override fun frontendUrls() = frontend
        override fun themeRoutes() = routes
    }

    private val inputs = FakeInputs()
    private val map = FrontendUrlMap(inputs, LoggerFactory.getLogger("test"))

    private val marketTargets = """
        { "store": "/store", "product": "/store/{slug}", "checkout": "/store/checkout",
          "order": { "path": "/store/order/{id}", "fallback": true }, "subscriptions": "/profile/subscriptions" }
    """.trimIndent()

    private fun routes(json: String) = ThemeRouteMap.fromCoreMeta(JsonObject(json))

    // --- the four steps ------------------------------------------------------

    @Test
    fun `a default site resolves every core target to the path it always had`() {
        assertEquals("https://site.example/login", map.url("auth.login"))
        assertEquals("https://site.example/reset-password", map.url("auth.reset-password"))
        assertEquals("https://site.example/register", map.url("auth.register"))
        assertEquals("https://site.example/profile", map.url("user.profile"))
        assertEquals("https://site.example/activate?token=abc", map.url("auth.activate", mapOf("token" to "abc")))
        assertEquals("/renew-password?token=x", map.path("auth.renew-password", mapOf("token" to "x")))
        assertEquals("/activate-new-email?token=x", map.path("auth.activate-new-email", mapOf("token" to "x")))
    }

    @Test
    fun `an unknown target is null`() {
        assertNull(map.url("nope.page"))
        assertNull(map.path("nope.page"))
        assertNull(map.template("nope.page"))
    }

    @Test
    fun `step 1 the admin override wins over the front-end urls and the theme`() {
        inputs.overrides = mapOf("auth.login" to "/sign-in")
        inputs.frontend = mapOf("auth.login" to "/from-frontend")
        inputs.routes = routes("""{ "rename": { "/login": "/from-theme" } }""")

        assertEquals("/sign-in", map.path("auth.login"))
        assertEquals("https://site.example/sign-in", map.url("auth.login"))
        assertEquals(UrlSource.OVERRIDE, map.targets().first { it.id == "auth.login" }.source)
    }

    @Test
    fun `an override may be an absolute URL and is used as it is`() {
        inputs.overrides = mapOf("auth.login" to "https://id.example.com/login")

        assertEquals("https://id.example.com/login", map.url("auth.login"))
        assertEquals("https://id.example.com/login", map.path("auth.login"))
        assertNull(map.routePath("auth.login"))
    }

    @Test
    fun `step 2 the active front-end urls entry wins over the theme`() {
        inputs.frontend = mapOf("auth.activate" to "/welcome/confirm?token={token}")
        inputs.routes = routes("""{ "rename": { "/activate": "/from-theme" } }""")

        assertEquals("/welcome/confirm?token=t1", map.path("auth.activate", mapOf("token" to "t1")))
        assertEquals(UrlSource.FRONTEND, map.targets().first { it.id == "auth.activate" }.source)
    }

    @Test
    fun `step 2 false means no such page and skips the theme`() {
        inputs.frontend = mapOf("auth.register" to false, "auth.login" to false)
        inputs.routes = routes("""{ "rename": { "/register": "/join" } }""")

        // not a fallback target: nothing serves it, callers omit the link
        assertNull(map.url("auth.register"))
        // a fallback target still has Pano's own page
        assertEquals("https://pano.example/_pano/auth.login", map.url("auth.login"))
    }

    @Test
    fun `step 3 a renamed route gives the public path`() {
        inputs.routes = routes("""{ "rename": { "/reset-password": "/forgot" } }""")

        assertEquals("/forgot", map.path("auth.reset-password"))
        assertEquals(UrlSource.THEME, map.targets().first { it.id == "auth.reset-password" }.source)
    }

    @Test
    fun `step 3 only applies in THEME mode`() {
        inputs.routes = routes("""{ "rename": { "/reset-password": "/forgot" } }""")

        for (mode in listOf(FrontendMode.CUSTOM_APP, FrontendMode.EXTERNAL, FrontendMode.NONE)) {
            inputs.mode = mode

            // no urls declared and no fallback page: nothing to link to
            assertNull(map.url("auth.reset-password"), mode.name)
            // a fallback target goes to Pano's page
            assertEquals("https://pano.example/_pano/auth.login", map.url("auth.login"), mode.name)
        }
    }

    @Test
    fun `step 3 a disabled route falls to the fallback page`() {
        map.registerPlugin("pano-plugin-market", "market", marketTargets)
        inputs.routes = routes("""{ "disable": ["/store/order/[id]", "/register"] }""")

        // the fallback page has no placeholder, so the parameter travels as a query parameter
        assertEquals("https://pano.example/_pano/market.order?id=9", map.url("market.order", mapOf("id" to "9")))
        assertEquals(UrlSource.FALLBACK, map.targets().first { it.id == "market.order" }.source)
        // no fallback page: null
        assertNull(map.url("auth.register"))
        // untouched ones keep their path
        assertEquals("https://site.example/store", map.url("market.store"))
    }

    @Test
    fun `step 4 is relative to website-url while steps 1 to 3 are relative to site-url`() {
        inputs.site = "https://www.example.com"
        inputs.website = "https://play.example.com"
        inputs.mode = FrontendMode.EXTERNAL

        assertEquals("https://play.example.com/_pano/auth.login", map.url("auth.login"))

        inputs.mode = FrontendMode.THEME

        assertEquals("https://www.example.com/login", map.url("auth.login"))
    }

    @Test
    fun `a core-meta rename moves market order and keeps the placeholder`() {
        map.registerPlugin("pano-plugin-market", "market", marketTargets)
        inputs.routes = routes(
            """{ "rename": { "/store": "/shop", "/store/[slug]": "/shop/[slug]", "/store/order/[id]": "/shop/o/[id]" } }"""
        )

        assertEquals("https://site.example/shop/o/42", map.url("market.order", mapOf("id" to "42")))
        assertEquals("/shop", map.path("market.store"))
        assertEquals("/shop/vip", map.path("market.product", mapOf("slug" to "vip")))
        assertEquals("https://site.example/shop/o/{id}", map.template("market.order"))
        // a path the theme did not rename stays
        assertEquals("/profile/subscriptions", map.path("market.subscriptions"))
    }

    // --- parameters ----------------------------------------------------------

    @Test
    fun `a missing parameter throws`() {
        val failure = assertThrows<IllegalArgumentException> { map.url("auth.activate") }

        assertTrue(failure.message!!.contains("token"))
        assertThrows<IllegalArgumentException> { map.path("auth.activate", mapOf("other" to "1")).also { } }
    }

    @Test
    fun `parameters the template does not name are appended as URL-encoded query parameters in order`() {
        assertEquals("/login?next=%2Fprofile%3Fa%3D1&lang=t%C3%BCrk", map.path("auth.login", linkedMapOf("next" to "/profile?a=1", "lang" to "türk")))
        // the template already has a query: joined with &
        assertEquals("/activate?token=a&via=mail", map.path("auth.activate", linkedMapOf("token" to "a", "via" to "mail")))
    }

    @Test
    fun `placeholder values are URL-encoded`() {
        assertEquals("/activate?token=a%2Fb%20c%3D", map.path("auth.activate", mapOf("token" to "a/b c=")))
    }

    @Test
    fun `template keeps the placeholders and is absolute`() {
        assertEquals("https://site.example/activate?token={token}", map.template("auth.activate"))
        assertEquals("/activate?token={token}", map.pathTemplate("auth.activate"))
        assertEquals("/activate", map.routePath("auth.activate"))
    }

    // --- plugin targets ------------------------------------------------------

    @Test
    fun `a plugin file adds targets prefixed with its namespace and unload drops them`() {
        map.registerPlugin("pano-plugin-market", "market", marketTargets)

        assertEquals("https://site.example/store/order/7", map.url("market.order", mapOf("id" to "7")))
        assertEquals("pano-plugin-market", map.target("market.order")!!.owner)
        assertTrue(map.target("market.order")!!.fallback)
        assertFalse(map.target("market.store")!!.fallback)
        assertEquals(listOf("market.checkout", "market.order", "market.product", "market.store", "market.subscriptions"),
            map.targets().filter { it.owner == "pano-plugin-market" }.map { it.id })

        map.unregisterPlugin("pano-plugin-market")

        assertNull(map.url("market.store"))
        assertNull(map.target("market.order"))
    }

    @Test
    fun `createsSession is read from the object form`() {
        map.registerPlugin(
            "pano-plugin-premium-login", "premium-login",
            """{ "callback": { "path": "/premium-login/callback", "fallback": true, "createsSession": true } }"""
        )
        map.fallbackPageCheck = { true }

        assertTrue(map.target("premium-login.callback")!!.createsSession)
    }

    @Test
    fun `fallback true without a registered page fails the plugin load and names the target`() {
        map.fallbackPageCheck = { false }

        val failure = assertThrows<FrontendTargetsRefusal> { map.registerPlugin("pano-plugin-market", "market", marketTargets) }

        assertTrue(failure.message!!.contains("market.order"), failure.message)
        // nothing was registered
        assertNull(map.target("market.store"))
    }

    @Test
    fun `fallback true with its page registered loads`() {
        map.fallbackPageCheck = { it == "market.order" }

        map.registerPlugin("pano-plugin-market", "market", marketTargets)

        assertNotNull(map.target("market.order"))
    }

    @Test
    fun `a refused file registers nothing`() {
        assertThrows<FrontendTargetsRefusal> { map.registerPlugin("p", "p", "[1, 2]") }
        assertThrows<FrontendTargetsRefusal> { map.registerPlugin("p", "p", """{ "a": "no-slash" }""") }
        assertThrows<FrontendTargetsRefusal> { map.registerPlugin("p", "p", """{ "a": "//evil.example" }""") }
        assertThrows<FrontendTargetsRefusal> { map.registerPlugin("p", "p", """{ "a": 5 }""") }
        assertThrows<FrontendTargetsRefusal> { map.registerPlugin("p", "p", """{ "bad key": "/x" }""") }
        assertTrue(map.targets().none { it.owner == "p" })
    }

    @Test
    fun `a plugin without the file has no targets and a core id cannot be taken over`() {
        map.registerPlugin("pano-plugin-x", "x", null)

        assertTrue(map.targets().none { it.owner == "pano-plugin-x" })

        // namespace "auth" would collide with core's auth.login: core keeps it
        map.registerPlugin("pano-plugin-auth", "auth", """{ "login": "/other", "extra": "/extra" }""")

        assertEquals("/login", map.path("auth.login"))
        assertEquals("/extra", map.path("auth.extra"))
    }

    // --- overrides validation ------------------------------------------------

    @Test
    fun `overrides are checked against the known targets and the location rule`() {
        map.registerPlugin("pano-plugin-market", "market", marketTargets)

        assertTrue(map.validateOverrides(mapOf("auth.login" to "/x", "market.order" to "https://a.example/o/{id}")).isEmpty())
        assertEquals(
            mapOf("nope.x" to "UNKNOWN_TARGET", "auth.login" to "INVALID_LOCATION", "auth.register" to "INVALID_LOCATION"),
            map.validateOverrides(
                linkedMapOf("nope.x" to "/x", "auth.login" to "javascript:alert(1)", "auth.register" to "//evil.example")
            )
        )
    }

    @Test
    fun `a location that is neither a site path nor an http URL is ignored when read`() {
        inputs.overrides = mapOf("auth.login" to "javascript:alert(1)")
        inputs.frontend = mapOf("auth.register" to "ftp://x")

        assertEquals("/login", map.path("auth.login"))
        assertEquals("/register", map.path("auth.register"))
    }

    // --- theme meta ----------------------------------------------------------

    @Test
    fun `urls of core-meta keep strings and false and drop the rest`() {
        val urls = PlatformFrontendUrlInputs.parseUrls(JsonObject("""{ "a": "/x", "b": false, "c": true, "d": 3, "e": null }"""))

        assertEquals(mapOf<String, Any?>("a" to "/x", "b" to false), urls)
        assertTrue(PlatformFrontendUrlInputs.parseUrls(null).isEmpty())
    }

    // --- mails ---------------------------------------------------------------

    private fun mailParameters() = MailManager.Companion.SystemParameters(
        "Site", inputs.website
    ) { target, params -> map.url(target, params) }

    @Test
    fun `the activation mail follows an override and a renamed route`() {
        val mail = ActivationMail("tok", "u", "e@x", "123456")

        assertEquals("https://site.example/activate?token=tok", mail.activationLink(mailParameters()))

        inputs.routes = routes("""{ "rename": { "/activate": "/welcome/confirm" } }""")

        assertEquals("https://site.example/welcome/confirm?token=tok", mail.activationLink(mailParameters()))

        inputs.overrides = mapOf("auth.activate" to "/my/confirm?token={token}")

        assertEquals("https://site.example/my/confirm?token=tok", mail.activationLink(mailParameters()))
    }

    @Test
    fun `the reset and change-email mails use their targets`() {
        inputs.mode = FrontendMode.NONE

        assertEquals("https://pano.example/_pano/auth.renew-password?token=r", ResetPasswordMail("r", "1").resetLink(mailParameters()))
        assertEquals("https://pano.example/_pano/auth.activate-new-email?token=c", ChangeEmailMail("c", "u", "n@x").confirmationLink(mailParameters()))
    }

    @Test
    fun `without a map a mail link is the old one`() {
        val plain = MailManager.Companion.SystemParameters("Site", "https://pano.example")

        assertEquals("https://pano.example/activate?token=t", ActivationMail("t", "u", "e", "1").activationLink(plain))
    }
}

/**
 * The beans that take the map in (mails, maintenance, usage-mode gate) are built by Spring in the real
 * platform; the unit tests above never see that. This builds the same wiring over stand-in collaborators.
 */
class FrontendUrlMapWiringTest {
    @Test
    fun `spring builds the map, its inputs and the mail manager that takes it`() {
        val objenesis = org.springframework.objenesis.ObjenesisStd()
        val context = org.springframework.context.annotation.AnnotationConfigApplicationContext()

        context.beanFactory.registerSingleton("configManager", objenesis.newInstance(com.panomc.platform.config.ConfigManager::class.java))
        context.beanFactory.registerSingleton("logger", LoggerFactory.getLogger("test"))
        context.beanFactory.registerSingleton("databaseManager", objenesis.newInstance(com.panomc.platform.db.DatabaseManager::class.java))
        context.beanFactory.registerSingleton("gson", com.google.gson.Gson())
        context.beanFactory.registerSingleton("vertx", io.vertx.core.Vertx.vertx())
        context.beanFactory.registerSingleton("i18nManager", objenesis.newInstance(com.panomc.platform.i18n.I18nManager::class.java))
        context.register(FrontendUrlMap::class.java, PlatformFrontendUrlInputs::class.java, MailManager::class.java)

        try {
            context.refresh()

            val map = context.getBean(FrontendUrlMap::class.java)
            val manager = context.getBean(MailManager::class.java)
            val field = MailManager::class.java.getDeclaredField("frontendUrlMap").apply { isAccessible = true }

            assertNotNull(map)
            assertTrue(field.get(manager) === map, "MailManager got the map")
        } finally {
            context.getBean(io.vertx.core.Vertx::class.java).close()
            context.close()
        }
    }
}

/** The route config of a theme (port of the theme-core route map). */
class ThemeRouteMapTest {
    @Test
    fun `rename keeps params, suffix and trailing slash`() {
        val routes = ThemeRouteMap(mapOf("/store" to "/shop", "/store/[slug]" to "/shop/[slug]", "/post/[url]" to "/news/[url]"))

        assertEquals("/shop", routes.toPublic("/store"))
        assertEquals("/shop/vip/?a=1#x", routes.toPublic("/store/vip/?a=1#x"))
        assertEquals("/news/hello", routes.toPublic("/post/hello"))
        assertEquals("/other", routes.toPublic("/other"))
        assertEquals("https://x.example/store", routes.toPublic("https://x.example/store"))
        assertEquals("/store", routes.toCanonical("/shop"))
        assertEquals("/post/hello", routes.toCanonical("/news/hello"))
        assertTrue(routes.isRenamedAway("/store"))
        assertFalse(routes.isRenamedAway("/shop"))
    }

    @Test
    fun `a template with braces maps through the same rules`() {
        val routes = ThemeRouteMap(mapOf("/store/[slug]" to "/shop/[slug]", "/activate" to "/confirm"))

        assertEquals("/shop/{slug}", routes.publicTemplate("/store/{slug}"))
        assertEquals("/confirm?token={token}", routes.publicTemplate("/activate?token={token}"))
        assertEquals("/other/{x}", routes.publicTemplate("/other/{x}"))
    }

    @Test
    fun `disable is checked on the canonical path and the more specific pattern wins`() {
        val routes = ThemeRouteMap(disable = listOf("/rules", "/store/[slug]"))

        assertTrue(routes.isDisabled("/rules"))
        assertTrue(routes.isDisabled("/store/vip"))
        assertFalse(routes.isDisabled("/store"))
        assertNull(routes.publicTemplate("/store/{slug}"))
        assertNull(routes.toCanonical("/rules"))
    }

    @Test
    fun `a bad config is refused like the build does`() {
        assertThrows<IllegalArgumentException> { ThemeRouteMap(mapOf("/a/[x]" to "/b/[y]")) }
        assertThrows<IllegalArgumentException> { ThemeRouteMap(mapOf("/a" to "/c", "/b" to "/c")) }
        assertThrows<IllegalArgumentException> { ThemeRouteMap(mapOf("a" to "/c")) }
        assertThrows<IllegalArgumentException> { ThemeRouteMap(disable = listOf("/a/[...r]/b")) }
        assertThrows<IllegalArgumentException> { ThemeRouteMap(mapOf("/a/[x]" to "/b/[...x]")) }
    }

    @Test
    fun `routes of core-meta are read and an empty one changes nothing`() {
        assertTrue(ThemeRouteMap.fromCoreMeta(null).isIdentity)
        assertTrue(ThemeRouteMap.fromCoreMeta(JsonObject("""{ "add": ["/staff"] }""")).isIdentity)

        val routes = ThemeRouteMap.fromCoreMeta(JsonObject("""{ "rename": { "/store": "/shop" }, "disable": ["/rules"], "add": [] }"""))

        assertEquals("/shop", routes.toPublic("/store"))
        assertTrue(routes.isDisabled("/rules"))
    }
}

/** A saved or refreshed descriptor reaches the URL map at once, without a restart (open-after-stage4 defect 8). */
class DescriptorReachesUrlMapTest {
    private fun provider(value: Any): org.springframework.beans.factory.ObjectProvider<Any> =
        object : org.springframework.beans.factory.ObjectProvider<Any> {
            override fun getObject(): Any = value
            override fun getObject(vararg args: Any?): Any = value
            override fun getIfAvailable(): Any? = value
            override fun getIfUnique(): Any? = value
        }

    @Suppress("UNCHECKED_CAST")
    private fun inputs(): PlatformFrontendUrlInputs {
        val objenesis = org.springframework.objenesis.ObjenesisStd()
        val ui = objenesis.newInstance(com.panomc.platform.UIManager::class.java)

        com.panomc.platform.UIManager::class.java.getDeclaredField("frontendMode")
            .apply { isAccessible = true }
            .set(ui, FrontendMode.EXTERNAL)

        return PlatformFrontendUrlInputs(
            objenesis.newInstance(com.panomc.platform.config.ConfigManager::class.java),
            provider(ui) as org.springframework.beans.factory.ObjectProvider<com.panomc.platform.UIManager>,
            provider(Any()) as org.springframework.beans.factory.ObjectProvider<com.panomc.platform.db.DatabaseManager>,
            LoggerFactory.getLogger("test")
        )
    }

    @Test
    fun `the urls of a new descriptor are used at once and a cleared one is forgotten`() {
        val inputs = inputs()
        val map = FrontendUrlMap(inputs, LoggerFactory.getLogger("test"))

        assertTrue(inputs.frontendUrls().isEmpty())

        inputs.useDescriptor(JsonObject("""{ "id": "x", "urls": { "auth.login": "https://app.example/sign-in", "auth.register": false } }"""))

        assertEquals("https://app.example/sign-in", inputs.frontendUrls()["auth.login"])
        assertEquals(false, inputs.frontendUrls()["auth.register"])
        assertNull(map.path("auth.register"))

        inputs.useDescriptor(null)

        assertTrue(inputs.frontendUrls().isEmpty())
    }

    @Test
    fun `a descriptor without urls leaves the map empty`() {
        val inputs = inputs()

        inputs.useDescriptor(JsonObject("""{ "id": "x" }"""))

        assertTrue(inputs.frontendUrls().isEmpty())
    }
}
