package com.panomc.platform.webhook

import com.panomc.platform.api.webhook.RenderedBody
import com.panomc.platform.api.webhook.WebhookEventType
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookRegistryTest {
    private val registry = WebhookRegistry(null)

    @Test
    fun `the core events are there from the start and the test ping is not subscribable`() {
        val core = registry.catalogue().single { it.source == "core" }
        val names = core.events.map { it.name }

        for (name in listOf(
            "core.user.registered", "core.user.deleted", "core.ticket.created", "core.ticket.replied",
            "core.post.published", "core.test.ping"
        )) {
            assertTrue(name in names, name)
        }

        assertFalse(registry.isSubscribable("core.test.ping"))
        assertTrue(registry.isSubscribable("core.user.registered"))
        assertNotNull(registry.sample("core.user.registered"))
    }

    @Test
    fun `a plugin declares events with a sample and one that is not subscribable`() {
        registry.register(
            "market",
            listOf(
                WebhookEventType("order.paid", JsonObject().put("total", 1)),
                WebhookEventType("action.grant", subscribable = false)
            ),
            null, null
        )

        assertEquals(listOf("core", "market"), registry.catalogue().map { it.source })
        assertTrue(registry.isSubscribable("market.order.paid"))
        assertFalse(registry.isSubscribable("market.action.grant"))
        assertEquals(1, registry.sample("market.order.paid")!!.getInteger("total"))
        assertTrue(registry.isKnown("market.order.paid"))
    }

    @Test
    fun `an undeclared event is registered on first use and a later declaration replaces the declared ones`() {
        registry.ensure("shop", "cart.abandoned")

        assertTrue(registry.isKnown("shop.cart.abandoned"))
        assertTrue(registry.isSubscribable("shop.cart.abandoned"))

        registry.register("shop", listOf(WebhookEventType("order.paid")), null, null)
        registry.register("shop", listOf(WebhookEventType("order.refunded")), null, null)

        val names = registry.catalogue().single { it.source == "shop" }.events.map { it.name }

        assertEquals(listOf("shop.cart.abandoned", "shop.order.refunded"), names)
    }

    @Test
    fun `stopping a plugin drops what it declared but never the core events`() {
        registry.register("market", listOf(WebhookEventType("order.paid")), { _, _, _ -> RenderedBody("{}") }, null)

        assertNotNull(registry.discord("market"))

        registry.unregister("market")
        registry.unregister("core")

        assertNull(registry.discord("market"))
        assertFalse(registry.isKnown("market.order.paid"))
        assertEquals(listOf("core"), registry.catalogue().map { it.source })
        assertTrue(registry.isKnown("core.user.registered"))
    }

    @Test
    fun `a plugin cannot declare core events`() {
        assertThrows(IllegalArgumentException::class.java) { registry.register("core", emptyList(), null, null) }
    }

    @Test
    fun `an unknown event counts as subscribable`() {
        assertTrue(registry.isSubscribable("nobody.never.heard"))
    }
}
