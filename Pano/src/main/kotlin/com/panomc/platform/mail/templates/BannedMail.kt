package com.panomc.platform.mail.templates

import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.TemplateMail
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Core mail `banned`: tells a player their account was banned, until when and why. */
class BannedMail(private val username: String, private val reason: String?, private val bannedUntil: Long?) :
    TemplateMail(ActivationMail.OWNER, "banned") {
    override suspend fun variables(locale: String, system: MailManager.Companion.SystemParameters): Map<String, Any?> {
        val formattedBannedUntil = bannedUntil?.let { timestamp ->
            LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }

        return mapOf(
            "username" to username,
            "reason" to reason?.takeIf { it.isNotBlank() },
            "bannedUntil" to formattedBannedUntil
        )
    }
}
