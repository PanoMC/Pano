package com.panomc.platform.mail

/** Outcome of [MailManager.sendMail] with [MailOptions]. SMTP failures are thrown, not reported here. */
enum class MailResult { SENT, DISABLED }
