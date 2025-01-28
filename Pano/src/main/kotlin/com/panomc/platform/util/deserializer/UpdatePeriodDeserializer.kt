package com.panomc.platform.util.deserializer

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.panomc.platform.util.UpdatePeriod
import java.lang.reflect.Type

class UpdatePeriodDeserializer : JsonDeserializer<UpdatePeriod> {
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): UpdatePeriod {
        val updatePeriod = json.asString

        if (updatePeriod == "never") {
            return UpdatePeriod.NEVER
        }

        if (updatePeriod == "oncePerDay") {
            return UpdatePeriod.ONCE_PER_DAY
        }

        if (updatePeriod == "oncePerWeek") {
            return UpdatePeriod.ONCE_PER_WEEK
        }

        if (updatePeriod == "oncePerMonth") {
            return UpdatePeriod.ONCE_PER_MONTH
        }

        return UpdatePeriod.valueOf(updatePeriod)
    }
}