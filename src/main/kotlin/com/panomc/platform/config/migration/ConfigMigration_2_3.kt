package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigManager.Companion.putAll
import com.panomc.platform.config.ConfigMigration
import com.panomc.platform.util.KeyGeneratorUtil
import io.jsonwebtoken.io.Encoders

@Suppress("ClassName")
@Migration
class ConfigMigration_2_3(
    override val FROM_VERSION: Int = 2,
    override val VERSION: Int = 3,
    override val VERSION_INFO: String = "Add jwt-keys config"
) : ConfigMigration() {
    override fun migrate(configManager: ConfigManager) {
        val key = KeyGeneratorUtil.generateJWTKeys()

        configManager.getConfig().putAll(
            mapOf(
                "jwt-keys" to mapOf(
                    "private" to Encoders.BASE64.encode(key.private.encoded),
                    "public" to Encoders.BASE64.encode(key.public.encoded)
                )
            )
        )
    }
}