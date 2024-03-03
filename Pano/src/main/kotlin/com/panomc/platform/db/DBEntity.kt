package com.panomc.platform.db

import com.google.gson.Gson
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

abstract class DBEntity {
    companion object {
        val gson by lazy {
            Gson()
        }

        inline fun <reified T : DBEntity> Class<T>.from(row: Row): T =
            gson.fromJson(row.toJson().toString(), this)

        inline fun <reified T : DBEntity> Class<T>.from(rowSet: RowSet<Row>) = rowSet.map { this.from(it) }
    }

    fun toJson(): String = gson.toJson(this)
}