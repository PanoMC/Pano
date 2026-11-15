package com.panomc.platform.ticket

import com.panomc.platform.error.InvalidFields
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TicketFormTest {
    @Test
    fun `a missing or broken stored form shows everything`() {
        assertEquals(TicketForm(), TicketForm.decode(null))
        assertEquals(TicketForm(), TicketForm.decode(""))
        assertEquals(TicketForm(), TicketForm.decode("{not json"))
    }

    @Test
    fun `the stored form round-trips`() {
        val form = TicketForm(titleEnabled = false, titlePlaceholder = "a", messageEnabled = true, messagePlaceholder = "b")

        assertEquals(form, TicketForm.decode(form.encode()))
    }

    @Test
    fun `a save keeps what it leaves out and trims placeholders`() {
        val current = TicketForm(titleEnabled = false, titlePlaceholder = "old")
        val saved = TicketForm.parse(JsonObject().put("messagePlaceholder", "  Tell us more  "), current)

        assertEquals(TicketForm(titleEnabled = false, titlePlaceholder = "old", messagePlaceholder = "Tell us more"), saved)
        assertEquals(current, TicketForm.parse(null, current))
    }

    @Test
    fun `a too long placeholder or a wrong type is refused`() {
        assertThrows(InvalidFields::class.java) {
            TicketForm.parse(JsonObject().put("titlePlaceholder", "x".repeat(TicketForm.PLACEHOLDER_MAX + 1)))
        }
        assertThrows(InvalidFields::class.java) { TicketForm.parse(JsonObject().put("titleEnabled", "no")) }
        assertThrows(InvalidFields::class.java) { TicketForm.parse("form") }
    }

    @Test
    fun `a ticket without a title is named after its description, then its first field, then its category`() {
        assertEquals("My server does not start", TicketForm.derivedTitle("<p>My server\n does   not start</p>", "x", "Bugs"))
        assertEquals("Survival", TicketForm.derivedTitle("", "Survival", "Bugs"))
        assertEquals("Bugs", TicketForm.derivedTitle(null, null, "Bugs"))
        assertNull(TicketForm.derivedTitle(" ", "", "-"))

        val long = TicketForm.derivedTitle("a".repeat(100), null, null)!!

        assertEquals(TicketForm.DERIVED_TITLE_MAX, long.length)
        assertEquals('…', long.last())
    }
}
