package com.panomc.platform.gate

import com.panomc.platform.ApiLevel
import com.panomc.platform.error.FailedToInstallResource
import com.panomc.platform.ui.ThemeApiLevelUnsupported

/**
 * Gate 1 at install time (doc 04 section 7): a file whose declared API level this Pano cannot run is refused before
 * anything is unloaded, moved or written, so the admin gets the reason instead of a generic start failure.
 */
object InstallApiLevel {
    /** Throws [FailedToInstallResource] with the level and the supported range when the plugin cannot run here. */
    fun requireCompatiblePlugin(
        pluginId: String,
        apiLevel: Int,
        minSupported: Int = ApiLevel.MIN_SUPPORTED,
        current: Int = ApiLevel.CURRENT
    ) {
        val verdict = ApiLevelGate.check(apiLevel, minSupported, current)

        if (verdict == Verdict.OK) {
            return
        }

        throw FailedToInstallResource(
            extras = mapOf(
                "message" to "The plugin '$pluginId' was built for API level $apiLevel; this Pano supports $minSupported to $current.",
                "pluginId" to pluginId,
                "verdict" to verdict.name,
                "apiLevel" to apiLevel,
                "min" to minSupported,
                "current" to current
            )
        )
    }

    /** Throws [ThemeApiLevelUnsupported] (THEME_API_LEVEL_UNSUPPORTED) when the theme cannot run here. */
    fun requireCompatibleTheme(
        themeId: String,
        apiLevel: Int,
        minSupported: Int = ApiLevel.MIN_SUPPORTED,
        current: Int = ApiLevel.CURRENT
    ) {
        val verdict = ApiLevelGate.check(apiLevel, minSupported, current)

        if (verdict != Verdict.OK) {
            throw ThemeApiLevelUnsupported(themeId, verdict.name, apiLevel, minSupported, current)
        }
    }
}
