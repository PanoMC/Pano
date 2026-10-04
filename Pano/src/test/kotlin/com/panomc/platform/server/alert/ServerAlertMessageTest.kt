package com.panomc.platform.server.alert

import com.github.jknack.handlebars.Handlebars
import com.panomc.platform.AppConstants
import com.panomc.platform.util.JsonObjectUtil
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An alert e-mail is the one thing Pano writes to somebody who is not looking at the panel, so a
 * key that is missing from a locale file costs a whole e-mail rather than a blank label somebody
 * can shrug at. These render every kind out of the real locale files the way `I18nManager` would,
 * and check that the English the alert was recorded in is still there to fall back on.
 */
class ServerAlertMessageTest {
    private val handlebars = Handlebars()

    private val translations: Map<String, Map<String, String>> =
        AppConstants.AVAILABLE_LOCALES.associateWith { locale ->
            val resource = "locales/platform/$locale.json"
            val content = javaClass.classLoader.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("$resource is not on the classpath")

            JsonObjectUtil.flattenJsonObject(JsonObject(content)).mapValues { it.value.toString() }
        }

    /**
     * What `I18nManager.translate` does, minus its fall back to en-US: a key these cannot find in
     * the locale asked for is a key that locale file does not have, which is the thing worth
     * failing over.
     */
    private fun translatorOf(locale: String): suspend (String, Map<String, Any?>) -> String? =
        { key, variables ->
            translations.getValue(locale)[key]?.let { template ->
                if (variables.isEmpty()) template else handlebars.compileInline(template).apply(variables)
            }
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
    fun `every kind says something in every locale`() {
        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            samples.forEach { message ->
                val rendered = runBlocking { message.render(translatorOf(locale)) }

                val where = "${message.kind.name} in $locale"

                assertTrue(rendered.title.isNotBlank(), "no title for $where")
                assertTrue(rendered.body.isNotBlank(), "no body for $where")
                assertFalse(rendered.title.contains("{{"), "unrendered variable in the title of $where")
                assertFalse(rendered.body.contains("{{"), "unrendered variable in the body of $where")
            }
        }
    }

    @Test
    fun `every locale file carries every key a kind asks for`() {
        samples.forEach { message ->
            (listOf("title") + message.keys).forEach { key ->
                val fullKey = "${ServerAlertMessage.GROUP}.${message.kind.id}.$key"

                AppConstants.AVAILABLE_LOCALES.forEach { locale ->
                    assertTrue(
                        translations.getValue(locale).containsKey(fullKey),
                        "$locale.json is missing $fullKey"
                    )
                }
            }
        }
    }

    @Test
    fun `the locale files agree on which keys a kind has`() {
        val reference = kindKeysOf(AppConstants.DEFAULT_LOCALE_CODE)

        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            assertEquals(reference, kindKeysOf(locale), "$locale.json does not match the default locale")
        }
    }

    @Test
    fun `says what the alert was actually about`() {
        val message = ServerAlertMessage.serverCrashed("Lobby", 13, "java.lang.UnsupportedClassVersionError")

        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            val body = runBlocking { message.render(translatorOf(locale)) }.body

            assertTrue(body.contains("Lobby"), "$locale lost the server name: $body")
            assertTrue(body.contains("13"), "$locale lost the exit code: $body")
            assertTrue(body.contains("UnsupportedClassVersionError"), "$locale lost the reason: $body")
        }
    }

    @Test
    fun `names the plugins an update alert is about, in every locale`() {
        val message = ServerAlertMessage.pluginUpdates("Lobby", 3, listOf("EssentialsX", "WorldEdit", "LuckPerms"))

        AppConstants.AVAILABLE_LOCALES.forEach { locale ->
            val body = runBlocking { message.render(translatorOf(locale)) }.body

            assertTrue(body.contains("Lobby"), "$locale lost the server name: $body")
            assertTrue(body.contains("3"), "$locale lost the count: $body")
            assertTrue(body.contains("EssentialsX"), "$locale lost the plugin names: $body")
            assertTrue(body.contains("LuckPerms"), "$locale lost the last plugin name: $body")
            // A list rendered through Handlebars as a Java toString would arrive in brackets.
            assertFalse(body.contains("["), "$locale rendered the names as a list: $body")
        }
    }

    @Test
    fun `is written in the reader's language, not the one it was recorded in`() {
        val message = ServerAlertMessage.diskLow("eu-west-1", 93)

        val turkish = runBlocking { message.render(translatorOf("tr")) }
        val russian = runBlocking { message.render(translatorOf("ru")) }

        assertEquals("Node \"eu-west-1\" is 93% full.", message.english)
        assertTrue(turkish.body.contains("dolu"), turkish.body)
        assertTrue(russian.body.contains("заполнен"), russian.body)
    }

    @Test
    fun `leaves what it substitutes for the mail template to escape`() {
        val message = ServerAlertMessage.backupFailed("R&D <1>", null)

        val body = runBlocking { message.render(translatorOf("en-US")) }.body

        assertTrue(body.contains("R&D <1>"), "the value was escaped before the template got it: $body")
    }

    @Test
    fun `falls back to the English it was recorded in when nothing is translated`() {
        samples.forEach { message ->
            val rendered = runBlocking { message.render { _, _ -> null } }

            assertEquals(message.english, rendered.body)
            assertTrue(rendered.title.isNotBlank(), "no title for ${message.kind.name}")
        }

        val rendered = runBlocking { ServerAlertMessage.diskLow("eu-west-1", 93).render { _, _ -> null } }

        assertEquals("Disk low", rendered.title)
    }

    @Test
    fun `falls back as a whole when only half of the message is translated`() {
        val message = ServerAlertMessage.serverCrashed("Lobby", 13, "java.lang.UnsupportedClassVersionError")

        val rendered = runBlocking {
            message.render { key, _ -> if (key.endsWith(".reason")) null else "Something in Turkish" }
        }

        assertEquals(message.english, rendered.body)
        assertEquals("Something in Turkish", rendered.title)
    }

    @Test
    fun `treats a blank translation as a missing one`() {
        val message = ServerAlertMessage.tpsLow("Lobby", "8.4")

        val rendered = runBlocking { message.render { _, _ -> "   " } }

        assertEquals(message.english, rendered.body)
        assertEquals("Tps low", rendered.title)
    }

    @Test
    fun `a translator that throws costs a translation, not the e-mail`() {
        val message = ServerAlertMessage.nodeOffline("eu-west-1", 3)

        val rendered = runBlocking { message.render { _, _ -> throw IllegalStateException("no database") } }

        assertEquals(message.english, rendered.body)
        assertEquals("Node offline", rendered.title)
    }

    private fun kindKeysOf(locale: String) = translations.getValue(locale)
        .keys
        .filter { it.startsWith("${ServerAlertMessage.GROUP}.") }
        .toSortedSet()
}
