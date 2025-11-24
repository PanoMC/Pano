package com.panomc.platform.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.platform.db.model.Server
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.notification.NotificationTypeDeserializer
import com.panomc.platform.notification.ServerSettingsDeserializer
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.JsonObjectDeserializer
import com.panomc.platform.util.deserializer.LenientListStringAdapterFactory
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet

abstract class DBEntity {
    companion object {
        val gson: Gson by lazy {
            val builder = GsonBuilder()

            builder.registerTypeAdapterFactory(LenientListStringAdapterFactory())
            builder.registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
            builder.registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
            builder.registerTypeAdapter(JsonObject::class.java, JsonObjectDeserializer())
            builder.registerTypeAdapter(NotificationType::class.java, NotificationTypeDeserializer())
            builder.registerTypeAdapter(Server.Companion.ServerSettings::class.java, ServerSettingsDeserializer())

            builder.create()
        }

        inline fun <reified T : DBEntity> Class<T>.from(row: Row): T =
            gson.fromJson(row.toJson().toString(), this)

        inline fun <reified T : DBEntity> Class<T>.from(rowSet: RowSet<Row>) = rowSet.map { this.from(it) }
    }

    fun toJson(): String = gson.toJson(this)
}