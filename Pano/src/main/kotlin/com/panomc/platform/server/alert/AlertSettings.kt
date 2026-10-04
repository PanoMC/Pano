package com.panomc.platform.server.alert

import io.vertx.core.json.JsonObject

/** Whether one kind of alert is raised at all, and whether it also goes out by e-mail. */
data class AlertSetting(val enabled: Boolean, val email: Boolean)

/**
 * The alert switch grid, as it is stored and as it is edited.
 *
 * Kept in `system_property` rather than in config.conf because it is edited from the panel while
 * Pano is running, and config.conf is rewritten on shutdown — a setting changed in the panel and a
 * file rewritten from memory are a pair that loses one of them.
 *
 * Reading is deliberately total: a kind missing from the stored JSON, a kind that no longer
 * exists, a value of the wrong type or a document that is not JSON at all all resolve to the
 * defaults. Alerting is a safety feature, and a malformed settings row must not be able to turn
 * it off.
 */
object AlertSettings {
    /** The `system_property` option this lives under. */
    const val PROPERTY = "server_alerts"

    /** Everything on, nothing by e-mail: notifications are free, e-mail is a decision. */
    fun defaults(): Map<ServerAlertKind, AlertSetting> =
        ServerAlertKind.entries.associateWith { AlertSetting(enabled = true, email = false) }

    fun parse(stored: String?): Map<ServerAlertKind, AlertSetting> {
        val document = try {
            stored?.takeIf { it.isNotBlank() }?.let { JsonObject(it) }
        } catch (_: Exception) {
            null
        } ?: return defaults()

        return ServerAlertKind.entries.associateWith { kind ->
            val entry = document.getJsonObject(kind.name)

            AlertSetting(
                enabled = entry?.getBoolean("enabled") ?: true,
                email = entry?.getBoolean("email") ?: false
            )
        }
    }

    /** Reads the panel's `{ "<KIND>": { enabled, email } }` map, ignoring anything unrecognised. */
    fun fromJson(document: JsonObject?): Map<ServerAlertKind, AlertSetting> {
        val body = document ?: JsonObject()

        return ServerAlertKind.entries.associateWith { kind ->
            val entry = body.getJsonObject(kind.name)

            AlertSetting(
                enabled = entry?.getBoolean("enabled") ?: true,
                email = entry?.getBoolean("email") ?: false
            )
        }
    }

    fun toJson(settings: Map<ServerAlertKind, AlertSetting>): JsonObject {
        val document = JsonObject()

        ServerAlertKind.entries.forEach { kind ->
            val setting = settings[kind] ?: AlertSetting(enabled = true, email = false)

            document.put(
                kind.name,
                JsonObject()
                    .put("enabled", setting.enabled)
                    .put("email", setting.email)
            )
        }

        return document
    }
}
