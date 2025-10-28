package com.panomc.platform.notification

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.panomc.platform.db.model.Server
import io.vertx.core.json.JsonObject
import java.lang.reflect.Type

class ServerSettingsDeserializer : JsonDeserializer<Server.Companion.ServerSettings> {

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Server.Companion.ServerSettings {
        val text = json.asString

        return JsonObject(text).mapTo(Server.Companion.ServerSettings::class.java)
    }
}