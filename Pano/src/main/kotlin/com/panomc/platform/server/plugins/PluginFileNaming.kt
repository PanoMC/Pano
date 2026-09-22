package com.panomc.platform.server.plugins

/**
 * The rules for the one string in a plugin install that becomes a path on a node's disk.
 *
 * Pure, because all three of its jobs are decisions that must be reviewable on their own: whether
 * a name from a third-party CDN is safe to write, what an update of an already-installed plugin
 * is called, and whether a file is the Pano plugin that must never be removed from under a
 * managed server.
 */
object PluginFileNaming {
    const val MAX_NAME_LENGTH = 200

    /** Whether [name] is a single path segment ending in `.jar`. */
    fun isJarName(name: String?): Boolean {
        val value = name?.trim().orEmpty()

        if (value.isEmpty() || value.length > MAX_NAME_LENGTH) {
            return false
        }

        if (value.contains('/') || value.contains('\\') || value.contains("..")) {
            return false
        }

        if (value.any { it.isISOControl() }) {
            return false
        }

        return value.lowercase().endsWith(".jar") && value.length > JAR_SUFFIX.length
    }

    /**
     * Turns whatever a source calls a file into a name that can be written.
     *
     * A source is free to publish `../../etc/cron.d/x.jar`, a name with a newline in it, or no
     * name at all, so nothing from upstream is used verbatim: everything outside a conservative
     * set becomes `-`, and a name that is still unusable falls back to [fallback].
     */
    fun sanitise(filename: String?, fallback: String): String {
        val raw = filename?.trim().orEmpty().substringAfterLast('/').substringAfterLast('\\')

        val cleaned = raw
            .map { if (it.isLetterOrDigit() || it in ALLOWED_PUNCTUATION) it else '-' }
            .joinToString("")
            .trim('-', '.')
            .take(MAX_NAME_LENGTH)

        val candidate = if (cleaned.lowercase().endsWith(JAR_SUFFIX)) cleaned else "$cleaned$JAR_SUFFIX"

        return if (isJarName(candidate)) candidate else "$fallback$JAR_SUFFIX"
    }

    /**
     * The part of a jar name that identifies the plugin rather than the build.
     *
     * `EssentialsX-2.21.2.jar` and `EssentialsX-2.22.0.jar` are the same plugin, and an update
     * that leaves both in the directory is how a server ends up loading two copies of it. Only
     * trailing segments that look like a version are stripped, so `worldedit-bukkit` keeps both
     * of its words.
     */
    fun baseNameOf(filename: String): String {
        var stem = filename.trim()

        if (stem.lowercase().endsWith(JAR_SUFFIX)) {
            stem = stem.dropLast(JAR_SUFFIX.length)
        }

        while (true) {
            val separator = stem.lastIndexOfAny(charArrayOf('-', '_'))

            if (separator <= 0) {
                return stem.lowercase()
            }

            val tail = stem.substring(separator + 1)

            if (!looksLikeVersion(tail)) {
                return stem.lowercase()
            }

            stem = stem.substring(0, separator)
        }
    }

    /**
     * The already-installed jar that [filename] replaces, if any.
     *
     * Same base name, different file name: installing the exact same file over itself is not a
     * replacement, it is an overwrite, and deleting it afterwards would delete what was just
     * installed.
     */
    fun replacementFor(filename: String, existing: List<String>): String? {
        val base = baseNameOf(filename)

        if (base.isEmpty()) {
            return null
        }

        return existing.firstOrNull { it != filename && baseNameOf(it) == base }
    }

    /**
     * Whether this file is the Pano plugin a managed server is linked through.
     *
     * Removing it would disconnect the server from the panel that is being used to remove it, so
     * it is refused rather than left to an admin to discover afterwards.
     */
    fun isPanoPluginJar(filename: String): Boolean {
        val lower = filename.lowercase()

        if (!lower.endsWith(JAR_SUFFIX)) {
            return false
        }

        return lower == "pano.jar" || lower.startsWith("pano-") || lower.startsWith("pano_")
    }

    /** Whether [value] is a build identifier rather than part of the plugin's name. */
    private fun looksLikeVersion(value: String): Boolean {
        if (value.isEmpty()) {
            return false
        }

        val candidate = if (value.length > 1 && (value[0] == 'v' || value[0] == 'V')) value.substring(1) else value

        if (candidate.isEmpty() || !candidate[0].isDigit()) {
            return SNAPSHOT_WORDS.contains(value.lowercase())
        }

        return candidate.all { it.isDigit() || it == '.' || it == '+' || it.isLetter() }
    }

    private const val JAR_SUFFIX = ".jar"

    private val ALLOWED_PUNCTUATION = setOf('.', '-', '_', '+', '(', ')', ' ')

    // Only build qualifiers, never platform names: "worldedit-bukkit" is the plugin's name, and
    // stripping "bukkit" from it would make every WorldEdit build look like a different plugin.
    private val SNAPSHOT_WORDS = setOf("snapshot", "release", "shaded", "all")
}
