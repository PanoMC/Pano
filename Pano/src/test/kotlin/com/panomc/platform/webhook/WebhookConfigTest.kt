package com.panomc.platform.webhook

import com.panomc.platform.config.HoconWriter
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.config.migration.ConfigMigration39To40
import com.panomc.platform.db.implementation.WebhookDeliveryDaoImpl
import com.panomc.platform.db.implementation.WebhookEndpointDaoImpl
import com.panomc.platform.db.migration.DatabaseMigration57to58
import com.panomc.platform.webhook.guard.TargetPolicy
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookConfigTest {
    @Test
    fun `migration 39 to 40 adds the webhooks block with private targets off and keeps an existing one`() {
        val migration = ConfigMigration39To40()

        assertEquals(39, migration.from)
        assertEquals(40, migration.to)
        assertTrue(migration.isMigratable(39))
        assertFalse(migration.isMigratable(38))

        val config = JsonObject().put("config-version", 39)

        migration.migrate(config)

        assertEquals(false, config.getJsonObject("webhooks").getBoolean("allow-private-targets"))
        assertFalse(PanoConfig.from(config).effectiveWebhooks.allowPrivateTargets)

        val kept = JsonObject().put("webhooks", JsonObject().put("allow-private-targets", true))

        migration.migrate(kept)

        assertTrue(PanoConfig.from(kept).effectiveWebhooks.allowPrivateTargets)
    }

    @Test
    fun `a config without the block means private targets off`() {
        assertFalse(PanoConfig.from(JsonObject().put("config-version", 39)).effectiveWebhooks.allowPrivateTargets)
        assertFalse(PanoConfig(40).effectiveWebhooks.allowPrivateTargets)
    }

    @Test
    fun `the block is written with its comment and key`() {
        val text = HoconWriter.render(JsonObject(PanoConfig(40).toString()), PanoConfig::class.java)

        assertTrue(text.contains("# Webhooks"), text)
        assertTrue(text.contains("allow-private-targets = false"), text)
    }

    @Test
    fun `private targets are forced off when the instance is hosted`() {
        assertTrue(TargetPolicy.effectiveAllowPrivate(flag = true, hosted = false))
        assertFalse(TargetPolicy.effectiveAllowPrivate(flag = true, hosted = true))
        assertFalse(TargetPolicy.effectiveAllowPrivate(flag = false, hosted = false))
    }

    @Test
    fun `the database migration is 57 to 58 and the tables are named after the models`() {
        val migration = DatabaseMigration57to58()

        assertEquals(57, migration.from)
        assertEquals(58, migration.to)
        assertTrue(migration.isMigratable(57))
        assertEquals(2, migration.handlers.size)

        val endpoint = WebhookEndpointDaoImpl.createTableQuery("pano_webhook_endpoint")
        val delivery = WebhookDeliveryDaoImpl.createTableQuery("pano_webhook_delivery")

        assertTrue(endpoint.contains("CREATE TABLE IF NOT EXISTS `pano_webhook_endpoint`"))
        assertTrue(delivery.contains("CREATE TABLE IF NOT EXISTS `pano_webhook_delivery`"))

        for (column in listOf("`source`", "`ownerRef`", "`subjectRef`", "UNIQUE KEY `uq_eventId`")) {
            assertTrue(delivery.contains(column), column)
        }

        assertFalse(delivery.contains("deliveryId") || delivery.contains("orderId"))
    }
}
