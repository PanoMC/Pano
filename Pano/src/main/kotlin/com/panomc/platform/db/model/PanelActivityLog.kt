package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject

open class PanelActivityLog(
    val id: Long = -1,
    val userId: Long? = null,
    val type: String = this::class.java.simpleName.replace("Log", "").convertToSnakeCase().lowercase(),
    val details: JsonObject = JsonObject(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()