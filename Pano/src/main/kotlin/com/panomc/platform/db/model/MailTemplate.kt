package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * A panel override of one mail × variant × language (`mail_template`). The shared layout override is the row
 * `owner = 'core', mailKey = '_layout', variant = 'default', localeCode = '*'` with the HTML in [body].
 *
 * Columns are snake_case (`mail_key`, `locale_code`, …); the DAO aliases them to these field names.
 */
data class MailTemplate(
    val id: Long = -1,
    val owner: String,
    val mailKey: String,
    val variant: String,
    val localeCode: String,
    val mode: String = "FIELDS",
    val subject: String,
    val preheader: String? = null,
    val body: String,
    val buttonLabel: String? = null,
    val buttonUrl: String? = null,
    val footer: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity() {
    companion object {
        const val LAYOUT_OWNER = "core"
        const val LAYOUT_KEY = "_layout"
        const val LAYOUT_VARIANT = "default"
        const val LAYOUT_LOCALE = "*"
    }
}
