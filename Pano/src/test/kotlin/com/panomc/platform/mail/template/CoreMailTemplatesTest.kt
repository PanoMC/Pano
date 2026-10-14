package com.panomc.platform.mail.template

import com.panomc.platform.AppConstants
import com.panomc.platform.server.alert.ServerAlertKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bundled core templates: every mail × variant × shipped language exists, validates and renders. */
class CoreMailTemplatesTest {
    private val registry = MailTemplateTestSupport.coreRegistry()
    private val service = MailTemplateTestSupport.coreService()

    @Test
    fun `core declares the platform mails and one server-alert variant per kind`() {
        val core = registry.owner(MailTemplateRegistry.CORE)!!

        assertEquals(
            setOf("activation", "change-email", "password-updated", "banned", "reset-password", "server-alert"),
            core.index.mails.map { it.key }.toSet()
        )
        assertEquals(
            ServerAlertKind.entries.map { it.id }.toSet(),
            registry.mail("core", "server-alert")!!.variants.map { it.key }.toSet()
        )
    }

    @Test
    fun `every variant has a valid file in every shipped language and renders without leftovers`() {
        registry.owner(MailTemplateRegistry.CORE)!!.index.mails.forEach { mail ->
            mail.variants.forEach { variant ->
                AppConstants.AVAILABLE_LOCALES.forEach { locale ->
                    val where = "${mail.key}/${variant.key}/$locale"
                    val source = registry.readBundled("core", mail.key, variant.key, locale)

                    assertTrue(source != null, "$where.hbs is missing")

                    val content = MailTemplateContent.parse(source!!)
                    val system = MailTemplateService.systemVariables("Craft", "https://craft.example", locale)
                    val validation = service.validate(content, variant.sampleVariables(), system)

                    assertTrue(validation.valid, "$where: ${validation.fields} ${validation.message}")

                    val rendered = runBlocking {
                        service.renderForSend("core", mail.key, variant.key, locale, variant.sampleVariables(), system, null)
                    }

                    assertFalse(rendered.html.contains("{{"), "$where left a Handlebars expression: ${rendered.html}")
                    assertTrue(rendered.subject.startsWith("Craft"), "$where subject: ${rendered.subject}")
                    assertTrue(rendered.text.isNotBlank(), "$where has no plain text")
                    assertTrue(rendered.html.contains("lang=\"$locale\""), "$where layout lost the locale")

                    // Every name a sample covers that the template mentions must be declared, so the panel can offer it.
                    val used = Regex("\\{\\{\\{?[#/]?(?:if |unless )?([A-Za-z_]+)").findAll(source).map { it.groupValues[1] }
                        .filter { it !in setOf("if", "unless", "else", "each") }.toSet()
                    val declared = variant.variables.map { it.name }.toSet() +
                        setOf("websiteName", "websiteUrl", "websiteLogo", "year", "locale")

                    assertTrue(declared.containsAll(used), "$where uses undeclared ${used - declared}")
                }
            }
        }
    }
}
