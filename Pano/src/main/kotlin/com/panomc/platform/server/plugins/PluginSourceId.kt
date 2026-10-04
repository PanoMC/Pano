package com.panomc.platform.server.plugins

/**
 * The plugin and mod directories Pano can search.
 *
 * The lowercase [id] is what travels over the panel API and into activity logs, so it is the
 * stable half of this enum; the enum name is only Kotlin's handle on it.
 */
enum class PluginSourceId(val id: String, val displayName: String) {
    MODRINTH("modrinth", "Modrinth"),
    HANGAR("hangar", "Hangar"),
    CURSEFORGE("curseforge", "CurseForge");

    companion object {
        fun fromId(id: String?): PluginSourceId? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
