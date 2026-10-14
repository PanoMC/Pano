package com.panomc.platform.mail.template

import com.panomc.platform.db.model.MailTemplate

/** Builds registries and services without Spring, a database or plugins. */
object MailTemplateTestSupport {
    /** The real core templates of the build (`mail-templates/` + `mail/layout.hbs` on the test classpath). */
    fun coreService(overrides: Map<List<String>, MailTemplate> = emptyMap()): MailTemplateService =
        MailTemplateService(coreRegistry(), { _, owner, key, variant, locale -> overrides[listOf(owner, key, variant, locale)] }) {
            MailTemplateService.readLayoutResource()
        }

    fun coreRegistry(): MailTemplateRegistry = MailTemplateRegistry({
        MailTemplateRegistry.classpathOwner(
            MailTemplateRegistry.CORE, null, null, MailTemplateTestSupport::class.java.classLoader, own = false
        )
    })

    /** A registry whose core owner serves [files] (`<key>/<variant>/<locale>.hbs` to text) for [index]. */
    fun memoryRegistry(index: MailTemplateIndex, files: Map<String, String>): MailTemplateRegistry =
        MailTemplateRegistry({ MailTemplateOwner(MailTemplateRegistry.CORE, null, null, index) { files[it] } })

    fun row(owner: String, key: String, variant: String, locale: String, subject: String, body: String, updatedAt: Long = 1) =
        MailTemplate(
            owner = owner, mailKey = key, variant = variant, localeCode = locale,
            subject = subject, body = body, updatedAt = updatedAt
        )
}
