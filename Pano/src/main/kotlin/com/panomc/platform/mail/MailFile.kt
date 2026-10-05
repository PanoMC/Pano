package com.panomc.platform.mail

/**
 * A regular (non-inline) mail attachment. [name] and [contentType] are sanitised / validated by
 * [MailOptions.validateAttachments] and [MailOptions.sanitizeFileName] before anything is sent.
 */
class MailFile(val name: String, val contentType: String, val data: ByteArray)
