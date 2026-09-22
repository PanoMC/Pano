package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject

open class PanelActivityLog(
    val id: Long = -1,
    val userId: Long? = null,
    val pluginId: String? = null,
    var type: String? = null,
    val details: JsonObject = JsonObject(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity() {
    init {
        if (type == null) {
            type = typeOf(this::class.java)
        }
    }

    companion object {
        /**
         * The `type` a log class writes, without having to construct one.
         *
         * Same derivation the constructor uses, pulled out so a caller that needs to *query* for
         * a type — the per-server activity feed does — asks the class instead of repeating the
         * string it produces.
         */
        fun typeOf(logClass: Class<out PanelActivityLog>): String =
            logClass.simpleName.removeSuffix("Log").convertToSnakeCase().uppercase()
    }
}