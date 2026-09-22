package com.panomc.platform.server.plugins

/**
 * What kind of thing a catalogue search is looking for, when no server says.
 *
 * The server-scoped search derives this from the software — a Paper server wants Modrinth's
 * `plugin`, a Fabric one wants `mod` — but the create-server wizard browses modpacks *before* a
 * server exists, so there is nothing to derive it from and the panel names it outright.
 *
 * Every source files these differently: Modrinth has a `project_type` facet, CurseForge has
 * numeric class ids, and Hangar only ever has plugins. The mapping lives here so the three
 * providers do not each invent one.
 */
enum class PluginProjectType(
    val id: String,
    /** Modrinth's `project_type` facet value. */
    val modrinthProjectType: String,
    /** CurseForge's Minecraft class id, or null when CurseForge files nothing under this kind. */
    val curseForgeClassId: Int?,
    /** Whether Hangar, which is a plugin site and nothing else, can serve this kind at all. */
    val hangarSupported: Boolean
) {
    PLUGIN("plugin", "plugin", PluginLoaderMapping.CURSEFORGE_CLASS_PLUGINS, true),
    MOD("mod", "mod", PluginLoaderMapping.CURSEFORGE_CLASS_MODS, false),
    MODPACK("modpack", "modpack", PluginLoaderMapping.CURSEFORGE_CLASS_MODPACKS, false);

    companion object {
        fun fromId(id: String?): PluginProjectType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
