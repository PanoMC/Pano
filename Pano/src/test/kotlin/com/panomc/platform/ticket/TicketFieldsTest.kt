package com.panomc.platform.ticket

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TicketFieldsTest {
    private fun field(key: String, type: String, vararg extra: Pair<String, Any?>) =
        JsonObject().put("key", key).put("label", key.uppercase()).put("type", type).also { obj -> extra.forEach { obj.put(it.first, it.second) } }

    private fun options(vararg values: String) = JsonArray(values.map { JsonObject().put("value", it).put("label", "L-$it") })

    private fun rule(field: String, op: String, value: String = "") = JsonObject().put("field", field).put("op", op).put("value", value)

    private fun conditions(match: String, vararg rules: JsonObject) = JsonObject().put("match", match).put("rules", JsonArray(rules.toList()))

    /** topic (select) → ban-id visible when topic = appeal → proof visible when ban-id is filled. */
    private val chain = TicketFields.parseDefinitions(
        JsonArray()
            .add(field("topic", "SELECT", "required" to true, "options" to options("appeal", "bug")))
            .add(field("ban-id", "TEXT", "required" to true, "conditions" to conditions("ALL", rule("topic", "EQUALS", "appeal"))))
            .add(field("proof", "URL", "conditions" to conditions("ALL", rule("ban-id", "FILLED"))))
    )

    @Test
    fun `chained conditions follow the values in order`() {
        assertEquals(setOf("topic"), TicketFields.visibleKeys(chain, mapOf("topic" to "bug")))
        assertEquals(setOf("topic", "ban-id"), TicketFields.visibleKeys(chain, mapOf("topic" to "appeal")))
        assertEquals(setOf("topic", "ban-id", "proof"), TicketFields.visibleKeys(chain, mapOf("topic" to "appeal", "ban-id" to "B-1")))
        // a hidden field counts as empty, even with a value sent
        assertEquals(setOf("topic"), TicketFields.visibleKeys(chain, mapOf("topic" to "bug", "ban-id" to "B-1")))
    }

    @Test
    fun `a submission keeps the visible values, needs the required ones and drops the hidden ones`() {
        val error = assertThrows(TicketFields.Invalid::class.java) {
            TicketFields.validateSubmission(chain, JsonObject().put("topic", "appeal"))
        }

        assertEquals(mapOf("fields.ban-id" to "REQUIRED"), error.errors)

        val values = TicketFields.validateSubmission(chain, JsonObject().put("topic", "bug").put("ban-id", "ignored"))

        assertEquals(mapOf<String, Any?>("topic" to "bug"), values)

        val snapshot = TicketFields.snapshot(chain, TicketFields.validateSubmission(chain, JsonObject().put("topic", "appeal").put("ban-id", " B-1 ")))

        assertEquals(listOf("topic", "ban-id", "proof"), snapshot.map { (it as JsonObject).getString("key") })
        assertEquals("L-appeal", snapshot.getJsonObject(0).getString("display"))
        assertEquals("B-1", snapshot.getJsonObject(1).getString("value"))
        assertNull(snapshot.getJsonObject(2).getValue("value"))
        assertEquals("L-appeal\nB-1", TicketFields.searchText(snapshot))
        assertEquals(JsonObject().put("topic", "L-appeal").put("ban-id", "B-1"), TicketFields.webhookValues(snapshot))
    }

    @Test
    fun `ANY, MULTISELECT, CHECKBOX and NOT_EQUALS on an empty value`() {
        val defs = TicketFields.parseDefinitions(
            JsonArray()
                .add(field("tags", "MULTISELECT", "options" to options("a", "b")))
                .add(field("agree", "CHECKBOX"))
                .add(field("any", "TEXT", "conditions" to conditions("ANY", rule("tags", "CONTAINS", "b"), rule("agree", "EQUALS", "true"))))
                .add(field("not-agree", "TEXT", "conditions" to conditions("ALL", rule("agree", "EQUALS", "false"))))
                .add(field("ne", "TEXT", "conditions" to conditions("ALL", rule("tags", "NOT_EQUALS", "a"))))
        )

        fun visible(tags: List<String>?, agree: Boolean?) =
            TicketFields.visibleKeys(defs, mapOf("tags" to tags, "agree" to TicketFields.normalise(TicketFields.Type.CHECKBOX, agree)))

        assertTrue("any" in visible(listOf("b"), null))
        assertTrue("any" in visible(null, true))
        assertFalse("any" in visible(listOf("a"), false))
        // an unticked box is "false"
        assertTrue("not-agree" in visible(null, false))
        assertFalse("not-agree" in visible(null, true))
        // NOT_EQUALS holds for an empty value
        assertTrue("ne" in visible(null, null))
        assertFalse("ne" in visible(listOf("a"), null))
    }

    @Test
    fun `values are checked by their type`() {
        val defs = TicketFields.parseDefinitions(
            JsonArray()
                .add(field("n", "NUMBER", "min" to 1, "max" to 10))
                .add(field("d", "DATE"))
                .add(field("e", "EMAIL"))
                .add(field("u", "URL"))
                .add(field("t", "TEXT", "min" to 3))
                .add(field("s", "SELECT", "options" to options("x")))
        )

        fun errors(vararg values: Pair<String, Any?>) = try {
            TicketFields.validateSubmission(defs, JsonObject(values.toMap().toMutableMap()))
            emptyMap()
        } catch (e: TicketFields.Invalid) {
            e.errors
        }

        assertEquals(mapOf("fields.n" to "OUT_OF_RANGE"), errors("n" to 11))
        assertEquals(emptyMap<String, String>(), errors("n" to "5", "d" to "2026-02-28", "e" to "a@b.co", "u" to "https://x.example", "t" to "abc", "s" to "x"))
        assertEquals(mapOf("fields.d" to "INVALID"), errors("d" to "2026-02-30"))
        assertEquals(mapOf("fields.e" to "INVALID", "fields.u" to "INVALID"), errors("e" to "nope", "u" to "javascript:alert(1)"))
        assertEquals(mapOf("fields.t" to "TOO_SHORT", "fields.s" to "UNKNOWN_OPTION"), errors("t" to "ab", "s" to "y"))
    }

    @Test
    fun `definitions are checked for keys, options, bounds and rules on earlier fields only`() {
        val error = assertThrows(TicketFields.Invalid::class.java) {
            TicketFields.parseDefinitions(
                JsonArray()
                    .add(field("a", "TEXT", "conditions" to conditions("ALL", rule("b", "FILLED"))))
                    .add(field("a", "SELECT", "options" to JsonArray()))
                    .add(field("Bad Key", "NUMBER", "min" to 5, "max" to 1))
            )
        }

        assertEquals("UNKNOWN_FIELD", error.errors["fields.0.conditions.rules.0.field"])
        assertEquals("DUPLICATE", error.errors["fields.1.key"])
        assertEquals("REQUIRED", error.errors["fields.1.options"])
        assertEquals("INVALID", error.errors["fields.2.key"])
        assertEquals("OUT_OF_RANGE", error.errors["fields.2.max"])
    }

    @Test
    fun `a staff edit changes snapshot values by their type and ignores unknown keys`() {
        val snapshot = TicketFields.snapshot(chain, linkedMapOf("topic" to "appeal", "ban-id" to "B-1", "proof" to null))
        val (edited, changed) = TicketFields.applyEdit(snapshot, JsonObject().put("proof", "https://x.example/p").put("other", "x"))

        assertTrue(changed)
        assertEquals("https://x.example/p", edited.getJsonObject(2).getString("display"))
        assertEquals(3, edited.size())

        val error = assertThrows(TicketFields.Invalid::class.java) { TicketFields.applyEdit(snapshot, JsonObject().put("topic", "nope")) }

        assertEquals(mapOf("fields.topic" to "UNKNOWN_OPTION"), error.errors)
        assertFalse(TicketFields.applyEdit(snapshot, JsonObject().put("ban-id", "B-1")).second)
    }
}
