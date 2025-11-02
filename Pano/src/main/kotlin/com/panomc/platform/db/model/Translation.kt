package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class Translation(
    val id: Long = -1,
    val localeId: Long,
    val type: TranslationType,
    val key: String,
    var value: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity() {
    companion object {
        enum class TranslationType {
            PANEL,
            THEME,
            PLUGIN,
            PLATFORM,
            MC_PLUGIN
        }
    }
}