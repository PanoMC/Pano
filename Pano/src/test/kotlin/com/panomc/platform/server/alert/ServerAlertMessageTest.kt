package com.panomc.platform.server.alert

import com.panomc.platform.AppConstants
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.template.MailTemplateService
import com.panomc.platform.mail.template.MailTemplateTestSupport
import com.panomc.platform.mail.templates.ServerAlertMail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An alert e-mail is the one thing Pano writes to somebody who is not looking at the panel. These render every kind
 * through the real `server-alert` templates in every shipped language, the way a send does.
 */
class ServerAlertMessageTest {
    private val service = MailTemplateTestSupport.coreService()
    private val system = MailManager.Companion.SystemParameters("Craft", "https://craft.example")

    private fun render(message: ServerAlertMessage, locale: String, serverName: String? = "Lobby") = runBlocking {
        val mail = ServerAlertMail(message, "/panel/servers/1", serverName, "eu-west-1", "14/10/2026 21:30")

        service.renderForSend(
            mail.owner, mail.key, mail.variant, locale, mail.variables(locale, system),
            MailTemplateService.systemVariables("Craft", "https://craft.example", locale), null
        )
    }

    /** Every kind, in both the shape that has its optional half and the shape that does not. */
    private val samples = listOf(
        ServerAlertMessage.serverCrashed("Lobby", null, null),
        ServerAlertMessage.serverCrashed("Lobby", 13, "java.lang.UnsupportedClassVersionError"),
        ServerAlertMessage.nodeOffline("eu-west-1", 0),
        ServerAlertMessage.nodeOffline("eu-west-1", 3),
        ServerAlertMessage.backupFailed("Lobby", null),
        ServerAlertMessage.backupFailed("Lobby", "no space left on device"),
        ServerAlertMessage.diskLow("eu-west-1", 93),
        ServerAlertMessage.tpsLow("Lobby", "8.4"),
        ServerAlertMessage.scheduleFailed("Lobby", "Nightly restart", null),
        ServerAlertMessage.scheduleFailed("Lobby", "Nightly restart", "the command timed out"),
        ServerAlertMessage.pluginUpdates("Lobby", 1, listOf("EssentialsX")),
        ServerAlertMessage.pluginUpdates("Lobby", 3, listOf("EssentialsX", "WorldEdit", "LuckPerms"))
    )

    @Test
    fun `there is a sample of every kind`() {
        assertEquals(ServerAlertKind.entries.toSet(), samples.map { it.kind }.toSet())
    }

    @Test
    fun `every kind says something in every locale and links to the panel`() {
        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            samples.forEach { message ->
                val rendered = render(message, locale)
                val where = "${message.kind.name} in $locale"

                assertTrue(rendered.subject.isNotBlank(), "no subject for $where")
                assertFalse(rendered.html.contains("{{"), "unrendered variable in $where")
                assertTrue(rendered.html.contains("https://craft.example/panel/servers/1"), "no panel link in $where")
                assertTrue(rendered.text.contains("https://craft.example/panel/servers/1"), "no panel link in the text of $where")
            }
        }
    }

    @Test
    fun `says what the alert was actually about`() {
        val message = ServerAlertMessage.serverCrashed("Lobby", 13, "java.lang.UnsupportedClassVersionError")

        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            val text = render(message, locale).text

            assertTrue(text.contains("Lobby"), "$locale lost the server name: $text")
            assertTrue(text.contains("13"), "$locale lost the exit code: $text")
            assertTrue(text.contains("UnsupportedClassVersionError"), "$locale lost the reason: $text")
        }
    }

    @Test
    fun `an exit code of 0 is still shown`() {
        val text = render(ServerAlertMessage.serverCrashed("Lobby", 0, null), "en-US").text

        assertTrue(text.contains("(exit code 0)"), text)
    }

    @Test
    fun `a node with no servers on it does not mention unreachable servers`() {
        assertFalse(render(ServerAlertMessage.nodeOffline("eu-west-1", 0), "en-US").text.contains("out of reach"))
        assertTrue(render(ServerAlertMessage.nodeOffline("eu-west-1", 3), "en-US").text.contains("3 server(s) are out of reach"))
    }

    @Test
    fun `names the plugins an update alert is about, in every locale`() {
        val message = ServerAlertMessage.pluginUpdates("Lobby", 3, listOf("EssentialsX", "WorldEdit", "LuckPerms"))

        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            val text = render(message, locale).text

            assertTrue(text.contains("EssentialsX, WorldEdit, LuckPerms"), "$locale lost the plugin names: $text")
            assertFalse(text.contains("["), "$locale rendered the names as a list: $text")
        }
    }

    @Test
    fun `is written in the reader's language, not the one it was recorded in`() {
        val message = ServerAlertMessage.diskLow("eu-west-1", 93)

        assertEquals("Node \"eu-west-1\" is 93% full.", message.english)
        assertTrue(render(message, "tr").text.contains("dolu"))
        assertTrue(render(message, "ru").text.contains("заполнен"))
    }

    @Test
    fun `escapes a value exactly once`() {
        val rendered = render(ServerAlertMessage.backupFailed("R&D <1>", null), "en-US", serverName = "R&D <1>")

        assertTrue(rendered.html.contains("R&amp;D &lt;1&gt;"), rendered.html)
        assertFalse(rendered.html.contains("&amp;amp;"), rendered.html)
        assertTrue(rendered.text.contains("R&D <1>"), rendered.text)
    }
}
