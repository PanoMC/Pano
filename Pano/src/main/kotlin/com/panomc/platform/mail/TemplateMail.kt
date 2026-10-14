package com.panomc.platform.mail

/**
 * A mail rendered from the unified mail templates: `(owner, key, variant)` names a mail declared in an owner's
 * `mail-templates/index.json` (`owner` = `core` or the plugin id), the recipient's language picks the template
 * (editable per language from the panel), and the shared layout wraps it.
 *
 * Send with [MailManager.sendTemplate] / [MailManager.sendRequiredTemplate]. Either subclass it and compute the
 * variables per send in [variables], or use [of] with a fixed map. Values are used escaped (`{{name}}`), except
 * variables declared as `html`, which are pre-rendered safe HTML used with `{{{name}}}`. `websiteName`,
 * `websiteUrl`, `websiteLogo`, `year` and `locale` are always available. Build front-end links with
 * [MailManager.Companion.SystemParameters.linkTo].
 */
open class TemplateMail(val owner: String, val key: String, val variant: String = "default") {
    /** Called once per send with the resolved locale; return the declared variables. */
    open suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> = emptyMap()

    companion object {
        fun of(owner: String, key: String, variant: String = "default", variables: Map<String, Any?> = emptyMap()): TemplateMail =
            object : TemplateMail(owner, key, variant) {
                override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters) = variables
            }
    }
}
