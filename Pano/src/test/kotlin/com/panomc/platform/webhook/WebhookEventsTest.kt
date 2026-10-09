package com.panomc.platform.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WebhookEventsTest {
    private val paid = "market.order.paid"

    @Test
    fun `an exact name, a source wildcard and the star match`() {
        assertTrue(WebhookEvents.matches("""["market.order.paid"]""", paid))
        assertTrue(WebhookEvents.matches("""["market.*"]""", paid))
        assertTrue(WebhookEvents.matches("""["*"]""", paid))
        assertTrue(WebhookEvents.matches("""["core.user.registered","market.order.paid"]""", paid))
    }

    @Test
    fun `a name or a wildcard of another source does not match`() {
        assertFalse(WebhookEvents.matches("""["market.order.refunded"]""", paid))
        assertFalse(WebhookEvents.matches("""["shop.*"]""", paid))
        assertFalse(WebhookEvents.matches("""["core.*"]""", paid))
        assertFalse(WebhookEvents.matches("""["market"]""", paid))
        assertFalse(WebhookEvents.matches("""["market.order"]""", paid))
        assertFalse(WebhookEvents.matches("""[]""", paid))
    }

    @Test
    fun `a source wildcard is prefix of the source only`() {
        assertFalse(WebhookEvents.matches("""["mark.*"]""", paid))
        assertFalse(WebhookEvents.matches("""["marketing.*"]""", "market.order.paid"))
        assertTrue(WebhookEvents.matches("""["marketing.*"]""", "marketing.campaign.sent"))
    }

    @Test
    fun `wildcards never match the test ping`() {
        assertFalse(WebhookEvents.matches("""["*"]""", WebhookEvents.TEST_PING))
        assertFalse(WebhookEvents.matches("""["core.*"]""", WebhookEvents.TEST_PING))
        assertTrue(WebhookEvents.matches("""["core.test.ping"]""", WebhookEvents.TEST_PING))
    }

    @Test
    fun `wildcards never match an event that is not subscribable`() {
        assertFalse(WebhookEvents.matches("""["*"]""", "market.action.grant", subscribable = false))
        assertFalse(WebhookEvents.matches("""["market.*"]""", "market.action.grant", subscribable = false))
        assertTrue(WebhookEvents.matches("""["market.action.grant"]""", "market.action.grant", subscribable = false))
    }

    @Test
    fun `an unreadable list matches nothing`() {
        for (bad in listOf("", "not json", "{}", "[1]", """["*", 5]""", "null")) {
            assertFalse(WebhookEvents.matches(bad, paid), bad)
        }
    }

    @Test
    fun `the event id is the name based uuid of event, subject and endpoint`() {
        val id = WebhookEvents.eventId(paid, "42", 7)

        assertEquals(UUID.nameUUIDFromBytes("market.order.paid:42:7".toByteArray()).toString(), id)
        assertEquals(id, WebhookEvents.eventId(paid, "42", 7))
        assertNotEquals(id, WebhookEvents.eventId(paid, "43", 7))
        assertNotEquals(id, WebhookEvents.eventId(paid, "42", 8))
    }

    @Test
    fun `two sources with the same event and subject get different ids`() {
        assertNotEquals(
            WebhookEvents.eventId("market.order.paid", "1", 1),
            WebhookEvents.eventId("shop.order.paid", "1", 1)
        )
    }

    @Test
    fun `names are dot separated segments of lower case letters, digits, underscore and hyphen`() {
        for (ok in listOf("order.paid", "order.chargeback.won", "a", "my-event_2.x")) assertTrue(WebhookEvents.isValidName(ok), ok)
        for (bad in listOf("", ".", "order..paid", "Order.paid", "order.paid.", "order paid", "order/paid", "x".repeat(64))) {
            assertFalse(WebhookEvents.isValidName(bad), bad)
        }
        assertTrue(WebhookEvents.isValidSource("market"))
        assertFalse(WebhookEvents.isValidSource("a.b"))
        assertFalse(WebhookEvents.isValidSource(""))
    }

    @Test
    fun `source and name split a full event`() {
        assertEquals("market", WebhookEvents.sourceOf(paid))
        assertEquals("order.paid", WebhookEvents.nameOf(paid))
        assertEquals(paid, WebhookEvents.full("market", "order.paid"))
    }
}
