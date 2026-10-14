package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * A mail layout row (`mail_layout`): the panel's override of a bundled layout (`core:default`, a plugin's layout),
 * or a layout an admin made (owner `custom`). The layout id is `<owner>:<layoutKey>`. [name] is only used by
 * custom layouts; bundled ones take theirs from the owner's `index.json`.
 */
data class MailLayout(
    val id: Long = -1,
    val owner: String,
    val layoutKey: String,
    val name: String = "",
    val body: String,
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
