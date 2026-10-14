package com.panomc.platform.mail.template

import com.panomc.platform.db.model.MailTemplate
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MailTemplateServiceTest {
    private val index = MailTemplateIndex.parse(
        JsonObject(
            """
            { "mails": [ { "key": "hello", "name": { "en-US": "Hello" },
              "variants": [ { "key": "default", "variables": [
                { "name": "username", "type": "text", "sample": "Steve" },
                { "name": "flag", "type": "boolean" } ] } ] } ] }
            """.trimIndent()
        )
    )

    private val layout = "<html lang=\"{{locale}}\"><title>{{subject}}</title>{{#if preheader}}<i>{{preheader}}</i>{{/if}}" +
        "<main>{{{content}}}</main>{{#if buttonUrl}}<a href=\"{{buttonUrl}}\">{{buttonLabel}}</a>{{/if}}" +
        "{{#if footer}}<footer>{{{footer}}}</footer>{{/if}}<small>{{year}} {{websiteName}}</small></html>"

    private val system = MailTemplateService.systemVariables("R&D Craft", "https://craft.example", "en-US")

    private fun file(locale: String) = "---\nsubject: from bundled $locale\n---\n<p>$locale</p>"

    private fun service(
        files: Map<String, String>,
        rows: Map<String, MailTemplate> = emptyMap()
    ) = MailTemplateService(
        MailTemplateTestSupport.memoryRegistry(index, files),
        { _, _, _, _, locale -> rows[locale] },
        { layout }
    )

    private fun row(locale: String, updatedAt: Long = 1) =
        MailTemplateTestSupport.row("core", "hello", "default", locale, "from db $locale", "<p>db $locale</p>", updatedAt)

    private fun resolve(service: MailTemplateService, locale: String, skipOwn: Boolean = false) =
        runBlocking { service.resolve("core", "hello", "default", locale, null, skipOwn) }

    @Test
    fun `resolution walks DB locale, bundled locale, bundled language, DB en-US, bundled en-US`() {
        val all = mapOf(
            "hello/default/pt-BR.hbs" to file("pt-BR"),
            "hello/default/pt.hbs" to file("pt"),
            "hello/default/en-US.hbs" to file("en-US")
        )
        val rows = mapOf("pt-BR" to row("pt-BR"), "en-US" to row("en-US"))

        assertEquals("from db pt-BR", resolve(service(all, rows), "pt-BR")!!.content.subject)
        assertEquals("from bundled pt-BR", resolve(service(all, rows - "pt-BR"), "pt-BR")!!.content.subject)
        assertEquals("from bundled pt", resolve(service(all - "hello/default/pt-BR.hbs", rows - "pt-BR"), "pt-BR")!!.content.subject)

        val onlyEnglish = mapOf("hello/default/en-US.hbs" to file("en-US"))
        val fromDb = resolve(service(onlyEnglish, rows - "pt-BR"), "pt-BR")!!

        assertEquals("from db en-US", fromDb.content.subject)
        assertEquals("en-US", fromDb.locale)
        assertTrue(fromDb.customized)

        val fromBundle = resolve(service(onlyEnglish), "pt-BR")!!

        assertEquals("from bundled en-US", fromBundle.content.subject)
        assertFalse(fromBundle.customized)

        assertNull(resolve(service(emptyMap()), "pt-BR"))
    }

    @Test
    fun `the default leaves out only the override of the locale itself`() {
        val files = mapOf("hello/default/tr.hbs" to file("tr"), "hello/default/en-US.hbs" to file("en-US"))
        val rows = mapOf("tr" to row("tr"), "en-US" to row("en-US"))

        assertEquals("from bundled tr", resolve(service(files, rows), "tr", skipOwn = true)!!.content.subject)
        assertEquals("from bundled en-US", resolve(service(files, rows), "en-US", skipOwn = true)!!.content.subject)
        assertEquals("from db en-US", resolve(service(files - "hello/default/tr.hbs", rows), "tr", skipOwn = true)!!.content.subject)
    }

    @Test
    fun `fields are rendered, then the layout with the results`() {
        val content = MailTemplateContent(
            subject = "{{websiteName}} — hi {{username}}",
            preheader = "Pre {{username}}",
            body = "<p>Hello <strong>{{username}}</strong></p>",
            buttonLabel = "Go & see",
            buttonUrl = "https://x.example/?a=1&b=2",
            footer = "Bye {{username}}"
        )

        val rendered = service(emptyMap()).render(
            content, MailTemplateService.Layout(layout, false, "test"), mapOf("username" to "<Bob>"), system
        )

        // The subject header gets the plain text; the layout escapes it once for HTML.
        assertEquals("R&D Craft — hi <Bob>", rendered.subject)
        assertTrue(rendered.html.contains("<title>R&amp;D Craft — hi &lt;Bob&gt;</title>"), rendered.html)
        assertTrue(rendered.html.contains("<main><p>Hello <strong>&lt;Bob&gt;</strong></p></main>"), rendered.html)
        assertTrue(rendered.html.contains("<i>Pre &lt;Bob&gt;</i>"), rendered.html)
        assertTrue(rendered.html.contains("<a href=\"https://x.example/?a&#x3D;1&amp;b&#x3D;2\">Go &amp; see</a>"), rendered.html)
        assertTrue(rendered.html.contains("<footer>Bye &lt;Bob&gt;</footer>"), rendered.html)
        assertTrue(rendered.html.contains("lang=\"en-US\""), rendered.html)
        assertTrue(rendered.html.contains("R&amp;D Craft</small>"), rendered.html)
    }

    @Test
    fun `empty button and footer leave their layout blocks out`() {
        val rendered = service(emptyMap()).render(
            MailTemplateContent(subject = "S", body = "<p>b</p>"), MailTemplateService.Layout(layout, false, "test"), emptyMap(), system
        )

        assertFalse(rendered.html.contains("<a "), rendered.html)
        assertFalse(rendered.html.contains("<footer>"), rendered.html)
        assertFalse(rendered.html.contains("<i>"), rendered.html)
    }

    @Test
    fun `compose puts the field templates into the layout and renders like fields mode`() {
        val fields = MailTemplateContent(
            subject = "{{websiteName}} — hi {{username}}",
            preheader = "Pre {{username}}",
            body = "<p>Hello <strong>{{username}}</strong></p>",
            buttonLabel = "Open",
            buttonUrl = "https://x.example/u/{{username}}",
            footer = "Bye {{username}}"
        )
        val service = service(emptyMap())
        val testLayout = MailTemplateService.Layout(layout, false, "test")

        val composed = service.compose(fields, testLayout)

        assertTrue(composed.contains("<title>{{websiteName}} — hi {{username}}</title>"), composed)
        assertTrue(composed.contains("<main><p>Hello <strong>{{username}}</strong></p></main>"), composed)
        assertTrue(composed.contains("lang=\"{{locale}}\""), composed)
        assertFalse(composed.contains("{{#if"), composed)

        val variables = mapOf("username" to "<Bob>")
        val asFields = service.render(fields, testLayout, variables, system)
        val asHtml = service.render(
            MailTemplateContent(mode = MailTemplateContent.Mode.HTML, subject = fields.subject, body = composed),
            null,
            variables,
            system
        )

        assertEquals(asFields.html, asHtml.html)
        assertEquals(asFields.subject, asHtml.subject)
    }

    @Test
    fun `compose leaves out empty optional blocks and returns html mode content as it is`() {
        val service = service(emptyMap())
        val testLayout = MailTemplateService.Layout(layout, false, "test")

        val composed = service.compose(MailTemplateContent(subject = "S", body = "<p>b</p>"), testLayout)

        assertFalse(composed.contains("<a "), composed)
        assertFalse(composed.contains("<footer>"), composed)

        val html = MailTemplateContent(mode = MailTemplateContent.Mode.HTML, subject = "S", body = "<html>x</html>")

        assertEquals("<html>x</html>", service.compose(html, testLayout))
    }

    @Test
    fun `html mode renders the body alone, without the layout`() {
        val rendered = service(emptyMap()).render(
            MailTemplateContent(mode = MailTemplateContent.Mode.HTML, subject = "S", body = "<html><body>Hi {{username}}</body></html>"),
            null,
            mapOf("username" to "Steve"),
            system
        )

        assertEquals("<html><body>Hi Steve</body></html>", rendered.html)
        assertEquals("Hi Steve", rendered.text)
    }

    @Test
    fun `a blank subject falls back to the website name and newlines are stripped`() {
        val service = service(emptyMap())
        val layout = MailTemplateService.Layout(layout, false, "test")

        assertEquals("R&D Craft", service.render(MailTemplateContent(subject = "{{missing}}", body = ""), layout, emptyMap(), system).subject)
        assertEquals("ab", service.render(MailTemplateContent(subject = "a{{x}}b", body = ""), layout, mapOf("x" to "\r\n"), system).subject)
    }

    @Test
    fun `plain text strips tags, keeps line breaks, decodes entities and adds the button and footer`() {
        val text = MailTemplateService.plainText(
            "<h2>Title</h2>\n<p>Hello <strong>R&amp;D</strong>,<br>line two</p><ul><li>one</li><li>two</li></ul><p>&quot;q&quot; &#252; &#x00e7;&nbsp;x</p>",
            "Open",
            "https://x.example/a",
            "<p>Footer &lt;note&gt;</p>"
        )

        assertEquals(
            "Title\nHello R&D,\nline two\n- one\n- two\n\n\"q\" ü ç x\n\nOpen: https://x.example/a\n\nFooter <note>",
            text
        )
    }

    @Test
    fun `plain text of a whole document leaves the head out`() {
        val text = MailTemplateService.toPlainText(
            "<html><head><title>T</title><style>p{color:red}</style></head><body><p>Body</p><!-- c --></body></html>"
        )

        assertEquals("Body", text)
    }

    @Test
    fun `validation reports a missing, a too long and a broken subject, and broken fields with a message`() {
        val service = service(emptyMap())
        val samples = index.mails.single().variants.single().sampleVariables()

        assertEquals(mapOf("subject" to "REQUIRED"), service.validate(MailTemplateContent(subject = " \n", body = "b"), samples, system).fields)
        assertEquals(mapOf("subject" to "TOO_LONG"), service.validate(MailTemplateContent(subject = "x".repeat(256), body = "b"), samples, system).fields)

        val broken = service.validate(
            MailTemplateContent(subject = "{{#if}}", body = "<p>{{#each username}}</p>", footer = "{{unknownHelper username}}"),
            samples,
            system
        )

        assertEquals(mapOf("subject" to "TEMPLATE_ERROR", "body" to "TEMPLATE_ERROR", "footer" to "TEMPLATE_ERROR"), broken.fields)
        assertNotNull(broken.message)
        assertFalse(broken.valid)

        assertTrue(service.validate(MailTemplateContent(subject = "Hi {{username}}", body = "{{#if flag}}x{{/if}}"), samples, system).valid)
    }

    @Test
    fun `a layout must keep the content slot and compile`() {
        val service = service(emptyMap())

        assertEquals(mapOf("body" to "MISSING_CONTENT"), service.validateLayout("<html>{{content}}</html>", system).fields)
        assertEquals(mapOf("body" to "TEMPLATE_ERROR"), service.validateLayout("<html>{{{content}}}{{#if x}}</html>", system).fields)
        assertTrue(service.validateLayout("<html>{{{ content }}}</html>", system).valid)
    }

    @Test
    fun `a sent mail picks up a saved override and its compiled templates are replaced`() {
        val rows = mutableMapOf<String, MailTemplate>()
        val service = MailTemplateService(
            MailTemplateTestSupport.memoryRegistry(index, mapOf("hello/default/en-US.hbs" to file("en-US"))),
            { _, _, _, _, locale -> rows[locale] },
            { layout }
        )

        fun send() = runBlocking { service.renderForSend("core", "hello", "default", "en-US", emptyMap(), system, null) }

        assertEquals("from bundled en-US", send().subject)

        rows["en-US"] = row("en-US", updatedAt = 1)
        assertEquals("from db en-US", send().subject)

        rows["en-US"] = row("en-US", updatedAt = 2).copy(subject = "edited")
        service.evict("core", "hello", "default", "en-US")
        assertEquals("edited", send().subject)
    }

    @Test
    fun `sending an unknown mail or variant throws`() {
        val service = service(mapOf("hello/default/en-US.hbs" to file("en-US")))

        assertThrows<MailTemplateService.TemplateNotFoundException> {
            runBlocking { service.renderForSend("core", "hello", "nope", "en-US", emptyMap(), system, null) }
        }
        assertThrows<MailTemplateService.TemplateNotFoundException> {
            runBlocking { service.renderForSend("pano-plugin-x", "hello", "default", "en-US", emptyMap(), system, null) }
        }
        assertThrows<MailTemplateService.TemplateNotFoundException> {
            runBlocking { service(emptyMap()).renderForSend("core", "hello", "default", "tr", emptyMap(), system, null) }
        }
    }

    @Test
    fun `an index with a bad key or a duplicate is refused`() {
        assertThrows<IllegalArgumentException> {
            MailTemplateIndex.parse(JsonObject("""{ "mails": [ { "key": "Bad_Key" } ] }"""))
        }
        assertThrows<IllegalArgumentException> {
            MailTemplateIndex.parse(JsonObject("""{ "mails": [ { "key": "a" }, { "key": "a" } ] }"""))
        }

        val defaulted = MailTemplateIndex.parse(JsonObject("""{ "mails": [ { "key": "a" } ] }"""))

        assertEquals(listOf("default"), defaulted.mails.single().variants.map { it.key })
    }
}
