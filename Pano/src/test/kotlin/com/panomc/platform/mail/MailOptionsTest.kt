package com.panomc.platform.mail

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.ReleaseStage
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.i18n.I18nManager
import com.google.gson.Gson
import io.vertx.core.Vertx
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Proxy

/** P-4.1: pure helpers behind `MailManager.sendMail(.., MailOptions)`. */
class MailOptionsTest {
    private fun file(name: String = "a.pdf", type: String = "application/pdf", size: Int = 1) =
        MailFile(name, type, ByteArray(size))

    // locale precedence

    @Test
    fun `locale precedence is options then user then site`() {
        assertEquals("tr", MailOptions.resolveLocale("tr", "ru", "en-US"))
        assertEquals("ru", MailOptions.resolveLocale(null, "ru", "en-US"))
        assertEquals("en-US", MailOptions.resolveLocale(null, null, "en-US"))
    }

    @Test
    fun `blank options locale is ignored`() {
        assertEquals("ru", MailOptions.resolveLocale("  ", "ru", "en-US"))
        assertEquals("en-US", MailOptions.resolveLocale("", null, "en-US"))
    }

    @Test
    fun `recipient is the non blank options email else the user email`() {
        assertEquals("guest@x.com", MailOptions.resolveRecipient("guest@x.com", "user@x.com"))
        assertEquals("user@x.com", MailOptions.resolveRecipient(null, "user@x.com"))
        assertEquals("user@x.com", MailOptions.resolveRecipient(" ", "user@x.com"))
        assertNull(MailOptions.resolveRecipient(null, null))
    }

    // subject

    @Test
    fun `subject loses CR and LF and is trimmed`() {
        assertEquals("HelloBcc: evil@x.com", MailOptions.sanitizeSubject("  Hello\r\nBcc: evil@x.com \n"))
    }

    @Test
    fun `subject is cut at 255 chars`() {
        val cut = MailOptions.sanitizeSubject("a".repeat(300))!!

        assertEquals(255, cut.length)
        assertEquals(255, MailOptions.sanitizeSubject("b".repeat(255))!!.length)
    }

    @Test
    fun `blank subject is treated as null and falls back to the translation`() {
        assertNull(MailOptions.sanitizeSubject(null))
        assertNull(MailOptions.sanitizeSubject("  \r\n "))
        assertEquals("Translated", MailOptions.resolveSubject("  \r\n", { "Translated" }, "Site"))
        assertEquals("Translated", MailOptions.resolveSubject(null, { "Translated" }, "Site"))
    }

    @Test
    fun `pre rendered subject skips the translation lookup`() {
        var called = false

        val subject = MailOptions.resolveSubject("Order 42", { called = true; "Translated" }, "Site")

        assertEquals("Order 42", subject)
        assertFalse(called)
    }

    @Test
    fun `null translation falls back to the website name`() {
        assertEquals("Site", MailOptions.resolveSubject(null, { null }, "Site"))
        assertEquals("Site", MailOptions.resolveSubject("", { null }, "Site"))
    }

    // reply-to and text

    @Test
    fun `reply to strips CR LF and must be an e-mail address`() {
        assertEquals("a@b.com", MailOptions.sanitizeReplyTo(" a@b.com "))
        assertNull(MailOptions.sanitizeReplyTo("a@b.com\r\nBcc: x@y.com"))
        assertEquals("a@b.com", MailOptions.sanitizeReplyTo("a@b.com\r\n"))
        assertEquals("a@b.com", MailOptions.sanitizeReplyTo("a@\r\nb.com"))
        assertNull(MailOptions.sanitizeReplyTo("not an email"))
        assertNull(MailOptions.sanitizeReplyTo("  "))
        assertNull(MailOptions.sanitizeReplyTo(null))
    }

    @Test
    fun `text is used only when non blank`() {
        assertEquals("hi", MailOptions.sanitizeText("hi"))
        assertNull(MailOptions.sanitizeText("   "))
        assertNull(MailOptions.sanitizeText(null))
    }

    // attachments

    @Test
    fun `five attachments pass and six are rejected`() {
        assertEquals(5, MailOptions.validateAttachments(List(5) { file() }).size)
        assertThrows(IllegalArgumentException::class.java) { MailOptions.validateAttachments(List(6) { file() }) }
    }

