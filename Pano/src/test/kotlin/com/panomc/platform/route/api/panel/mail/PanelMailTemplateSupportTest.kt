package com.panomc.platform.route.api.panel.mail

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PanelMailTemplateSupportTest {
    private val samples = mapOf<String, Any?>("username" to "Steve", "flag" to true)

    @Test
    fun `preview variables are the samples without overrides`() {
        assertSame(samples, PanelMailTemplateSupport.previewVariables(samples, null))
        assertSame(samples, PanelMailTemplateSupport.previewVariables(samples, JsonObject()))
    }

    @Test
    fun `overrides replace samples and undeclared keys are kept`() {
        val result = PanelMailTemplateSupport.previewVariables(
            samples,
            JsonObject("""{ "username": "Alex", "extra": 3 }""")
        )

        assertEquals(mapOf("username" to "Alex", "flag" to true, "extra" to 3), result)
    }

    @Test
    fun `nested objects and arrays become plain maps and lists`() {
        val result = PanelMailTemplateSupport.previewVariables(
            samples,
            JsonObject("""{ "order": { "items": [ { "name": "Sword" } ], "paid": false } }""")
        )

        assertEquals(
            mapOf("items" to listOf(mapOf("name" to "Sword")), "paid" to false),
            result["order"]
        )
        assertEquals(LinkedHashMap::class.java, result["order"]!!::class.java)
    }
}
