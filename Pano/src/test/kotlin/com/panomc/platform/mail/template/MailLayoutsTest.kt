package com.panomc.platform.mail.template

import com.panomc.platform.db.model.MailLayout
import com.panomc.platform.db.model.MailSetting
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MailLayoutsTest {
    private val mainLayout = "<main>{{{content}}}</main>"

    private val coreIndex = MailTemplateIndex.parse(
        JsonObject(
            """
            { "mails": [
              { "key": "hello", "name": { "en-US": "Hello", "tr": "Merhaba" } },
              { "key": "bare", "name": { "en-US": "Bare" }, "layout": "none" },
              { "key": "fancy", "name": { "en-US": "Fancy" }, "layout": "shop:receipt" }
            ] }
            """.trimIndent()
        )
    )

    private val pluginIndex = MailTemplateIndex.parse(
        JsonObject(
            """
            { "mails": [ { "key": "order", "layout": "shop:receipt" } ],
              "layouts": [ { "key": "receipt", "name": { "en-US": "Receipt", "tr": "Fiş" } } ] }
            """.trimIndent()
        )
    )

    private val coreFiles = mapOf(
        "hello/default/en-US.hbs" to "---\nsubject: Hi\nbuttonUrl: https://x.example\n---\n<p>hello</p>",
        "bare/default/en-US.hbs" to "---\nsubject: Bare\n---\n<p>bare</p>",
        "fancy/default/en-US.hbs" to "---\nsubject: Fancy\n---\n<p>fancy</p>"
    )

    private val pluginFiles = mapOf(
        "order/default/en-US.hbs" to "---\nsubject: Order\n---\n<p>order</p>",
        "layouts/receipt.hbs" to "<receipt>{{{content}}}</receipt>"
    )

    private class MemoryStore(
        val layoutRows: MutableList<MailLayout> = mutableListOf(),
        val settings: MutableList<MailSetting> = mutableListOf()
    ) : MailLayoutStore {
        override suspend fun layout(sqlClient: SqlClient?, owner: String, key: String) =
            layoutRows.firstOrNull { it.owner == owner && it.layoutKey == key }

        override suspend fun layouts(sqlClient: SqlClient?) = layoutRows.toList()

        override suspend fun mailSetting(sqlClient: SqlClient?, owner: String, mailKey: String) =
            settings.firstOrNull { it.owner == owner && it.mailKey == mailKey }?.layout

        override suspend fun mailSettings(sqlClient: SqlClient?) = settings.toList()
    }

    private val system = MailTemplateService.systemVariables("Craft", "https://craft.example", "en-US")

    private fun service(store: MemoryStore = MemoryStore(), withPlugin: Boolean = true): MailTemplateService {
        val registry = MailTemplateTestSupport.memoryRegistry(coreIndex, coreFiles)

        if (withPlugin) {
            registry.registerPlugin(MailTemplateOwner("shop", "shop", "Shop", pluginIndex) { pluginFiles[it] })
        }

        return MailTemplateService(registry, { _, _, _, _, _ -> null }, { mainLayout }, store)
    }

    private fun send(service: MailTemplateService, owner: String, key: String) = runBlocking {
        service.renderForSend(owner, key, "default", "en-US", emptyMap(), system, null).html
    }

    @Test
    fun `layouts list the main one first, then bundled ones, then custom ones by name`() {
        val store = MemoryStore(
            mutableListOf(
                MailLayout(owner = "custom", layoutKey = "zeta", name = "Zeta", body = "z{{{content}}}"),
                MailLayout(owner = "custom", layoutKey = "alpha", name = "Alpha", body = "a{{{content}}}"),
                MailLayout(owner = "shop", layoutKey = "receipt", body = "<r2>{{{content}}}</r2>")
            )
        )

        val layouts = runBlocking { service(store).layouts(null) }

        assertEquals(listOf("core:default", "shop:receipt", "custom:alpha", "custom:zeta"), layouts.map { it.id })
        assertEquals(listOf(true, true, false, false), layouts.map { it.builtIn })
        assertEquals(listOf(false, false, true, true), layouts.map { it.deletable })
        assertEquals(listOf(false, true, false, false), layouts.map { it.customized })
        assertEquals("Fiş", layouts[1].name("tr"))
        assertEquals("Shop", layouts[1].pluginName)
        assertEquals("Ana düzen", layouts[0].name("tr"))
    }

    @Test
    fun `a bundled layout is its override, else its file, and a custom one exists only as a row`() {
        val store = MemoryStore()
        val service = service(store)

        runBlocking {
            assertEquals("<receipt>{{{content}}}</receipt>", service.layout("shop:receipt", null)!!.body)
            assertEquals(mainLayout, service.layout("core:default", null)!!.body)
            assertNull(service.layout("custom:nope", null))
            assertNull(service.layout("shop:missing", null))
            assertNull(service.layout("not an id", null))

            store.layoutRows.add(MailLayout(owner = "shop", layoutKey = "receipt", body = "<edited>{{{content}}}</edited>"))
            store.layoutRows.add(MailLayout(owner = "custom", layoutKey = "mine", name = "Mine", body = "<mine>{{{content}}}</mine>"))

            assertEquals("<edited>{{{content}}}</edited>", service.layout("shop:receipt", null)!!.body)
            assertTrue(service.layout("shop:receipt", null)!!.customized)
            assertEquals("<receipt>{{{content}}}</receipt>", service.bundledLayout("shop:receipt"))
            assertNull(service.bundledLayout("custom:mine"))
            assertEquals("<mine>{{{content}}}</mine>", service.layout("custom:mine", null)!!.body)
        }
    }

    @Test
    fun `mails are sent with their declared layout, the admin's choice, or none`() {
        val store = MemoryStore()
        val service = service(store)

        assertEquals("<main><p>hello</p></main>", send(service, "core", "hello"))
        assertEquals("<receipt><p>order</p></receipt>", send(service, "shop", "order"))
        assertEquals("<p>bare</p>", send(service, "core", "bare"))

        store.layoutRows.add(MailLayout(owner = "custom", layoutKey = "mine", name = "Mine", body = "<mine>{{{content}}}</mine>"))
        store.settings.add(MailSetting(owner = "core", mailKey = "hello", layout = "custom:mine"))
        store.settings.add(MailSetting(owner = "core", mailKey = "bare", layout = "core:default"))
        store.settings.add(MailSetting(owner = "shop", mailKey = "order", layout = "none"))

        assertEquals("<mine><p>hello</p></mine>", send(service, "core", "hello"))
        assertEquals("<main><p>bare</p></main>", send(service, "core", "bare"))
        assertEquals("<p>order</p>", send(service, "shop", "order"))
    }

    @Test
    fun `a deleted custom layout or an unloaded plugin's layout falls back to the main one`() {
        val store = MemoryStore(settings = mutableListOf(MailSetting(owner = "core", mailKey = "hello", layout = "custom:gone")))
        val service = service(store, withPlugin = false)

        runBlocking {
            assertEquals("core:default", service.effectiveLayoutId("core", "hello", null))
            assertEquals("core:default", service.effectiveLayoutId("core", "fancy", null))
            assertEquals("none", service.effectiveLayoutId("core", "bare", null))

            val effective = service.effectiveLayouts(null)

            assertEquals("core:default", effective["core" to "hello"])
            assertEquals("core:default", effective["core" to "fancy"])
            assertEquals("none", effective["core" to "bare"])
        }

        assertEquals("<main><p>fancy</p></main>", send(service, "core", "fancy"))
    }

    @Test
    fun `an unsaved layout choice overrides the mail's own`() {
        val service = service()

        runBlocking {
            assertNull(service.layoutForMail("core", "hello", null, "none"))
            assertEquals("shop:receipt", service.layoutForMail("core", "hello", null, "shop:receipt")!!.id)
            assertEquals("core:default", service.layoutForMail("shop", "order", null, "core:default")!!.id)
        }
    }

    @Test
    fun `without a layout the rendered body is the whole mail and compose returns the body`() {
        val service = service()
        val content = MailTemplateContent(subject = "S {{websiteName}}", body = "<p>{{websiteName}}</p>", buttonUrl = "https://b.example")

        val rendered = service.render(content, null, emptyMap(), system)

        assertEquals("<p>Craft</p>", rendered.html)
        assertEquals("S Craft", rendered.subject)
        assertTrue(rendered.text.contains("https://b.example"))
        assertEquals("<p>{{websiteName}}</p>", service.compose(content, null))
    }

    @Test
    fun `custom layout keys are slugs of the name, made unique`() {
        assertEquals("my-layout", MailTemplateService.slug("  My Layout! "))
        assertEquals("siparis-duzeni", MailTemplateService.slug("Sipariş düzeni"))
        assertEquals("istanbul", MailTemplateService.slug("İstanbul"))
        assertEquals("layout", MailTemplateService.slug("Макет"))

        val store = MemoryStore(
            mutableListOf(
                MailLayout(owner = "custom", layoutKey = "receipt", name = "Receipt", body = "{{{content}}}"),
                MailLayout(owner = "custom", layoutKey = "receipt-2", name = "Receipt", body = "{{{content}}}"),
                // Another owner's key does not count.
                MailLayout(owner = "shop", layoutKey = "fresh", body = "{{{content}}}")
            )
        )

        runBlocking {
            assertEquals("receipt-3", service(store).newCustomLayoutKey("Receipt", null))
            assertEquals("fresh", service(store).newCustomLayoutKey("Fresh", null))
        }
    }

    @Test
    fun `index layouts and layout choices are validated`() {
        assertEquals("core:default", coreIndex.mails.first { it.key == "hello" }.layout)
        assertEquals(listOf("receipt"), pluginIndex.layouts.map { it.key })

        assertThrows<IllegalArgumentException> {
            MailTemplateIndex.parse(JsonObject("""{ "mails": [ { "key": "a", "layout": "nope" } ] }"""))
        }
        assertThrows<IllegalArgumentException> {
            MailTemplateIndex.parse(JsonObject("""{ "layouts": [ { "key": "x" }, { "key": "x" } ] }"""))
        }
        assertThrows<IllegalArgumentException> {
            MailTemplateIndex.parse(JsonObject("""{ "layouts": [ { "key": "Bad Key" } ] }"""))
        }

        assertTrue(MailTemplateIndex.isLayoutChoice("none"))
        assertTrue(MailTemplateIndex.isLayoutChoice("pano-plugin-market:receipt"))
        assertFalse(MailTemplateIndex.isLayoutChoice("receipt"))
    }

    @Test
    fun `an owner reads only the layouts its index declares`() {
        val owner = MailTemplateOwner("shop", "shop", "Shop", pluginIndex) { pluginFiles[it] ?: "other $it" }

        assertEquals("<receipt>{{{content}}}</receipt>", owner.readLayout("receipt"))
        assertNull(owner.readLayout("undeclared"))
    }
}
