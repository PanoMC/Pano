package com.panomc.platform.mail

import com.google.gson.Gson
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.MailDisabled
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailUnavailable
import com.panomc.platform.i18n.I18nManager
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/** A mail an admin asked for on purpose ([MailManager.sendRequiredMail]) is an error when it did not go out. */
class MailManagerStrictTest {
    private val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe

    @Suppress("UNCHECKED_CAST")
    private fun <T> untouched(type: Class<T>): T = unsafe.allocateInstance(type) as T

    private val mail = object : Mail {
        override val templatePath = "none"
        override val subject = "none"

        override suspend fun generateParameters(
            systemParameters: MailManager.Companion.SystemParameters,
            i18nManager: I18nManager,
            locale: String
        ): MailParameters = throw AssertionError("not reached while e-mail is off")
    }

    @Test
    fun `with e-mail off the quiet send says DISABLED and the strict one fails`() = runBlocking {
        val vertx = Vertx.vertx()

        try {
            val configManager = ConfigManager(vertx, LoggerFactory.getLogger("test"), AnnotationConfigApplicationContext())

            ConfigManager::class.java.getDeclaredField("config").apply { isAccessible = true }.set(configManager, PanoConfig(41))
            configManager.config.email.enabled = false

            val manager = MailManager(
                configManager, untouched(DatabaseManager::class.java), LoggerFactory.getLogger("test"), Gson(), vertx, untouched(I18nManager::class.java)
            )
            val sql = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SqlClient::class.java)) { _, _, _ ->
                throw AssertionError("not reached while e-mail is off")
            } as SqlClient

            assertEquals(MailResult.DISABLED, manager.sendMail(sql, 1L, mail, MailOptions(email = "a@b.co")))
            assertThrows<MailDisabled> { runBlocking { manager.sendRequiredMail(sql, 1L, mail, MailOptions(email = "a@b.co")) } }
        } finally {
            vertx.close()
        }
    }

    @Test
    fun `a Pano Mail failure becomes a connect or an unavailable error with the reason`() {
        assertTrue(MailManager.strictFailure(PanoMailException(PanoMailException.CONNECT_REQUIRED, "x")) is PanoMailConnectRequired)

        val quota = MailManager.strictFailure(PanoMailException("QUOTA_EXCEEDED", "limit", JsonObject().put("reason", "DAILY_LIMIT")))

        assertTrue(quota is PanoMailUnavailable)
        assertTrue(quota.encode().contains("\"reason\":\"DAILY_LIMIT\""), quota.encode())
        assertTrue(MailManager.strictFailure(PanoMailException("QUOTA_EXCEEDED", "l")).encode().contains("\"reason\":\"QUOTA_EXCEEDED\""))
    }
}
