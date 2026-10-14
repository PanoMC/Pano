package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * The admin's choice for one mail (`mail_setting`, all its variants and languages): [layout] is a layout id
 * (`<owner>:<key>`) or `none`. No row means the mail's own default.
 */
data class MailSetting(
    val id: Long = -1,
    val owner: String,
    val mailKey: String,
    val layout: String,
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
