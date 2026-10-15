package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

data class Locale(
    val id: Long = -1,
    var code: String,
    var name: String,
    var dateFnsCode: String,
    var derivatives: List<String>,
    val definedBy: DefinedBy = DefinedBy.SYSTEM,
    /** Off: kept in the panel with its translations, not offered to visitors. English is always on. */
    var enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity() {
    companion object {
        enum class DefinedBy {
            SYSTEM, USER
        }
    }
}