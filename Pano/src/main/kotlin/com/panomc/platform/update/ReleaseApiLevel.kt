package com.panomc.platform.update

import io.vertx.core.json.JsonObject

/**
 * The API levels of one Pano release (doc 04 section 7): the content of its release asset `pano-api-level.json`,
 * `{"apiLevel": 1, "minApiLevel": 1}` (the property names `current` and `min` are accepted too). Either number may
 * be missing; both missing is not a level file. The Pano API puts the same two numbers on each entry of
 * `/releases/pano`, and [ReleaseInfo] carries them.
 */
data class ReleaseApiLevel(val apiLevel: Int?, val minApiLevel: Int?) {
    companion object {
        /** The asset that tells the levels of a release. */
        const val ASSET_NAME = "pano-api-level.json"

        private const val MAX_LEVEL = 1_000_000

        /** A level as a JSON value: a whole number in `0..1,000,000`, else null. */
        fun level(value: Any?): Int? =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }?.takeIf { it in 0..MAX_LEVEL }?.toInt()

        /** Parses the asset body; null when it is not JSON or carries neither number. */
        fun parse(body: String?): ReleaseApiLevel? {
            val json = try {
                JsonObject(body ?: return null)
            } catch (_: Exception) {
                return null
            }

            val parsed = ReleaseApiLevel(
                level(json.getValue("apiLevel")) ?: level(json.getValue("current")),
                level(json.getValue("minApiLevel")) ?: level(json.getValue("min"))
            )

            return if (parsed.apiLevel == null && parsed.minApiLevel == null) null else parsed
        }
    }
}
