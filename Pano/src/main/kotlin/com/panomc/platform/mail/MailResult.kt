package com.panomc.platform.mail

/**
 * Outcome of [MailManager.sendMail] with [MailOptions]. SMTP failures are thrown, not reported here. [FAILED] =
 * Pano Mail did not take the message (limit used up, sending paused, website unreachable): it is logged and
 * the caller's own work (a registration, a ban, a password reset) is not undone by it.
 */
enum class MailResult { SENT, DISABLED, FAILED }