    @Test
    fun `total size of 10 MB passes and 10 MB plus one byte is rejected`() {
        val tenMb = 10 * 1024 * 1024

        MailOptions.validateAttachments(listOf(file(size = tenMb)))
        MailOptions.validateAttachments(listOf(file(size = tenMb / 2), file(size = tenMb / 2)))

        assertThrows(IllegalArgumentException::class.java) {
            MailOptions.validateAttachments(listOf(file(size = tenMb + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MailOptions.validateAttachments(listOf(file(size = tenMb), file(size = 1)))
        }
    }

    @Test
    fun `file name is sanitised`() {
        assertEquals(".._.._a_b.pdf", MailOptions.sanitizeFileName("../../a b.pdf"))
        assertEquals("invoice-1.2_x.pdf", MailOptions.sanitizeFileName("invoice-1.2_x.pdf"))
        assertEquals("a__b_c.pdf", MailOptions.sanitizeFileName("a\r\nb\"c.pdf"))
        assertEquals("attachment", MailOptions.sanitizeFileName(""))
        assertEquals("attachment", MailOptions.sanitizeFileName("   "))
        assertEquals(100, MailOptions.sanitizeFileName("x".repeat(250)).length)
        assertEquals("____", MailOptions.sanitizeFileName("çşğü"))
    }

    @Test
    fun `validated attachments carry the sanitised name and the same bytes`() {
        val data = byteArrayOf(1, 2, 3)

        val validated = MailOptions.validateAttachments(listOf(MailFile("../../a b.pdf", "application/pdf", data))).single()

        assertEquals(".._.._a_b.pdf", validated.name)
        assertEquals("application/pdf", validated.contentType)
        assertSame(data, validated.data)
    }

    @Test
    fun `content type is validated`() {
        listOf("application/pdf", "image/svg+xml", "application/vnd.ms-excel", "text/plain").forEach {
            MailOptions.validateAttachments(listOf(file(type = it)))
        }

        listOf("text/html; x", "pdf", "", "a/b/c", "text/html\r\nX: y", "text/ html", "/pdf").forEach {
            assertThrows(IllegalArgumentException::class.java, { MailOptions.validateAttachments(listOf(file(type = it))) }, it)
        }
    }

    @Test
    fun `empty attachment list is valid and defaults are empty`() {
        assertTrue(MailOptions.validateAttachments(emptyList()).isEmpty())

        val options = MailOptions()

        assertNull(options.email)
        assertNull(options.locale)
        assertNull(options.subject)
        assertNull(options.text)
        assertNull(options.replyTo)
        assertTrue(options.attachments.isEmpty())
    }

    // sendMail overload: disabled mail (no SMTP, no database, no i18n touched)

    private fun mailManager(enabled: Boolean, vertx: Vertx): MailManager {
        val objenesis = ObjenesisStd()
        val configManager = objenesis.newInstance(ConfigManager::class.java)

        // `config` has a private setter and ConfigManager is outside this slice: set the backing field.
        ConfigManager::class.java.getDeclaredField("config").apply { isAccessible = true }
            .set(configManager, PanoConfig(1, releaseChannel = ReleaseStage.ALPHA).apply { email.enabled = enabled })

        return MailManager(
            configManager,
            objenesis.newInstance(DatabaseManager::class.java),
            LoggerFactory.getLogger("test"),
            Gson(),
            vertx,
            objenesis.newInstance(I18nManager::class.java)
        )
    }

    private val unusedMail: Mail = proxy(Mail::class.java)

    private fun unusedSqlClient(): SqlClient = proxy(SqlClient::class.java)

    private fun <T> proxy(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            throw AssertionError("unexpected call to ${method.name}")
        }
    )

    @Test
    fun `sendMail returns DISABLED without touching anything when e-mail is off`() {
        withVertx { vertx ->
            val result = runBlocking {
                mailManager(false, vertx).sendMail(
                    unusedSqlClient(),
                    null,
                    unusedMail,
                    MailOptions(email = "a@b.com", attachments = listOf(file()))
                )
            }

            assertEquals(MailResult.DISABLED, result)
        }
    }

    @Test
    fun `sendMail rejects bad attachments before the disabled check`() {
        withVertx { vertx ->
            val manager = mailManager(false, vertx)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    manager.sendMail(unusedSqlClient(), null, unusedMail, MailOptions(attachments = List(6) { file() }))
                }
            }
        }
    }

    @Test
    fun `old four argument sendMail delegates and returns normally when e-mail is off`() {
        withVertx { vertx ->
            runBlocking { mailManager(false, vertx).sendMail(unusedSqlClient(), null, unusedMail) }
            runBlocking { mailManager(false, vertx).sendMail(unusedSqlClient(), null, unusedMail, "a@b.com") }
        }
    }

    private fun withVertx(block: (Vertx) -> Unit) {
        val vertx = Vertx.vertx()

        try {
            block(vertx)
        } finally {
            vertx.close()
        }
    }
}
