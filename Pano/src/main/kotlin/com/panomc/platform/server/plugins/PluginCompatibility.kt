package com.panomc.platform.server.plugins

/**
 * Whether what a version supports overlaps with what a server needs.
 *
 * "Nothing required" means "everything matches": a proxy has no Minecraft version to filter on and
 * vanilla has no loader, and in both cases the honest answer is that the constraint does not
 * apply, not that nothing is compatible. Comparison is case-insensitive because each source
 * capitalises its loader names differently.
 */
object PluginCompatibility {
    fun matches(supported: List<String>, required: List<String>): Boolean {
        if (required.isEmpty()) {
            return true
        }

        if (supported.isEmpty()) {
            return false
        }

        val normalised = supported.map { it.lowercase() }.toSet()

        return required.any { normalised.contains(it.lowercase()) }
    }
}
