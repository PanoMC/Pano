package com.panomc.platform.i18n

import com.panomc.platform.ticket.TicketFields

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentTranslationsTest {
    private val content = ContentI18n

    private val rawFields = JsonArray().add(
        JsonObject().put("key", "topic").put("label", "Konu").put("type", "SELECT")
            .put("options", JsonArray().add(JsonObject().put("value", "appeal").put("label", "Ban itirazı")))
            .put(
                "i18n", JsonObject().put(
                    "en-US", JsonObject().put("label", "Topic").put("help", " ")
                        .put("options", JsonObject().put("appeal", "Ban appeal").put("gone", "Old option"))
                )
            )
    )

    private val definitions = TicketFields.parseDefinitions(rawFields)

    @Test
    fun `a category save becomes keyed translations and comes back as i18n`() {
        val entries = content.parseCategory(
            7, JsonObject().put("en-US", JsonObject().put("title", "Bans").put("description", "")), rawFields, definitions
        )

        assertEquals(
            mapOf(
                "ticket-category.7.title" to mapOf("en-US" to "Bans"),
                "ticket-category.7.field.topic.label" to mapOf("en-US" to "Topic"),
                "ticket-category.7.field.topic.option.appeal" to mapOf("en-US" to "Ban appeal")
            ),
            entries
        )

        assertEquals(JsonObject().put("en-US", JsonObject().put("title", "Bans")), content.categoryI18n(7, entries))
        assertEquals(
            JsonObject().put("en-US", JsonObject().put("label", "Topic").put("options", JsonObject().put("appeal", "Ban appeal"))),
            content.fieldI18n(7, "topic", entries)
        )

        val snapshot = content.withSnapshotI18n(7, JsonArray().add(JsonObject().put("key", "topic").put("value", "appeal")), entries)

        assertEquals("Topic", snapshot.getJsonObject(0).getJsonObject("i18n").getJsonObject("en-US").getString("label"))
    }

    @Test
    fun `a translation longer than its default text may be is refused with its field key`() {
        val error = assertThrows(TicketFields.Invalid::class.java) {
            content.parseCategory(1, JsonObject().put("ru", JsonObject().put("title", "x".repeat(33))), null, emptyList())
        }

        assertEquals(mapOf("i18n.ru.title" to "TOO_LONG"), error.errors)
        assertTrue(content.parseCategory(1, null, null, emptyList()).isEmpty())
    }
}
