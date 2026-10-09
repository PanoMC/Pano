package com.panomc.platform.error

import com.panomc.platform.ApiLevel
import com.panomc.platform.model.Error

/**
 * A plugin outside the supported API level can never be switched on (doc 04 section 7): every way of enabling or
 * starting it (panel, console, install) answers with this error instead of starting it or doing nothing. It can
 * still be updated or deleted. [dependencyId] names the required plugin that is the incompatible one, when the
 * refused plugin is only blocked through its dependency.
 */
class PluginApiLevelUnsupported(
    pluginId: String = "",
    verdict: String = "TOO_OLD",
    apiLevel: Int = 0,
    min: Int = ApiLevel.MIN_SUPPORTED,
    current: Int = ApiLevel.CURRENT,
    dependencyId: String? = null
) : Error(
    "PLUGIN_API_LEVEL_UNSUPPORTED",
    400,
    "",
    mapOf(
        "message" to "The plugin '${dependencyId ?: pluginId}' was built for API level $apiLevel; this Pano supports $min to $current. It can not be enabled until a compatible version is installed.",
        "pluginId" to pluginId,
        "verdict" to verdict,
        "apiLevel" to apiLevel,
        "min" to min,
        "current" to current
    ) + (if (dependencyId != null) mapOf("dependencyId" to dependencyId) else emptyMap())
)
