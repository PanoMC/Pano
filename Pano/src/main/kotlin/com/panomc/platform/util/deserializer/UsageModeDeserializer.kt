package com.panomc.platform.util.deserializer

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.panomc.platform.util.UsageMode
import java.lang.reflect.Type

/**
 * Tolerant reader for the `usage-mode` config key. Accepts the enum names in any casing
 * ("BOTH", "both", "Servers"...) and falls back to [UsageMode.BOTH] — today's behaviour — for a
 * blank, unknown or wrongly-typed value, so a hand-edited config.conf can never break startup.
 */
class UsageModeDeserializer : JsonDeserializer<UsageMode> {
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): UsageMode {
        val usageMode = try {
            json.asString
        } catch (_: Exception) {
            null
        }

        if (usageMode.isNullOrBlank()) {
            return UsageMode.BOTH
        }

        return UsageMode.entries.firstOrNull { it.name.equals(usageMode.trim(), ignoreCase = true) } ?: UsageMode.BOTH
    }
}
