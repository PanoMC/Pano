package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration7To8 : ConfigMigration(7, 8, "Fix update period") {
    override fun migrate(config: JsonObject) {
        val updatePeriodConfig = config.getString("update-period")

        val updatePeriod = when (updatePeriodConfig) {
            "never" -> {
                "NEVER"
            }

            "oncePerDay" -> {
                "ONCE_PER_DAY"
            }

            "oncePerWeek" -> {
                "ONCE_PER_WEEK"
            }

            else -> {
                "ONCE_PER_MONTH"
            }
        }

        config.put("update-period", updatePeriod)
    }
}