package com.panomc.platform.notification

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.annotation.NotificationDefinition
import java.lang.reflect.Type

class NotificationTypeDeserializer : JsonDeserializer<NotificationType> {
    private val notificationDefinitions by lazy {
        val beans = applicationContext.getBeansWithAnnotation(NotificationDefinition::class.java)

        beans.map { it.value as NotificationType }
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): NotificationType {
        val typeName = json.asString

        return notificationDefinitions.find { it.getName() == typeName }!!
    }
}