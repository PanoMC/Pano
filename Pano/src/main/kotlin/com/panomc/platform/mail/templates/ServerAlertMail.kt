package com.panomc.platform.mail.templates

import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters
import com.panomc.platform.server.alert.ServerAlertMessage

/**
 * The e-mail behind a server alert.
 *
 * Alert e-mail is an opt-in on a switch the panel shows disabled unless [isAvailable] says the
 * bundled templates contain `mail/server-alert.hbs`, because the template is built from the
 * `pano-email` project and a build that did not pick it up must not offer the toggle: rendering a
 * mail whose template is missing throws from inside the send.
 *
 * [message] arrives as keys and values rather than a finished sentence, because this is the one
 * place that knows who is reading: [generateParameters] is called once per recipient with that
 * recipient's locale, so the headline and the body are put into their language here and nowhere
 * earlier.
 *
 * [serverName], [nodeName] and [occurredAt] are optional on purpose. Half the alert kinds are
 * about a node rather than a server and one is about neither, and the template hides whichever
 * detail rows it was not given instead of printing an empty label.
 */
class ServerAlertMail(
    private val message: ServerAlertMessage,
    private val link: String,
    private val serverName: String? = null,
    private val nodeName: String? = null,
    private val occurredAt: String? = null
) : Mail {
    override val templatePath = TEMPLATE_PATH
    override val subject = "mail.server-alert.subject"

    override suspend fun generateParameters(
        systemParameters: MailManager.Companion.SystemParameters,
        i18nManager: I18nManager,
        locale: String
    ): MailParameters {
        val rendered = message.render { key, variables ->
            i18nManager.translate(TranslationType.PLATFORM, locale, key, variables)
        }

        return ServerAlertMailParameters(
            message.kind.name,
            rendered.title,
            rendered.body,
            "${systemParameters.websiteUrl}$link",
            serverName,
            nodeName,
            occurredAt,
            // The kind texts live under the same group as the template's own labels, but they are
            // sentences with holes in them: rendered without their variables they would put
            // "{{serverName}}" in front of the template, so only the labels go through.
            getTranslations(i18nManager, locale, GROUP)
                .filterKeys { !it.startsWith("$KINDS_KEY.") }
        )
    }

    companion object {
        const val TEMPLATE_PATH = "mail/server-alert.hbs"

        private const val GROUP = "mail.server-alert"
        private const val KINDS_KEY = "kinds"

        /**
         * Whether this build actually bundles the template.
         *
         * Checked against the classloader rather than assumed, because the answer differs between
         * a build that shipped the template and one that did not, and rendering a mail whose
         * template is missing throws from inside the send.
         */
        fun isAvailable(): Boolean =
            ServerAlertMail::class.java.classLoader.getResource(TEMPLATE_PATH) != null

        data class ServerAlertMailParameters(
            val kind: String,
            val title: String,
            val body: String,
            val link: String,
            val serverName: String?,
            val nodeName: String?,
            val occurredAt: String?,
            val translation: Map<String, Any?>
        ) : MailParameters
    }
}
