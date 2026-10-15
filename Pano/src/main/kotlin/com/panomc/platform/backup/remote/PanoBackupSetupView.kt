package com.panomc.platform.backup.remote

import com.panomc.platform.config.PanoConfig
import io.vertx.core.json.JsonObject

/**
 * `GET /api/v1/setup/pano-backup`: `{website, connected, account {username} | null, freeGb (number | null), plan {name,
 * quotaBytes, free} | null, usage {usedBytes} | null, daily, manageUrl}`.
 *
 * [list] is the website's `GET /host/connected/backups` answer (`{tier, subscription, usage {usedBytes, quotaBytes |
 * null, ...}, free {quotaBytes}, ...}`), null when not connected or it failed. A paid `tier` gives
 * `{name: tier.name, quotaBytes: usage.quotaBytes, free: false}`; no tier but a quota is the free plan
 * (`{name: null, quotaBytes, free: true}`); no quota at all (a lapsed plan over the free quota, or no free plan) is null.
 */
object PanoBackupSetupView {
    fun build(config: PanoConfig, list: JsonObject?, freeGb: Number?, daily: Boolean): Map<String, Any?> {
        val connected = config.panoAccount.accessToken.isNotBlank()
        val usage = list?.getJsonObject("usage")
        val quota = (usage?.getValue("quotaBytes") as? Number)?.toLong()
        val tier = list?.getJsonObject("tier")

        val plan = when {
            list == null || quota == null -> null
            tier != null -> mapOf("name" to tier.getString("name"), "quotaBytes" to quota, "free" to false)
            else -> mapOf("name" to null, "quotaBytes" to quota, "free" to true)
        }

        return mapOf(
            "website" to config.panoWebsiteUrl,
            "connected" to connected,
            "account" to if (connected) mapOf("username" to config.panoAccount.username) else null,
            "freeGb" to freeGb,
            "plan" to plan,
            "usage" to (usage?.getValue("usedBytes") as? Number)?.let { mapOf("usedBytes" to it.toLong()) },
            "daily" to daily,
            "manageUrl" to config.panoWebsiteUrl.trimEnd('/') + "/host/manage/backups"
        )
    }
}
