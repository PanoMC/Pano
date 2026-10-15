package com.panomc.platform.mail.templates

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.TemplateMail
import com.panomc.platform.server.alert.ServerAlertKind
import com.panomc.platform.server.alert.ServerAlertMessage

/**
 * The e-mail behind a server alert: core mail `server-alert`, one variant per [ServerAlertKind] (its
 * [ServerAlertKind.id]), so every kind has its own text in every language and is editable from the panel.
 *
 * [message] arrives as raw values ([ServerAlertMessage.variables]); the template of the recipient's language
 * turns them into sentences, once per recipient.
 *
 * [serverName], [nodeName] and [occurredAt] are optional on purpose. Half the alert kinds are about a node
 * rather than a server and one is about neither, and the templates hide whichever detail rows they were not
 * given instead of printing an empty label.
 */
class ServerAlertMail(
    private val message: ServerAlertMessage,
    private val link: String,
    private val serverName: String? = null,
    private val nodeName: String? = null,
    private val occurredAt: String? = null
) : TemplateMail(ActivationMail.OWNER, KEY, message.kind.id) {
    override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> {
        // The exit code goes in as text: `{{#if exitCode}}` must show an exit code of 0.
        val values = message.variables.mapValues { (name, value) -> if (name == "exitCode") value?.toString() else value }

        return values + mapOf(
            "link" to "${system.websiteUrl}$link",
            "serverName" to (serverName ?: values["serverName"]),
            "nodeName" to (nodeName ?: values["nodeName"]),
            "occurredAt" to occurredAt
        )
    }

    companion object {
        const val KEY = "server-alert"
    }
}
