package com.panomc.platform.mail.template

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MailTemplateListVariableTest {
    private val index = MailTemplateIndex.parse(
        JsonObject(
            """
            { "mails": [ { "key": "order", "variants": [ { "key": "default", "variables": [
                { "name": "total", "type": "text", "sample": "€10.00" },
                { "name": "items", "type": "list",
                  "label": { "en-US": "Items", "tr": "Ürünler" },
                  "fields": [
                    { "name": "name", "label": { "en-US": "Name", "tr": "Ad" } },
                    { "name": "total", "label": { "en-US": "Line total" } },
                    { "name": "child", "type": "boolean", "label": { "en-US": "Bundle part" } }
                  ],
                  "sample": [ { "name": "Gold Rank", "total": "€5.00" }, { "name": "Sword", "child": true } ] }
              ] } ] } ] }
            """.trimIndent()
        )
    )

    private val variant = index.mails.single().variants.single()
    private val items = variant.variables.single { it.name == "items" }

    private val layout = "<html>{{{content}}}</html>"
    private val system = MailTemplateService.systemVariables("Craft", "https://craft.example", "en-US")
    private val service = MailTemplateService(MailTemplateTestSupport.memoryRegistry(index, emptyMap()), { _, _, _, _, _ -> null }, { layout })

    private val body = "<table>{{#each items}}\n<tr><td>{{#if child}}- {{/if}}{{name}}</td><td>{{total}}</td></tr>\n{{/each}}</table><p>{{total}}</p>"

    @Test
    fun `a list variable parses its fields and its sample rows`() {
        assertEquals(VariableDefinition.Type.LIST, items.type)
        assertEquals(listOf("name", "total", "child"), items.fields.map { it.name })
        assertEquals(VariableDefinition.Type.BOOLEAN, items.fields[2].type)

        @Suppress("UNCHECKED_CAST")
        val rows = variant.sampleVariables()["items"] as List<Map<String, Any?>>

        assertEquals(2, rows.size)
        assertEquals(mapOf("name" to "Gold Rank", "total" to "€5.00", "child" to false), rows[0])
        assertEquals(mapOf("name" to "Sword", "total" to "", "child" to true), rows[1], "a field a row leaves out is empty, never null")
    }

    @Test
    fun `the panel gets the fields with labels in the admin's language and the sample rows`() {
        val map = items.toMap("tr")

        assertEquals("list", map["type"])
        assertEquals("Ürünler", map["label"])

        @Suppress("UNCHECKED_CAST")
        val fields = map["fields"] as List<Map<String, Any?>>

        assertEquals(listOf("Ad", "Line total", "Bundle part"), fields.map { it["label"] })
        assertEquals(listOf("text", "text", "boolean"), fields.map { it["type"] })
        assertEquals(2, (map["sample"] as List<*>).size)

        // serialisable as the endpoint answers it
        assertTrue(JsonObject(map).encode().contains("\"Gold Rank\""))

        val plain = variant.variables.single { it.name == "total" }.toMap("en-US")

        assertFalse(plain.containsKey("fields"), "a plain variable has no fields")
    }

    @Test
    fun `a list renders row by row with its samples and validates, a missing row field never falls through to the outer variable`() {
        val content = MailTemplateContent(subject = "Order", body = body)

        assertTrue(service.validate(content, variant.sampleVariables(), system).valid)

        val r = service.render(content, MailTemplateService.Layout(layout, false, "t"), variant.sampleVariables(), system)

        assertTrue(r.html.contains("<tr><td>Gold Rank</td><td>€5.00</td></tr>"), r.html)
        assertTrue(r.html.contains("<tr><td>- Sword</td><td></td></tr>"), r.html)
        assertTrue(r.html.contains("<p>€10.00</p>"))
        assertEquals("Gold Rank €5.00\n- Sword\n\n€10.00", r.text, "one row per line, cells apart")
    }

    @Test
    fun `a sender's list of maps renders like the samples, an empty list skips its block`() {
        val content = MailTemplateContent(subject = "Order", body = "{{#if items}}<table>{{#each items}}<tr><td>{{name}}</td></tr>{{/each}}</table>{{/if}}")
        val sent = mapOf("items" to listOf(mapOf("name" to "<b>A</b>", "total" to "", "child" to false)))
        val r = service.render(content, MailTemplateService.Layout(layout, false, "t"), sent, system)

        assertTrue(r.html.contains("<td>&lt;b&gt;A&lt;/b&gt;</td>"), r.html)
        assertEquals("<html></html>", service.render(content, MailTemplateService.Layout(layout, false, "t"), mapOf("items" to emptyList<Any>()), system).html)
    }

    @Test
    fun `a sample that is not an array is an empty list, a nested list is refused, old indexes still parse`() {
        val odd = MailTemplateIndex.parse(JsonObject("""{ "mails": [ { "key": "a", "variants": [ { "key": "default", "variables": [ { "name": "rows", "type": "list", "sample": "x" } ] } ] } ] }"""))

        assertEquals(emptyList<Any>(), odd.mails.single().variants.single().sampleVariables()["rows"])

        assertThrows<IllegalArgumentException> {
            MailTemplateIndex.parse(
                JsonObject("""{ "mails": [ { "key": "a", "variants": [ { "key": "default", "variables": [ { "name": "rows", "type": "list", "fields": [ { "name": "inner", "type": "list" } ] } ] } ] } ] }""")
            )
        }

        val old = MailTemplateIndex.parse(JsonObject("""{ "mails": [ { "key": "a", "variants": [ { "variables": [ { "name": "x", "type": "html", "sample": "<b>b</b>" } ] } ] } ] }"""))

        assertEquals("<b>b</b>", old.mails.single().variants.single().sampleVariables()["x"])
    }
}
