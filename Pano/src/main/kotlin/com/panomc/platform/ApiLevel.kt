package com.panomc.platform

import java.util.Properties

/**
 * The extension contract level of this Pano (doc 04 section 7).
 *
 * A plugin or a theme declares the level it **needs** (by default the level it was built against). It is
 * compatible when `MIN_SUPPORTED <= level <= CURRENT`; no declaration at all means level 0, a resource built
 * before the cutover, which is never compatible. [CURRENT] rises when the contract gains something a resource
 * may rely on, [MIN_SUPPORTED] only on a break. The version number of Pano itself is display only.
 *
 * Both numbers come from `gradle.properties` (`panoApiLevel`, `panoMinApiLevel`) through the classpath resource
 * `pano-api-level.properties`, which the build writes, and through the `API_LEVEL` / `MIN_API_LEVEL` attributes
 * of the jar manifest.
 */
object ApiLevel {
    const val RESOURCE = "pano-api-level.properties"

    /**
     * Lowest Minecraft plugin protocol this Pano still talks to. Plugins that announced a lower protocol (or
     * none) are listed as agents to update (doc 04 section 7, decision 46).
     */
    const val MIN_MC_PROTOCOL = 3

    /**
     * Lowest node protocol this Pano still talks to: the protocol of the cutover (the one after the last
     * pre-cutover value of [com.panomc.platform.node.NodeProtocol.VERSION]).
     */
    const val MIN_NODE_PROTOCOL = 6

    private val levels: Pair<Int, Int> by lazy { read() }

    /** The level this Pano implements. */
    val CURRENT: Int get() = levels.first

    /** The lowest level this Pano still accepts. */
    val MIN_SUPPORTED: Int get() = levels.second

    private fun read(): Pair<Int, Int> {
        val properties = Properties()

        ApiLevel::class.java.getResourceAsStream("/$RESOURCE")?.use { properties.load(it) }

        // A run that skipped the build step (an IDE with a stale classpath) gets the levels of this source tree.
        val current = properties.getProperty("current")?.trim()?.toIntOrNull() ?: DEFAULT_LEVEL
        val min = properties.getProperty("min")?.trim()?.toIntOrNull() ?: DEFAULT_LEVEL

        return current to min
    }

    private const val DEFAULT_LEVEL = 1
}
