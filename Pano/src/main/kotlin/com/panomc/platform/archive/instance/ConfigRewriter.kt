package com.panomc.platform.archive.instance

import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcException.Code
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigSyntax
import io.vertx.core.json.JsonObject

/**
 * The config half of a restore (archive-format.md section 2): the archived `config.conf` is kept
 * (jwt-key, pano-account, email, site settings, db prefix) except the keys that belong to the
 * machine it lands on, which come from the target's current config.
 */
object ConfigRewriter {
    /** `database {…}` keys taken from the target; `prefix` and `type` stay the archive's. */
    val TARGET_DATABASE_KEYS = listOf("host", "name", "username", "password")

    /** Top-level keys taken whole from the target: listen address, ports, TLS, uploads location. */
    val TARGET_KEYS = listOf("server", "file-uploads-folder")

    fun parseHocon(text: String): JsonObject = try {
        val config = ConfigFactory
            .parseString(text, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false))
            .resolve(ConfigResolveOptions.noSystem())

        JsonObject(config.root().render(ConfigRenderOptions.concise()))
    } catch (e: Exception) {
        throw PanoArcException(Code.INVALID_ARCHIVE, "The archived config.conf cannot be read.", e)
    }

    /**
     * [archive] with the target-specific keys of [target] applied; [hostedEmail] (the Pano Host SMTP
     * relay) replaces `email` when the target is Pano Host.
     */
    fun rewrite(archive: JsonObject, target: JsonObject, hostedEmail: JsonObject? = null): JsonObject {
        val result = archive.copy()
        val database = (archive.getJsonObject("database") ?: JsonObject()).copy()
        val targetDatabase = target.getJsonObject("database") ?: JsonObject()

        TARGET_DATABASE_KEYS.forEach { database.put(it, targetDatabase.getValue(it)) }
        result.put("database", database)

        TARGET_KEYS.forEach { key ->
            if (target.containsKey(key)) {
                result.put(key, target.getValue(key).let { if (it is JsonObject) it.copy() else it })
            }
        }

        hostedEmail?.let { result.put("email", it.copy()) }

        return result
    }
}
