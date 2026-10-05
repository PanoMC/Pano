package com.panomc.platform.notification

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.notification.type.panel.NewTicketNotification
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/** P-5.1 */
class NotificationTypeRegistryTest {
    @NotificationDefinition
    class MarketOrderReviewNotification : PanelUserNotificationType()

    @NotificationDefinition
    class OtherPluginNotification : PanelUserNotificationType()

    /** Same simple name as a core type: NewTicketNotification -> NEW_TICKET. */
    object Shadow {
        @NotificationDefinition
        class NewTicketNotification : PanelUserNotificationType()
    }

    /** Same simple name as [MarketOrderReviewNotification], other class. */
    object Dup {
        @NotificationDefinition
        class MarketOrderReviewNotification : PanelUserNotificationType()
    }

    private class TestPlugin : PanoPlugin()

    private val core = NewTicketNotification()

    private fun registry() = NotificationTypeRegistry { listOf(core) }

    private fun plugin(id: String, vararg classes: Class<*>): PanoPlugin {
        val ctx = AnnotationConfigApplicationContext()
        ctx.register(*classes)
        ctx.refresh()

        return TestPlugin().also {
            it.pluginId = id
            it.pluginBeanContext = ctx
        }
    }

    @Test
    fun coreTypeResolvesWithoutOwner() {
        val r = registry()

        assertSame(core, r.resolve("NEW_TICKET"))
        assertNull(r.ownerOf("NEW_TICKET"))
    }

    @Test
    fun pluginTypeResolvesWithOwner() = runBlocking {
        val r = registry()
        r.onPluginLoad(plugin("market", MarketOrderReviewNotification::class.java))

        assertTrue(r.resolve("MARKET_ORDER_REVIEW") is MarketOrderReviewNotification)
        assertEquals("market", r.ownerOf("MARKET_ORDER_REVIEW"))
    }

    @Test
    fun unknownNameResolvesToUnknownTypeWithoutThrowing() {
        val r = registry()
        val type = r.resolve("NOPE")

        assertTrue(type is UnknownNotificationType)
        assertEquals("NOPE", type.getName())
        assertNull(r.ownerOf("NOPE"))
    }

    @Test
    fun pluginTypeNamedLikeCoreIsRejected() = runBlocking {
        val r = registry()
        r.onPluginLoad(plugin("evil", Shadow.NewTicketNotification::class.java))

        assertSame(core, r.resolve("NEW_TICKET"))
        assertNull(r.ownerOf("NEW_TICKET"))
    }

    @Test
    fun pluginTypeNamedLikeOtherPluginsTypeIsRejected() = runBlocking {
        val r = registry()
        r.onPluginLoad(plugin("market", MarketOrderReviewNotification::class.java))
        r.onPluginLoad(plugin("second", Dup.MarketOrderReviewNotification::class.java, OtherPluginNotification::class.java))

        assertEquals("market", r.ownerOf("MARKET_ORDER_REVIEW"))
        assertTrue(r.resolve("MARKET_ORDER_REVIEW") is MarketOrderReviewNotification)
        assertEquals("second", r.ownerOf("OTHER_PLUGIN"))
    }

    @Test
    fun unloadRemovesAndReloadRegistersAgain() = runBlocking {
        val r = registry()
        val p = plugin("market", MarketOrderReviewNotification::class.java)

        r.onPluginLoad(p)
        r.onPluginUnload(p)

        assertTrue(r.resolve("MARKET_ORDER_REVIEW") is UnknownNotificationType)
        assertNull(r.ownerOf("MARKET_ORDER_REVIEW"))

        r.onPluginLoad(p)
        assertEquals("market", r.ownerOf("MARKET_ORDER_REVIEW"))

        // load twice without unload keeps the same owner
        r.onPluginLoad(p)
        assertEquals("market", r.ownerOf("MARKET_ORDER_REVIEW"))
        assertFalse(r.resolve("MARKET_ORDER_REVIEW") is UnknownNotificationType)
    }
}
