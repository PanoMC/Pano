package com.panomc.platform.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.JsonObjectDeserializer
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

abstract class DBEntity {
    companion object {
        val gson: Gson by lazy {
            val builder = GsonBuilder()

            builder.registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
            builder.registerTypeAdapter(JsonObject::class.java, JsonObjectDeserializer())

            builder.create()
        }

        inline fun <reified T : DBEntity> Class<T>.from(row: Row): T =
            gson.fromJson(row.toJson().toString(), this)

        inline fun <reified T : DBEntity> Class<T>.from(rowSet: RowSet<Row>) = rowSet.map { this.from(it) }
    }

    fun toJson(): String = gson.toJson(this)
}